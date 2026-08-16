#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
端侧知识库检索评测脚本 —— 与 App 内 LocalKbEngine 完全同口径。

复现 docs/evaluation.md 中的 31 条 query 评测：
  embed(query) → 向量检索(50) + 关键词检索(50) → RRF 融合(K=60)
  → 阈值过滤 0.3 → top_k(3)，判定 top-1 / top-3 命中率。

口径对照（app/src/main/java/com/palmagent/app/kb/）：
  - OnnxEmbedder     : bge-small-zh INT8 ONNX，mean pooling + L2 归一化
  - InMemoryVectorIndex : task 0.7 + keyword 0.3 加权余弦
  - KeywordSearcher  : ≥2 字子串匹配 + 命中次数打分
  - LocalKbEngine    : RRF_K=60、SCORE_THRESHOLD=0.3
  - SopJsonLoader    : taskText=task_name，keywordText=keywords 顿号连接
  - BertTokenizer    : 中文按字切分，英文/数字 WordPiece，lowercase

用法：
    python eval/eval_retrieval.py [--kb-dir app/src/main/assets/kb] [--topk 3]

依赖：onnxruntime、numpy
    pip install onnxruntime numpy
"""

import argparse
import json
import os
import re
import sys
import time
from typing import Dict, List, Optional, Tuple

import numpy as np

# ============ 评测集（对齐 docs/evaluation.md，query 与知识库实际数据一致） ============
# 注：饿了么平台已下线，知识库实际条目为「淘宝闪购外卖」（sop 006b3e83f8，
# task=淘宝闪购找一下附近可以送外卖的特定菜品），故 query 1 使用淘宝闪购表述。
# 31 条 query：覆盖 17 个 App + 跨App 场景，每条均对应知识库真实 SOP 语义。
# (query, 期望 App, 领域)
QUERIES = [
    # ---- 购物/外卖 ----
    ("在淘宝闪购点附近可以送外卖的麻辣香锅", "淘宝闪购", "购物"),
    ("在淘宝找一家叫优衣库的店把白色T恤加到购物车", "淘宝", "购物"),
    ("在淘宝上逛逛找到优衣库店铺把黑色修身牛仔裤加入购物车", "淘宝", "购物"),
    ("在美团点附近麦当劳的两份汉堡送到家", "美团", "生活"),
    ("美团上点迈德思客的一份香辣鸡腿中国堡送到家", "美团", "生活"),
    ("在京东上找匡威1970s低帮红色38码加到购物车", "京东", "购物"),
    # ---- 导航/地图 ----
    ("用高德地图搜一下附近的加油站", "高德地图", "导航"),
    ("用高德地图导航到杭州东站", "高德地图", "导航"),
    ("用高德地图导航到最近的医院然后把位置分享给微信里的张三", "高德地图", "跨App"),
    ("用高德把西安科技大学临潼校区设置成家庭住址并保存到收藏夹", "高德地图", "导航"),
    ("用百度地图导航到大明宫国家遗址公园", "百度地图", "导航"),
    # ---- 社交 ----
    ("把微信里张三发给我的最后一个视频转发给李四", "微信", "社交"),
    ("微信好友Charles发给我的最后一条消息转发给好友孙杰", "微信", "社交"),
    ("把当前位置发送给微信联系人", "微信", "社交"),
    ("在小红书搜一个用户", "小红书", "社交"),
    ("在小红书上找美容护肤笔记并点赞前三篇", "小红书", "社交"),
    # ---- 视频/音乐 ----
    ("在B站播放三体", "B站", "视频"),
    ("在B站查看up主影视飓风的视频", "B站", "视频"),
    ("腾讯视频搜电视剧人民警察并从第5集开始播放", "腾讯视频", "视频"),
    ("优酷视频播放电影加菲猫家族", "优酷视频", "视频"),
    ("QQ音乐搜特定歌曲并播放", "QQ音乐", "音乐"),
    ("QQ音乐停止播放当前歌曲然后播放收藏的歌曲", "QQ音乐", "音乐"),
    ("网易云播放排行榜VIP热歌榜前十的歌", "网易云音乐", "音乐"),
    ("网易云音乐搜锻炼的歌曲并从第4首开始播放", "网易云音乐", "音乐"),
    ("酷狗音乐搜林俊杰的爱笑的眼睛并分享给联系人", "酷狗音乐", "音乐"),
    # ---- 生活/效率 ----
    ("在大众点评找一家叫西湖春天的餐厅订明晚八点的4人桌", "大众点评", "生活"),
    ("在点评上搜曲江池遗址公园领优惠券", "大众点评", "生活"),
    ("在携程上搜北京朝阳区四星级酒店的第一家分享给微信", "携程", "跨App"),
    ("用腾讯会议创建快速会议并打开视频", "腾讯会议", "效率"),
    ("用浏览器查一下2024年最值得关注的全球健康问题", "浏览器", "查询"),
    ("在知乎上了解专利法律", "知乎", "查询"),
    ("用高德地图找附近评分最高的火锅店并把位置分享给微信好友", "高德地图", "跨App"),
]


# ============ BertTokenizer（Python 移植，与 BertTokenizer.kt 一致） ============

def _is_chinese(c: str) -> bool:
    return 0x4E00 <= ord(c) <= 0x9FFF


class BertTokenizer:
    def __init__(self, vocab_path: str):
        self.vocab: Dict[str, int] = {}
        with open(vocab_path, encoding="utf-8") as f:
            for i, line in enumerate(f):
                line = line.rstrip("\n")
                if line:
                    self.vocab[line] = i
        self.unk = "[UNK]"
        self.cls = "[CLS]"
        self.sep = "[SEP]"

    def _wordpiece(self, word: str) -> List[str]:
        tokens: List[str] = []
        if not word:
            return tokens
        start = 0
        n = len(word)
        while start < n:
            end = n
            hit = None
            while start < end:
                sub = word[start:end] if start == 0 else "##" + word[start:end]
                if sub in self.vocab:
                    hit = sub
                    break
                end -= 1
            if hit is None:
                tokens.append(self.unk)
                start += 1
            else:
                tokens.append(hit)
                start = end
        return tokens

    def encode(self, text: str, max_length: int = 512) -> Tuple[List[int], List[int], List[int]]:
        raw: List[str] = []
        chars = list(text)
        i = 0
        while i < len(chars):
            c = chars[i]
            if c.isspace():
                i += 1
            elif _is_chinese(c):
                raw.append(c)
                i += 1
            elif c.isalnum():
                j = i
                while j < len(chars) and chars[j].isalnum() and not _is_chinese(chars[j]):
                    j += 1
                raw.extend(self._wordpiece(text[i:j].lower()))
                i = j
            else:
                raw.append(c)
                i += 1
        max_content = max_length - 2
        truncated = raw[:max_content]
        ids = [self.vocab.get(self.cls, 0)]
        unk_id = self.vocab.get(self.unk, 0)
        for t in truncated:
            ids.append(self.vocab.get(t, unk_id))
        ids.append(self.vocab.get(self.sep, 0))
        mask = [1] * len(ids)
        types = [0] * len(ids)
        return ids, mask, types


# ============ OnnxEmbedder（与 OnnxEmbedder.kt 一致） ============

class OnnxEmbedder:
    def __init__(self, model_path: str, vocab_path: str, embed_dim: int = 512):
        import onnxruntime as ort

        self.dim = embed_dim
        so = ort.SessionOptions()
        so.intra_op_num_threads = 2
        self.session = ort.InferenceSession(model_path, sess_options=so,
                                            providers=["CPUExecutionProvider"])
        self.tokenizer = BertTokenizer(vocab_path)

    def embed(self, text: str) -> np.ndarray:
        ids, mask, types = self.tokenizer.encode(text, 512)
        seq = len(ids)
        inputs = {
            "input_ids": np.asarray([ids], dtype=np.int64),
            "attention_mask": np.asarray([mask], dtype=np.int64),
            "token_type_ids": np.asarray([types], dtype=np.int64),
        }
        last_hidden = self.session.run(None, inputs)[0]  # [1, seq, dim]
        token_vecs = last_hidden[0]  # [seq, dim]
        # mean pooling（按 attention mask）
        mask_arr = np.asarray(mask, dtype=np.float32)[:, None]
        pooled = (token_vecs * mask_arr).sum(axis=0) / max(mask_arr.sum(), 1.0)
        # L2 归一化
        norm = np.linalg.norm(pooled)
        if norm > 0:
            pooled = pooled / norm
        return pooled.astype(np.float32)


# ============ SOP 加载（与 SopJsonLoader.kt 一致） ============

class SopChunk:
    def __init__(self, sop_id: str, task_name: str, original_task_name: str,
                 app_name: str, keywords: List[str], task_text: str,
                 keyword_text: str):
        self.sop_id = sop_id
        self.task_name = task_name
        self.original_task_name = original_task_name
        self.app_name = app_name
        self.keywords = keywords
        self.task_text = task_text
        self.keyword_text = keyword_text


def load_sops(sop_dir: str) -> List[SopChunk]:
    chunks: List[SopChunk] = []
    if not os.path.isdir(sop_dir):
        raise FileNotFoundError(f"SOP 目录不存在: {sop_dir}")
    for fname in sorted(os.listdir(sop_dir)):
        if not fname.endswith(".json"):
            continue
        with open(os.path.join(sop_dir, fname), encoding="utf-8") as f:
            raw = json.load(f)
        sop_id = str(raw.get("sop_id", "")).strip()
        task_name = str(raw.get("task_name", "")).strip()
        app_name = str(raw.get("app_name", "")).strip()
        if not sop_id or not task_name:
            continue
        keywords = [str(k).strip() for k in raw.get("keywords", []) if str(k).strip()]
        chunks.append(SopChunk(
            sop_id=sop_id,
            task_name=task_name,
            original_task_name=str(raw.get("original_task_name", "")).strip(),
            app_name=app_name,
            keywords=keywords,
            task_text=task_name,
            keyword_text="，".join(keywords),
        ))
    return chunks


# ============ 检索（与 InMemoryVectorIndex / KeywordSearcher / LocalKbEngine 一致） ============

class VectorIndex:
    def __init__(self, records: List[Tuple[str, np.ndarray, Optional[np.ndarray]]],
                 w_task: float = 0.7, w_kw: float = 0.3):
        self.records = records
        self.w_task = w_task
        self.w_kw = w_kw

    def search(self, qvec: np.ndarray, limit: int = 50) -> List[Tuple[str, float]]:
        scored = []
        for sop_id, task_vec, kw_vec in self.records:
            ts = float(np.dot(qvec, task_vec))
            ks = float(np.dot(qvec, kw_vec)) if kw_vec is not None else 0.0
            scored.append((sop_id, self.w_task * ts + self.w_kw * ks))
        scored.sort(key=lambda x: x[1], reverse=True)
        return scored[:limit]


class KeywordSearcher:
    def __init__(self, records: List[SopChunk]):
        self.records = records

    def search(self, query: str, limit: int = 50) -> List[Tuple[str, float]]:
        terms = [t.strip() for t in re.split(r"[\s，。、；：！？/]+", query)]
        terms = [t for t in terms if len(t) >= 2]
        if not terms:
            return []
        scored = []
        for r in self.records:
            text = " ".join([r.task_name, " ".join(r.keywords), r.app_name, r.original_task_name])
            score = 0.0
            for t in terms:
                idx = text.find(t)
                while idx >= 0:
                    score += 1.0
                    idx = text.find(t, idx + len(t))
            if score > 0:
                scored.append((r.sop_id, score))
        scored.sort(key=lambda x: x[1], reverse=True)
        return scored[:limit]


def rrf_fuse(a: List[Tuple[str, float]], b: List[Tuple[str, float]],
             k: int = 60, limit: int = 50) -> List[Tuple[str, float]]:
    rank: Dict[str, float] = {}
    for i, (sop_id, _) in enumerate(a):
        rank[sop_id] = rank.get(sop_id, 0.0) + 1.0 / (k + i + 1)
    for i, (sop_id, _) in enumerate(b):
        rank[sop_id] = rank.get(sop_id, 0.0) + 1.0 / (k + i + 1)
    fused = sorted(rank.items(), key=lambda x: x[1], reverse=True)
    return fused[:limit]


def _app_match(a: str, b: str) -> bool:
    if not a or not b:
        return False
    if a == b:
        return True
    return len(a) >= 2 and len(b) >= 2 and (a in b or b in a)


# ============ 主流程 ============

def main() -> int:
    parser = argparse.ArgumentParser(description="端侧知识库检索评测（与 LocalKbEngine 同口径）")
    parser.add_argument("--kb-dir", default=os.path.join("app", "src", "main", "assets", "kb"),
                        help="kb 资产目录（含 onnx/ 与 sop_raw/）")
    parser.add_argument("--topk", type=int, default=3, help="top_k（默认 3）")
    parser.add_argument("--threshold", type=float, default=0.3, help="分数阈值（默认 0.3）")
    args = parser.parse_args()

    onnx_path = os.path.join(args.kb_dir, "onnx", "model_quantized.onnx")
    vocab_path = os.path.join(args.kb_dir, "vocab.txt")
    sop_dir = os.path.join(args.kb_dir, "sop_raw")
    for p in (onnx_path, vocab_path, sop_dir):
        if not os.path.exists(p):
            print(f"[错误] 找不到 {p}，请确认 --kb-dir 指向 app/src/main/assets/kb", file=sys.stderr)
            return 1

    print(f"加载 ONNX 嵌入模型: {onnx_path}")
    embedder = OnnxEmbedder(onnx_path, vocab_path)

    print(f"加载 SOP: {sop_dir}")
    sops = load_sops(sop_dir)
    print(f"共 {len(sops)} 条 SOP，开始嵌入建库...")

    t_build0 = time.perf_counter()
    records = []
    for i, s in enumerate(sops):
        tv = embedder.embed(s.task_text)
        kv = embedder.embed(s.keyword_text) if s.keyword_text else None
        records.append((s.sop_id, tv, kv))
        if (i + 1) % 100 == 0:
            print(f"  嵌入进度 {i + 1}/{len(sops)}")
    t_build = (time.perf_counter() - t_build0) * 1000
    print(f"建库完成：{len(sops)} 条，耗时 {t_build:.0f}ms（含模型加载）\n")

    vec_index = VectorIndex(records)
    kw_searcher = KeywordSearcher(sops)
    by_id = {t[0]: t for t in records}
    sop_by_id = {s.sop_id: s for s in sops}

    print(f"{'No':<4}{'Query':<42}{'期望App':<10}{'命中':<10}{'分数':<7}{'延迟(ms)':<9}")
    print("-" * 88)
    top1_hits = 0
    topk_hits = 0
    total_latency = 0.0
    rows = []
    for idx, (query, expect_app, domain) in enumerate(QUERIES, start=1):
        t0 = time.perf_counter()
        qvec = embedder.embed(query)
        vec_results = vec_index.search(qvec, 50)
        kw_results = kw_searcher.search(query, 50)
        fused = rrf_fuse(vec_results, kw_results, 60, 50)
        # 阈值过滤 + 组装分数（与 LocalKbEngine.search 一致）
        results = []
        for sop_id, _ in fused[: args.topk]:
            ts = float(np.dot(qvec, by_id[sop_id][1]))
            ks = float(np.dot(qvec, by_id[sop_id][2])) if by_id[sop_id][2] is not None else 0.0
            score = 0.7 * ts + 0.3 * ks
            results.append((sop_id, score))
        elapsed = (time.perf_counter() - t0) * 1000
        total_latency += elapsed

        rank_hit = None
        hit_score = None
        for rank, (sop_id, score) in enumerate(results, start=1):
            if score < args.threshold:
                continue
            if _app_match(sop_by_id[sop_id].app_name, expect_app):
                rank_hit = rank
                hit_score = score
                break
        if rank_hit is not None:
            if rank_hit == 1:
                top1_hits += 1
            topk_hits += 1
        hit_str = f"✅ top-{rank_hit}" if rank_hit else "❌ 未命中"
        score_str = f"{hit_score:.2f}" if hit_score is not None else "-"
        print(f"{idx:<4}{query:<42}{expect_app:<10}{hit_str:<10}"
              f"{score_str:<7}"
              f"{elapsed:<9.1f}")
        rows.append({
            "no": idx, "query": query, "expect_app": expect_app, "domain": domain,
            "hit_rank": rank_hit, "hit_score": hit_score, "latency_ms": round(elapsed, 2),
        })

    n = len(QUERIES)
    avg_latency = total_latency / n
    print("-" * 88)
    print(f"\n核心指标：")
    print(f"  top-{args.topk} 命中率：{topk_hits}/{n} = {topk_hits / n * 100:.0f}%")
    print(f"  top-1 命中率：{top1_hits}/{n} = {top1_hits / n * 100:.0f}%")
    hit_scores = [r["hit_score"] for r in rows if r["hit_score"] is not None]
    if hit_scores:
        print(f"  命中平均分：{sum(hit_scores) / len(hit_scores):.2f}")
    print(f"  平均端到端延迟：{avg_latency:.0f}ms（embed + 检索，不含建库）")

    out_json = os.path.join(os.path.dirname(__file__), "eval_retrieval_results.json")
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump({
            "topk": args.topk, "threshold": args.threshold,
            "total": n, "topk_hits": topk_hits, "top1_hits": top1_hits,
            "avg_latency_ms": round(avg_latency, 2), "rows": rows,
        }, f, ensure_ascii=False, indent=2)
    print(f"\n结果已写入 {out_json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
