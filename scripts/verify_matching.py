#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Offline mirror of SearchHelper's question-bank matching logic.

Purpose: verify the Han-only normalization + adaptive Dice changes WITHOUT an Android
toolchain. It replicates the OLD normalize/match (pre-change) and the NEW one and
compares behaviour on the real assets/question_bank.csv, including OCR-noise-injected
queries. This is a logic check only - it does not compile the app.

Run:  python verify_matching.py
"""
import csv
import io
import os
import re
import sys
import unicodedata

CSV_PATH = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main",
                        "assets", "question_bank.csv")

# --------------------------------------------------------------------------- mirror

def han_only(text: str) -> str:
    """NEW normalize: keep ONLY CJK ideographs (U+3400-4DBF, U+4E00-9FFF)."""
    return "".join(c for c in text if 0x3400 <= ord(c) <= 0x4DBF or 0x4E00 <= ord(c) <= 0x9FFF)


def old_normalize(text: str) -> str:
    """OLD normalize: full->half width, strip whitespace/punctuation, lowercase."""
    out = []
    for c in text:
        o = ord(c)
        if 0xFF21 <= o <= 0xFF3A:      # Ａ-Ｚ
            out.append(chr(o - 0xFEE0))
        elif 0xFF41 <= o <= 0xFF5A:    # ａ-ｚ
            out.append(chr(o - 0xFEE0))
        elif 0xFF10 <= o <= 0xFF19:    # ０-９
            out.append(chr(o - 0xFEE0))
        else:
            out.append(c)
    joined = "".join(out)
    # Kotlin regex was [\s\p{P}\u3000-\u303F\uFF00-\uFFEF]; Python re has no \p{P},
    # so filter by Unicode category instead.
    keep = []
    for c in joined:
        o = ord(c)
        if c.isspace():
            continue
        if unicodedata.category(c).startswith("P"):
            continue
        if 0x3000 <= o <= 0x303F or 0xFF00 <= o <= 0xFFEF:
            continue
        keep.append(c)
    return "".join(keep).lower()


def dice(a: str, b: str) -> float:
    if len(a) < 2 or len(b) < 2:
        return 1.0 if a == b else 0.0
    counts = {}
    for i in range(len(a) - 1):
        g = a[i:i + 2]
        counts[g] = counts.get(g, 0) + 1
    hits = 0
    for i in range(len(b) - 1):
        g = b[i:i + 2]
        left = counts.get(g, 0)
        if left > 0:
            hits += 1
            counts[g] = left - 1
    return 2.0 * hits / ((len(a) - 1) + (len(b) - 1))


def match(bank, raw: str, use_new: bool) -> object:
    norm = han_only if use_new else old_normalize
    query = norm(raw)
    if len(query) < (4 if use_new else 4) or not bank:
        return None
    entries = [(norm(q), q, a) for (q, a) in bank]
    for e in entries:
        if e[0] == query:
            return e
    min_contain = 6
    for e in entries:
        if len(e[0]) >= min_contain and (query in e[0] or e[0] in query):
            return e
    best = None
    best_score = 0.0
    runner = 0.0
    for e in entries:
        s = dice(query, e[0])
        if s > best_score:
            runner = best_score
            best_score = s
            best = e
        elif s > runner:
            runner = s
    if best is None:
        return None
    # NEW threshold is length-adaptive; OLD was a flat 0.62.
    if use_new:
        thr = 0.72 if len(query) < 10 else (0.66 if len(query) < 25 else 0.60)
        if best_score < thr or best_score - runner < 0.05:
            return None
    else:
        if best_score < 0.62:
            return None
    return best


# --------------------------------------------------------------------------- load

def load_bank(path):
    with io.open(path, "r", encoding="utf-8-sig") as f:
        rows = list(csv.reader(f))
    out = []
    for r in rows:
        if len(r) < 2:
            continue
        q, a = r[0].strip(), r[1].strip()
        if not q or not a or q.startswith("#"):
            continue
        if q == "题目" and a == "答案":
            continue
        out.append((q, a))
    return out


# --------------------------------------------------------------------------- checks

def inject_noise(q: str):
    """Simulate OCR junk around a clean stem: progress/timer/option/chrome noise."""
    return "第 3 题\n剩余 00:45  得分:88\n%s（2026版）\nA．完全不相关选项\nB．另一个选项" % q


def main():
    bank = load_bank(CSV_PATH)
    print("bank entries:", len(bank))
    if not bank:
        print("NO BANK DATA - aborting")
        return 1

    # 1) Filtering demo.
    print("\n--- Han-only filtering demo ---")
    samples = [
        "1．安全生产（2026）方针的内容是？",
        "第12题/共20题 A.选项B.选项 剩余3:45",
        "Which of the following is correct? A．x B．y",  # English-only -> empty
    ]
    for s in samples:
        print("raw :", repr(s))
        print("new :", repr(han_only(s)))
        print("old :", repr(old_normalize(s)))

    # 2) Round-trip regression: every bank question must still hit itself.
    print("\n--- self-match regression (clean query must hit) ---")
    old_miss = new_miss = 0
    for q, a in bank:
        if match(bank, q, use_new=False) is None:
            old_miss += 1
        if match(bank, q, use_new=True) is None:
            new_miss += 1
    print("old miss:", old_miss, "/", len(bank))
    print("new miss:", new_miss, "/", len(bank))

    # 3) Noise-injected queries (whole screen of OCR junk) hit rates.
    print("\n--- noise-injected query hit rate ---")
    old_hit = new_hit = 0
    for q, a in bank:
        noisy = inject_noise(q)
        if match(bank, noisy, use_new=False) is not None:
            old_hit += 1
        if match(bank, noisy, use_new=True) is not None:
            new_hit += 1
    print("old hit:", old_hit, "/", len(bank))
    print("new hit:", new_hit, "/", len(bank))

    # 4) Dice scores per question on the noise-injected query, new vs old norm.
    print("\n--- per-question dice (noisy query vs bank entry) ---")
    for q, a in bank:
        noisy = inject_noise(q)
        new_q, old_q = han_only(noisy), old_normalize(noisy)
        new_e, old_e = han_only(q), old_normalize(q)
        print("q     :", q[:30])
        print("  new : %5.3f  (%s)" % (dice(new_q, new_e), new_q[:30]))
        print("  old : %5.3f  (%s)" % (dice(old_q, old_e), old_q[:30]))

    # 5) Ambiguity boundary: margin gate (new) must reject close runners-up.
    print("\n--- margin gate sanity (simulated ambiguous pair) ---")
    sim_bank = [("安全生产方针的内容是", "x"), ("安全生产方针内容是什么", "y")]
    # OCR variant half-way between both:
    amb = "安全生产方针内容是"
    print("query :", amb)
    print("  new match ->", match(sim_bank, amb, use_new=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
