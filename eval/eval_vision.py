#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
视觉消融评测脚本（扩充版）—— 广告/干扰弹窗识别准确率评测。

评测集：temp/ad_test_samples/ 全部 185 张真实 App 截图（25 个场景目录），
目录前缀即标注：ad_* → 广告(ad)，normal_* → 正常(normal)，spec_* → 规格页(视为正常)。
覆盖 QQ音乐/酷狗/优酷/网易云/小红书/淘宝/高德/百度地图 等 App。

方法（对齐 temp/prompt_engineering.py 原实验）：
  P1-P5 提示词变体 × C1-C3 图片压缩策略，调用视觉模型（默认 GLM-4V-Flash，
  从 local.properties 读 KEYBOARD_VLM_API_KEY/URL/MODEL），回答解析为 ad/normal。

用法：
  # 1) 不调用 API，仅验证样本集与脚本（无 Key 也能跑）
  python eval/eval_vision.py --dry-run

  # 2) 完整评测（需在 local.properties 配置 KEYBOARD_VLM_API_KEY 等）
  python eval/eval_vision.py [--prompt P1|P2|P3|P4|P5] [--img-cfg C1|C2|C3] [--limit N]

输出：控制台准确率表 + eval/eval_vision_results.json
依赖：Pillow、requests（仅完整评测需要）
"""

import argparse
import base64
import glob
import io
import json
import os
import re
import sys
import time
from collections import Counter

try:
    from PIL import Image
except ImportError:
    Image = None

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAMPLE_DIR = os.path.join(BASE_DIR, "temp", "ad_test_samples")
RESULT_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval_vision_results.json")

AD_DEFINITION = (
    "广告/干扰弹窗特征：全屏或大面积遮罩、\"跳过/Skip/关闭×\"按钮、倒计时圆环、"
    "收下红包、福利提醒、升级提示、与当前任务无关的推广内容。"
    "注意：规格选择（份量/辣度/杯型/口味/糖度/冰量）、确认对话框、权限请求属于正常功能界面，不是广告。"
)

PROMPTS = {
    "P1": (
        "这张手机截图是否是广告弹窗？只回答：是 或 否",
        "",
        0.1,
    ),
    "P2": (
        f"判断当前截图是否为广告/干扰弹窗。{AD_DEFINITION} 只回答：是 或 否",
        "你是移动端界面弹窗识别专家。",
        0.1,
    ),
    "P3": (
        "按以下规则判定：\n1) 出现跳过/Skip/关闭×/倒计时/红包/福利/全屏遮罩 → 广告\n"
        "2) 规格选择/确认框/权限请求 → 正常，非广告\n3) 正常业务页面 → 正常\n"
        "请判定这张截图：只输出 是（广告）或 否（正常）",
        "你是手机自动化助手的屏幕质检员，负责识别广告弹窗。",
        0.1,
    ),
    "P4": (
        f"判断是否为广告/干扰弹窗。{AD_DEFINITION} 只输出JSON：{{\"is_ad\": true或false, \"reason\": \"简短原因\"}}",
        "你是移动端屏幕分类器，只输出 JSON。",
        0.1,
    ),
    "P5": (
        "示例：全屏广告带'跳过'倒计时 → 是；外卖规格选择（份量/辣度）→ 否；普通首页 → 否。"
        f"{AD_DEFINITION} 请判定这张截图：只输出 是 或 否",
        "你是移动端弹窗识别器。",
        0.1,
    ),
}

# 图片压缩配置: 名称 -> (max_side, quality)
IMG_CFGS = {
    "C1": (None, None),        # 原图
    "C2": (768, None),         # 长边 768
    "C3": (768, 70),           # 长边 768 + q70
}


def load_samples():
    """从 ad_test_samples 加载全部截图，目录前缀即标签。"""
    samples = []
    if not os.path.isdir(SAMPLE_DIR):
        raise FileNotFoundError(f"样本目录不存在: {SAMPLE_DIR}")
    for d in sorted(os.listdir(SAMPLE_DIR)):
        prefix = d.split("_")[0]
        if prefix == "ad":
            label = "ad"
        elif prefix in ("normal", "spec"):
            label = "normal"   # 规格选择页属正常功能界面
        else:
            continue
        for img in sorted(glob.glob(os.path.join(SAMPLE_DIR, d, "*"))):
            if img.lower().endswith((".jpeg", ".jpg", ".png")):
                # 样本 id：目录名|图片文件名（保留 step 信息）
                sid = f"{d}|{os.path.basename(img).rsplit('.', 1)[0].split('_')[-1]}"
                samples.append({"id": sid, "label": label, "path": img})
    return samples


def encode_img(path, max_side=None, quality=None):
    if Image is None:
        raise RuntimeError("需要安装 Pillow: pip install Pillow")
    img = Image.open(path)
    if max_side:
        w, h = img.size
        scale = max_side / max(w, h)
        if scale < 1:
            img = img.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
    buf = io.BytesIO()
    img.convert("RGB").save(buf, "JPEG", quality=quality if quality else 90)
    return base64.b64encode(buf.getvalue()).decode()


def load_cfg(key, default=""):
    """从 local.properties 读取配置（与 build.gradle.kts 同源）。"""
    txt = ""
    for name in ("local.properties", "local.default.properties"):
        p = os.path.join(BASE_DIR, name)
        if os.path.exists(p):
            txt = open(p, encoding="utf-8").read()
            break
    m = re.search(r"^" + re.escape(key) + r"\s*=\s*(.*?)\s*$", txt, re.M)
    return m.group(1).strip() if m else default


def call_vlm(messages, api_key, api_url, model, temperature):
    import requests
    body = {"model": model, "messages": messages, "temperature": temperature}
    r = requests.post(api_url, headers={"Authorization": f"Bearer {api_key}"},
                      json=body, timeout=60)
    if r.status_code == 200:
        return r.json()["choices"][0]["message"]["content"]
    return f"HTTP{r.status_code}"


def classify(ans):
    a = (ans or "").strip()
    if re.search(r'"is_ad"\s*:\s*true', a): return "ad"
    if re.search(r'"is_ad"\s*:\s*false', a): return "normal"
    if re.search(r'^是', a): return "ad"
    if re.search(r'^否|^不', a): return "normal"
    if "是" in a[:8] and "否" not in a[:2]: return "ad"
    return "unknown"


def run_eval(samples, prompt_name, img_cfg_name, limit=None, dry_run=False):
    prompt_text, system_text, temp = PROMPTS[prompt_name]
    max_side, quality = IMG_CFGS[img_cfg_name]
    api_key = load_cfg("KEYBOARD_VLM_API_KEY")
    api_url = load_cfg("KEYBOARD_VLM_API_URL")
    model = load_cfg("KEYBOARD_VLM_MODEL")

    if dry_run:
        print(f"[dry-run] 不调用 API；样本={len(samples)}，提示词={prompt_name}，图片={img_cfg_name}")
        return None

    if not api_key or not api_url:
        print("[跳过] 未配置 KEYBOARD_VLM_API_KEY/URL，无法调用视觉模型。")
        print("        配置方法：在 local.properties 取消注释 KEYBOARD_VLM_API_KEY 等并填入有效 Key。")
        return None

    rows = []
    for i, s in enumerate(samples[:limit] if limit else samples):
        img_b64 = encode_img(s["path"], max_side, quality)
        messages = []
        if system_text:
            messages.append({"role": "system", "content": system_text})
        messages.append({"role": "user", "content": [
            {"type": "text", "text": prompt_text},
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{img_b64}"}},
        ]})
        ans = call_vlm(messages, api_key, api_url, model, temp)
        pred = classify(ans)
        rows.append({"id": s["id"], "label": s["label"], "pred": pred,
                     "answer": (ans or "")[:200]})
        if (i + 1) % 20 == 0:
            print(f"  进度 {i + 1}/{len(samples[:limit] if limit else samples)}", flush=True)
        time.sleep(0.1)  # 温和限速，避免触发 API 频率限制
    return rows


def summarize(rows, prompt_name, img_cfg_name):
    total = len(rows)
    correct = sum(1 for r in rows if r["label"] == r["pred"])
    unknown = sum(1 for r in rows if r["pred"] == "unknown")
    per_label = {}
    for label in ("ad", "normal"):
        sub = [r for r in rows if r["label"] == label]
        if sub:
            ok = sum(1 for r in sub if r["label"] == r["pred"])
            per_label[label] = f"{ok}/{len(sub)} = {ok / len(sub) * 100:.0f}%"
    print(f"  {prompt_name}@{img_cfg_name}: {correct}/{total} = {correct / total * 100:.0f}%"
          f"（unknown={unknown}）ad={per_label.get('ad', '-')} normal={per_label.get('normal', '-')}")
    return {"prompt": prompt_name, "img_cfg": img_cfg_name,
            "total": total, "correct": correct,
            "accuracy": round(correct / total, 4) if total else 0,
            "unknown": unknown, "per_label": per_label}


def main():
    parser = argparse.ArgumentParser(description="视觉消融评测（扩充版，185 张标注截图）")
    parser.add_argument("--prompt", default="P1", choices=sorted(PROMPTS.keys()))
    parser.add_argument("--img-cfg", default="C1", choices=sorted(IMG_CFGS.keys()))
    parser.add_argument("--limit", type=int, default=None, help="限制样本数（默认全部）")
    parser.add_argument("--dry-run", action="store_true", help="不调用 API，仅验证样本集")
    args = parser.parse_args()

    samples = load_samples()
    labels = Counter(s["label"] for s in samples)
    print(f"样本集: {len(samples)} 张 | ad={labels['ad']} normal={labels['normal']}")
    print(f"覆盖目录: {len(set(s['id'].split('|')[0] for s in samples))} 个场景\n")

    rows = run_eval(samples, args.prompt, args.img_cfg, args.limit, args.dry_run)
    if rows is None:
        sys.exit(0)

    summary = summarize(rows, args.prompt, args.img_cfg)
    with open(RESULT_JSON, "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "rows": rows}, f, ensure_ascii=False, indent=2)
    print(f"\n结果已写入 {RESULT_JSON}")


if __name__ == "__main__":
    main()
