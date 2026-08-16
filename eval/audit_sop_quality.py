#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SOP 数据质量审查脚本 —— 用于定位"描述含糊 / 未优先搜索 / 泛化占位"的低质量条目。

质量标准（与 docs 规范对齐）：
  A. 能搜索一定搜索：凡可输入关键词锁定的信息（联系人、店铺名、菜品、地点等），
     步骤必须包含"点击搜索框→输入关键词→点击搜索结果条目"，不得"从列表里挑/直接选择"。
  B. UI 描述清晰：goal 必须点名具体控件位置与名称（如"微信右上角的搜索图标"、
     "页面顶部的搜索框"），不得用"对应按钮/相应的选项/合适的条目"等模糊表述。
  C. 禁止泛化占位：goal/expected 不得出现"指定地点/特定餐厅/对应联系人/目的地"等
     把具体对象抽象成占位词的写法（检索靠 task_name/keywords 泛化即可，步骤必须具体）。

用法：
    python eval/audit_sop_quality.py [--kb-dir app/src/main/assets/kb/sop_raw]
"""

import argparse
import json
import os
import re
from collections import Counter
from typing import Dict, List, Tuple

# ---- 含糊 / 占位词表 ----
VAGUE_WORDS = [
    "指定地点", "指定场所", "指定餐厅", "指定店铺", "指定菜品", "指定联系人",
    "指定对象", "指定内容", "指定位置", "指定目的地", "特定餐厅", "特定店铺",
    "特定菜品", "特定场所", "特定地点", "特定内容", "对应店铺", "对应联系人",
    "对应地点", "相应的", "合适的", "相关的条目", "对应的条目", "目的地的条目",
    "目标联系人", "需要的联系人", "需要的菜品", "需要的信息", "相关的内容",
    "目标店铺", "合适的位置", "相应的位置",
]
SEARCH_WORDS = ["搜索", "输入", "查找", "检索"]
# 微信/聊天类场景的强信号
CHAT_APPS = ["微信", "QQ", "钉钉", "企业微信", "飞书"]
# 通过搜索定位联系人的正例特征（描述清晰）
SEARCH_UI_GOOD = ["搜索框", "搜索图标", "搜索栏", "搜索按钮", "右上角的搜索", "顶部的搜索"]


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
    # 搜索 UI 是否描述清晰（点名了搜索框/图标/栏）
    search_ui_clear = any(w in text for w in SEARCH_UI_GOOD)
    # 联系人类任务：是否经过"搜索→点结果"链路
    app = sop.get("app_name", "")
    is_chat = any(c in app for c in CHAT_APPS) or "联系人" in sop.get("task_name", "")
    has_contact = "联系人" in text or "好友" in text or "聊天" in text
    search_to_contact = has_search and has_contact and ("搜索框" in text or "搜索图标" in text)
    # goal 平均长度（短句 = 可能太泛）
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kb-dir", default="app/src/main/assets/kb/sop_raw")
    args = parser.parse_args()

    sops = load_sops(args.kb_dir)
    print(f"共 {len(sops)} 条 SOP\n")

    rows = [analyze(s) for s in sops]

    # ========== 1. 总体统计 ==========
    n_vague = sum(1 for r in rows if r["vague_words"])
    n_nosearch = sum(1 for r in rows if not r["has_search"])
    n_chat_nosearch = sum(1 for r in rows if r["is_chat"] and r["has_contact"] and not r["search_to_contact"])
    print("=== 总体统计 ===")
    print(f"含含糊/占位词条数        : {n_vague}/{len(rows)}")
    print(f"无任何搜索/输入步骤的条数 : {n_nosearch}/{len(rows)}")
    print(f"聊天/联系人场景但未走搜索链路的条数: {n_chat_nosearch}")
    vague_counter = Counter()
    for r in rows:
        for w in r["vague_words"]:
            vague_counter[w] += 1
    print("\n=== 占位词 TOP 分布 ===")
    for w, c in vague_counter.most_common(15):
        print(f"  {w}: {c}")

    # ========== 2. 推荐删除候选：含糊词多 + 无搜索优先 ==========
    print("\n=== 推荐删除候选（含糊词≥2 或 聊天场景未搜索，按问题数排序） ===")
    bad = []
    for r in rows:
        problems = []
        if len(r["vague_words"]) >= 2:
            problems.append(f"占位词{len(r['vague_words'])}个: {','.join(r['vague_words'][:4])}")
        if r["is_chat"] and r["has_contact"] and not r["search_to_contact"]:
            problems.append("聊天场景未用搜索框定位联系人")
        if not r["has_search"] and r["n_steps"] > 1:
            problems.append("整条无搜索/输入步骤")
        if r["avg_goal_len"] < 10:
            problems.append(f"goal过短({r['avg_goal_len']}字)")
        if problems:
            bad.append((len(problems), r, problems))
    bad.sort(key=lambda x: (-x[0], x[1]["avg_goal_len"]))
    for _, r, problems in bad[:60]:
        print(f"  [{len(problems)}问题] {r['file']} {r['app_name']} | {r['task_name'][:40]}")
        for p in problems:
            print(f"      - {p}")
    print(f"\n候选合计: {len(bad)} 条")

    # ========== 3. 达标样例：搜索优先 + UI 清晰 + 无占位 ==========
    print("\n=== 达标样例（搜索优先 + 无占位词 + goal长度≥10） ===")
    good = []
    for r in rows:
        if r["has_search"] and not r["vague_words"] and r["avg_goal_len"] >= 12 and r["n_steps"] >= 3:
            good.append(r)
    good.sort(key=lambda x: (-x["n_steps"], -x["avg_goal_len"]))
    for r in good[:20]:
        print(f"  {r['file']} {r['app_name']} | {r['task_name'][:40]} | {r['n_steps']}步 goal均{r['avg_goal_len']}字")
    print(f"\n达标合计: {len(good)} 条")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
