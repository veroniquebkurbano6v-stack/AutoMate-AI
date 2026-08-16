#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第二轮核查：候选条目"删除后是否有同 App 同模板优质替代"。

判定逻辑：
  对每条候选（含糊词>=2 或 聊天场景未搜索）：
    1) 找出同 App 的优质条目（有搜索 + 无占位词 + goal均长>=12 + 步数>=3）
    2) 用 task_name 关键词集合计算语义重合：
       候选 task 的关键词（去掉动作修饰后的对象词）若能被子集/重合的优质 task 覆盖
       → 判定"有替代"（可删除）
      否则 → "无替代"（删除会丢场景，需保留或修复）

用法：
    python eval/audit_dedup.py [--kb-dir app/src/main/assets/kb/sop_raw]
"""

import argparse
import json
import os
import re
from collections import Counter
from typing import Dict, List, Set, Tuple

VAGUE_WORDS = [
    "指定地点", "指定场所", "指定餐厅", "指定店铺", "指定菜品", "指定联系人",
    "指定对象", "指定内容", "指定位置", "指定目的地", "特定餐厅", "特定店铺",
    "特定菜品", "特定场所", "特定地点", "特定内容", "对应店铺", "对应联系人",
    "对应地点", "相应的", "合适的", "相关的条目", "对应的条目", "目的地的条目",
    "目标联系人", "需要的联系人", "需要的菜品", "需要的信息", "相关的内容",
    "目标店铺", "合适的位置", "相应的位置",
]
SEARCH_WORDS = ["搜索", "输入", "查找", "检索"]
SEARCH_UI_GOOD = ["搜索框", "搜索图标", "搜索栏", "搜索按钮", "右上角的搜索", "顶部的搜索"]
CHAT_APPS = ["微信", "QQ", "钉钉", "企业微信", "飞书"]

# 动作/修饰词（对象词提取时剔除，避免把动作当成对象）
ACTION_STOP = set([
    "用", "在", "把", "将", "给", "找", "搜索", "查找", "搜", "查", "导航", "前往",
    "并", "和", "然后", "分享", "发送", "转发", "播放", "点赞", "打开", "进入",
    "最近", "最新", "最后", "第一条", "第二条", "第2条", "第", "条", "个", "一下",
    "当前", "目前", "指定的", "特定的", "对应的", "相关的", "合适的", "的", "了",
    "好友", "位置", "消息", "视频", "语音", "电话", "图片", "群组", "群聊", "联系人",
    "我的", "他", "她", "我", "我们", "里", "中", "上", "下", "吧", "呢", "吗",
    "第", "个", "份", "张", "段", "条消息", "内容",
])


def load_sops(sop_dir: str) -> List[Dict]:
    sops = []
    for fn in sorted(os.listdir(sop_dir)):
        if not fn.endswith(".json"):
            continue
        with open(os.path.join(sop_dir, fn), encoding="utf-8") as f:
            sop = json.load(f)
        sop["_file"] = fn
        sops.append(sop)
    return sops


def analyze(sop: Dict) -> Dict:
    steps = sop.get("steps", [])
    goals = [s.get("goal", "") for s in steps]
    expects = [s.get("expected", "") for s in steps]
    text = " ".join(goals + expects)
    hit_vague = [w for w in VAGUE_WORDS if w in text]
    has_search = any(w in text for w in SEARCH_WORDS)
    search_ui_clear = any(w in text for w in SEARCH_UI_GOOD)
    app = sop.get("app_name", "")
    is_chat = any(c in app for c in CHAT_APPS) or "联系人" in sop.get("task_name", "")
    has_contact = "联系人" in text or "好友" in text or "聊天" in text
    search_to_contact = has_search and has_contact and ("搜索框" in text or "搜索图标" in text)
    avg_goal_len = sum(len(g) for g in goals) / len(goals) if goals else 0
    return {
        "file": sop.get("_file", ""),
        "sop_id": sop.get("sop_id", ""),
        "task_name": sop.get("task_name", ""),
        "app_name": app,
        "n_steps": len(steps),
        "avg_goal_len": round(avg_goal_len, 1),
        "has_search": has_search,
        "search_ui_clear": search_ui_clear,
        "vague_words": hit_vague,
        "is_chat": is_chat,
        "has_contact": has_contact,
        "search_to_contact": search_to_contact,
    }


def is_candidate(r: Dict) -> bool:
    problems = []
    if len(r["vague_words"]) >= 2:
        problems.append("vague")
    if r["is_chat"] and r["has_contact"] and not r["search_to_contact"]:
        problems.append("chat_nosearch")
    if not r["has_search"] and r["n_steps"] > 1:
        problems.append("nosearch")
    if r["avg_goal_len"] < 10:
        problems.append("short")
    return len(problems) > 0


def is_good(r: Dict) -> bool:
    return (r["has_search"] and not r["vague_words"]
            and r["avg_goal_len"] >= 12 and r["n_steps"] >= 3)


def task_keywords(task: str) -> Set[str]:
    """提取 task_name 中的对象/意图关键词（剔除动作词与标点）。"""
    # 切分：按顿号/逗号/空格/引号
    parts = re.split(r"[，,、。\s\"'（）()]+", task)
    kws = set()
    for p in parts:
        p = p.strip()
        if not p or len(p) < 2:
            continue
        if p in ACTION_STOP:
            continue
        kws.add(p)
    return kws


def overlap_ratio(cand_kws: Set[str], good_kws: Set[str]) -> float:
    if not cand_kws:
        return 0.0
    return len(cand_kws & good_kws) / len(cand_kws)


def norm_task(task: str) -> str:
    """归一化 task_name：去引号内容（具体人名/群名）、去标点空白。"""
    t = re.sub(r"['\"“”‘’]+[^'\"“”‘’]*['\"“”‘’]+", "", task)
    t = re.sub(r"[，,、。\s（）()]+", "", t)
    return t


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kb-dir", default="app/src/main/assets/kb/sop_raw")
    args = parser.parse_args()

    sops = load_sops(args.kb_dir)
    rows = [analyze(s) for s in sops]
    cands = [r for r in rows if is_candidate(r)]
    goods = [r for r in rows if is_good(r)]
    print(f"共 {len(rows)} 条 | 候选 {len(cands)} | 优质 {len(goods)}\n")

    # 按 App 分组优质条目
    good_by_app: Dict[str, List[Dict]] = {}
    for g in goods:
        good_by_app.setdefault(g["app_name"], []).append(g)

    # 候选内部按归一化 task_name 分组（同组=彼此可替代的重复变体）
    cand_by_task: Dict[str, List[Dict]] = {}
    for c in cands:
        cand_by_task.setdefault(norm_task(c["task_name"]), []).append(c)

    # 每条候选的替代来源
    def find_alt(c: Dict) -> Tuple[float, Dict]:
        cand_kws = task_keywords(c["task_name"])
        best_ratio, best = 0.0, None
        for g in good_by_app.get(c["app_name"], []):
            ratio = overlap_ratio(cand_kws, task_keywords(g["task_name"]))
            if ratio > best_ratio:
                best_ratio, best = ratio, g
        return best_ratio, best

    no_alt, has_alt = [], []
    for c in cands:
        ratio, best = find_alt(c)
        if best is not None and ratio >= 0.5:
            has_alt.append((c, ratio, best))
        else:
            no_alt.append(c)

    # 区分：唯一无替代（同组只有1条） vs 组内可去重（同组>=2条，保留1条即可）
    unique_no_alt, dup_no_alt = [], []
    for c in no_alt:
        grp = cand_by_task[norm_task(c["task_name"])]
        if len(grp) >= 2:
            dup_no_alt.append((c, len(grp)))
        else:
            unique_no_alt.append(c)

    print("=== A. 唯一无替代（同 App 无优质替代且无重复变体 → 必须保留或修复） ===")
    for c in sorted(unique_no_alt, key=lambda x: -x["n_steps"]):
        print(f"  {c['file'][:20]} {c['app_name']} | {c['task_name'][:44]} | 步{c['n_steps']} 占位{c['vague_words'][:3]}")
    print(f"\n唯一无替代: {len(unique_no_alt)} 条")

    print("\n=== B. 组内重复（同 App 有 >=2 条同 task 变体 → 每组保留 1 条修复，其余可删） ===")
    seen = set()
    for c, grp_n in sorted(dup_no_alt, key=lambda x: -x[1]):
        key = norm_task(c["task_name"])
        if key in seen:
            continue
        seen.add(key)
        members = [m["file"][:20] for m in cand_by_task[key]]
        print(f"  [{grp_n}条] {c['app_name']} | {c['task_name'][:40]} | 文件: {', '.join(members)}")
    print(f"\n组内重复涉及: {len(seen)} 组 / {len(dup_no_alt)} 条")

    print("\n=== C. 有优质替代（可删除） ===")
    for c, ratio, g in sorted(has_alt, key=lambda x: -x[1]):
        print(f"  {c['file'][:20]} {c['app_name']} | {c['task_name'][:34]} | 重合{ratio:.0%} → 替代: {g['task_name'][:34]}")
    print(f"\n有优质替代: {len(has_alt)} 条")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
