#!/usr/bin/env python3
"""检查主线/FAQ：DB 标准话术 vs loan-pack 文本 vs 录音文件是否对齐。"""
from __future__ import annotations

import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK_PATH = ROOT / "ai-call-server/src/main/resources/dialog-scripts/loan-pack.json"
OUT_JSON = ROOT / "scripts/_text_wav_alignment_report.json"
BASE = "http://127.0.0.1:8081/api/admin"

# 6-23 批次 + 常用 m4a 手工映射（用于「文件名 vs 场景」风险提示）
M4A_TO_REMARK: dict[str, str] = {
    "开场白": "flow:01",
    "年开票": "flow:09",
    "纳税": "flow:10",
    "人寿": "flow:14",
    "公积金": "flow:06",
    "月收入": "flow:07",
    "意向不明加微": "flow:25",
    "加微失败": "flow:26",
    "追问": "flow:AA",
    "上门": "fallback:4",
    "不开票": "fallback:5",
    "别家费用低": "fallback:9",
    "公司优势": "fallback:14",
    "听不清": "fallback:19",
    "公司位置": "fallback:26",
    "补充材料": "fallback:30",
    "先发产品": "fallback:55",
    "上班没空": "fallback:51",
    "杂音": "fallback:62",
    "客户经理外呼": "fallback:63",
    "经营年限": "fallback:67",
    "担心": "fallback:69",
    "没时间": "fallback:75",
    "初次挽留": "fallback:83",
    "无房产证": "fallback:98",
    "婉拒": "fallback:104",
    "需要还嘛": "fallback:119",
    "非目标客群": "fallback:138",
    "公积金贷款": "fallback:43",
    "听得见吗": "fallback:42",
    "信号不好": "fallback:21",
    "喂？": "fallback:101",
    "喂？在听吗？": "fallback:101",
    "上不上征信": "fallback:2",
    "查征信": "fallback:125",
    "利息1": "fallback:8",
    "是不是高利贷": "fallback:141",
}


def norm(text: str) -> str:
    if not text:
        return ""
    t = text.strip().replace("\n", "")
    t = re.sub(r"[\s，,。.!！?？~～；;：:\"\"'（）()\[\]【】]+", "", t)
    return t


def wav_ok(path: str | None) -> bool:
    if not path:
        return False
    p = Path(path.replace("\\", "/").replace("./", ""))
    if not p.is_absolute():
        p = ROOT / "ai-call-server" / p
    return p.exists() and p.stat().st_size > 44


def login() -> str:
    body = json.dumps({"username": "admin", "password": "admin123"}).encode()
    req = urllib.request.Request(
        f"{BASE}/login", data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read())
    if data.get("code") != 200:
        raise RuntimeError(data.get("message") or "login failed")
    return data["data"]["token"]


def fetch_qas(token: str) -> list[dict]:
    req = urllib.request.Request(
        f"{BASE}/dialog-training/list?kbId=1&status=1",
        headers={"Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read())
    if data.get("code") != 200:
        raise RuntimeError(data.get("message") or "list failed")
    return data["data"]


def remark_key(remark: str) -> str | None:
    if not remark:
        return None
    m = re.match(r"(flow|fallback):(\w+)$", remark.strip())
    return m.group(2) if m else None


def main() -> int:
    pack = json.loads(PACK_PATH.read_text(encoding="utf-8"))
    flow_pack = {x["step"]: x for x in pack["mainFlow"]}
    fb_pack = {str(x["no"]): x for x in pack["fallbacks"]}

    token = login()
    qas = fetch_qas(token)
    by_remark: dict[str, dict] = {}
    for q in qas:
        r = q.get("remark") or ""
        if r:
            by_remark[r] = q

    report: dict = {
        "mainFlow": {"ok": [], "textMismatch": [], "missingWav": [], "packMissing": []},
        "fallback": {"ok": [], "textMismatch": [], "missingWav": [], "packMissing": []},
        "riskyM4aBinding": [],
        "multiSegmentFaq": [],
        "summary": {},
    }

    for q in qas:
        remark = q.get("remark") or ""
        if remark.startswith("flow:"):
            step = remark_key(remark) or ""
            pack_row = flow_pack.get(step)
            db_ans = (q.get("standardAnswer") or "").strip()
            pack_txt = (pack_row.get("script") or "").strip() if pack_row else ""
            has = wav_ok(q.get("answerWavPath"))
            entry = {
                "step": step,
                "scene": pack_row.get("scene", "") if pack_row else "",
                "qaId": q["id"],
                "hasWav": has,
                "wavPath": q.get("answerWavPath"),
                "textMatch": norm(db_ans) == norm(pack_txt),
                "dbPreview": db_ans[:80],
                "packPreview": pack_txt[:80],
            }
            if not pack_row:
                report["mainFlow"]["packMissing"].append(entry)
            elif not entry["textMatch"]:
                report["mainFlow"]["textMismatch"].append(entry)
            elif not has:
                report["mainFlow"]["missingWav"].append(entry)
            else:
                report["mainFlow"]["ok"].append(entry)
        elif remark.startswith("fallback:"):
            no = remark_key(remark) or ""
            pack_row = fb_pack.get(no)
            db_ans = (q.get("standardAnswer") or "").strip()
            pack_txt = (pack_row.get("answer") or "").strip() if pack_row else ""
            has = wav_ok(q.get("answerWavPath"))
            entry = {
                "no": no,
                "keywords": (pack_row.get("keywords") or "") if pack_row else "",
                "qaId": q["id"],
                "hasWav": has,
                "wavPath": q.get("answerWavPath"),
                "textMatch": norm(db_ans) == norm(pack_txt),
                "dbPreview": db_ans[:80],
                "packPreview": pack_txt[:80],
            }
            if not pack_row:
                report["fallback"]["packMissing"].append(entry)
            elif not entry["textMatch"]:
                report["fallback"]["textMismatch"].append(entry)
            elif not has:
                report["fallback"]["missingWav"].append(entry)
            else:
                report["fallback"]["ok"].append(entry)

            if "；" in db_ans or ";" in db_ans:
                segs = re.split("[；;]", db_ans)
                report["multiSegmentFaq"].append({
                    "no": no,
                    "qaId": q["id"],
                    "segmentCount": len(segs),
                    "hasWav": has,
                    "firstSegment": segs[0][:60],
                    "keywords": entry["keywords"][:40],
                })

    # m4a 文件名与绑定 remark 是否一致（仅风险提示，无法听内容）
    for stem, expected_remark in M4A_TO_REMARK.items():
        qa = by_remark.get(expected_remark)
        if not qa:
            continue
        has = wav_ok(qa.get("answerWavPath"))
        if not has:
            report["riskyM4aBinding"].append({
                "m4a": stem,
                "expectedRemark": expected_remark,
                "issue": "映射条目仍缺 wav",
            })

    # 高关注：文件名语义与标准话术场景可能不符
    semantic_warnings = [
        ("fallback:19", "听不清", "标准答是重播开场白，不是「听不清」类应答"),
        ("fallback:21", "信号不好", "标准答「喂，您好，您这边能听见么？」，勿与 #42 听感确认混用"),
        ("fallback:42", "听得见吗", "标准答「在听的，您接着说」，需与 m4a 内容一致"),
        ("fallback:101", "静默/没声音", "多段话术（喂？/在听吗/再见），单条 m4a 可能只录一句"),
        ("fallback:97", "没听清", "多段 fallback，录音可能不完整"),
    ]
    report["semanticWarnings"] = [
        {"remark": r, "m4aHint": m, "note": n} for r, m, n in semantic_warnings
    ]

    mf = report["mainFlow"]
    fb = report["fallback"]
    report["summary"] = {
        "mainFlowTotal": len(mf["ok"]) + len(mf["textMismatch"]) + len(mf["missingWav"]) + len(mf["packMissing"]),
        "mainFlowOk": len(mf["ok"]),
        "mainFlowTextMismatch": len(mf["textMismatch"]),
        "mainFlowMissingWav": len(mf["missingWav"]),
        "fallbackTotal": len(fb["ok"]) + len(fb["textMismatch"]) + len(fb["missingWav"]) + len(fb["packMissing"]),
        "fallbackOk": len(fb["ok"]),
        "fallbackTextMismatch": len(fb["textMismatch"]),
        "fallbackMissingWav": len(fb["missingWav"]),
        "multiSegmentFaq": len(report["multiSegmentFaq"]),
    }

    OUT_JSON.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    s = report["summary"]
    print("=" * 60)
    print("主线 + 知识库：文本 vs 录音 对齐检查")
    print("=" * 60)
    print(f"主线 {s['mainFlowOk']}/{s['mainFlowTotal']} 文本一致且已有 wav")
    print(f"  文本不一致: {s['mainFlowTextMismatch']}")
    print(f"  文本一致但缺 wav: {s['mainFlowMissingWav']}")
    print(f"FAQ  {s['fallbackOk']}/{s['fallbackTotal']} 文本一致且已有 wav")
    print(f"  文本不一致: {s['fallbackTextMismatch']}")
    print(f"  文本一致但缺 wav: {s['fallbackMissingWav']}")
    print(f"  多段话术 FAQ: {s['multiSegmentFaq']} 条（单 wav 可能只覆盖首句）")
    print(f"\n详细报告: {OUT_JSON}")

    if mf["textMismatch"]:
        print("\n--- 主线：DB 与 loan-pack 文本不一致 ---")
        for x in mf["textMismatch"]:
            print(f"  [{x['step']}] {x['scene']} qa={x['qaId']} wav={'Y' if x['hasWav'] else 'N'}")
            print(f"    DB  : {x['dbPreview']}")
            print(f"    PACK: {x['packPreview']}")

    if mf["missingWav"]:
        print("\n--- 主线：仍缺录音 ---")
        for x in mf["missingWav"]:
            print(f"  [{x['step']}] {x['scene']} qa={x['qaId']}")

    if fb["textMismatch"]:
        print("\n--- FAQ：DB 与 loan-pack 文本不一致 ---")
        for x in fb["textMismatch"][:20]:
            print(f"  [{x['no']}] {x['keywords'][:36]} qa={x['qaId']} wav={'Y' if x['hasWav'] else 'N'}")
            print(f"    DB  : {x['dbPreview']}")
            print(f"    PACK: {x['packPreview']}")
        if len(fb["textMismatch"]) > 20:
            print(f"  ... 共 {len(fb['textMismatch'])} 条")

    if fb["missingWav"]:
        print("\n--- FAQ：仍缺录音 ---")
        for x in fb["missingWav"]:
            print(f"  [{x['no']}] {x['keywords'][:40]} qa={x['qaId']}")

    print("\n--- 语义风险（需人工听音核对 m4a 内容）---")
    for w in report["semanticWarnings"]:
        print(f"  {w['remark']} | 素材提示: {w['m4aHint']}")
        print(f"    {w['note']}")

    if report["multiSegmentFaq"]:
        print("\n--- 多段 FAQ（前 10）---")
        for x in report["multiSegmentFaq"][:10]:
            print(f"  [{x['no']}] {x['segmentCount']}段 wav={'Y' if x['hasWav'] else 'N'} | {x['firstSegment']}…")

    check_opening_and_extras(token, qas, flow_pack, report)
    return 0


def check_opening_and_extras(token: str, qas: list[dict], flow_pack: dict, report: dict) -> None:
    """外呼开场白（话术模板）与 flow:01、以及 DB 中 pack 未收录条目。"""
    req = urllib.request.Request(
        f"{BASE}/ai-prompt/list",
        headers={"Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        prompts = json.loads(resp.read())["data"]
    active = next((p for p in prompts if p.get("isActive") == 1), None)
    flow01 = next((q for q in qas if q.get("remark") == "flow:01"), None)
    pack01 = flow_pack.get("01", {}).get("script", "")

    print("\n--- 外呼开场白（接通首句）---")
    if active:
        opening_text = (active.get("openingText") or active.get("content") or "").strip()
        print(f"  话术模板 openingText 与 pack 01 一致: {norm(opening_text) == norm(pack01)}")
        print(f"  话术模板 opening wav: {'有' if wav_ok(active.get('openingWavPath')) else '无'}")
    if flow01:
        print(f"  主线 flow:01 wav: {'有' if wav_ok(flow01.get('answerWavPath')) else '无'}")
        print(f"  flow:01 与 pack 01 一致: {norm(flow01.get('standardAnswer', '')) == norm(pack01)}")

    extras = [q for q in qas if (q.get("remark") or "").startswith("flow:")
              and remark_key(q["remark"]) not in flow_pack]
    if extras:
        print("\n--- DB 有、loan-pack 无的主线条目（不影响 pack 对照）---")
        for q in extras:
            print(f"  {q.get('remark')} qa={q['id']} wav={'Y' if wav_ok(q.get('answerWavPath')) else 'N'}")
            print(f"    { (q.get('standardAnswer') or '')[:60] }…")

    # 相同标准答 → 共用录音（findByAnswerText 会命中首条）
    from collections import defaultdict
    ans_groups: dict[str, list[tuple]] = defaultdict(list)
    for q in qas:
        a = norm(q.get("standardAnswer") or "")
        if a:
            ans_groups[a].append((q["id"], q.get("remark"), (q.get("question") or "")[:28]))
    dups = [(a, v) for a, v in ans_groups.items() if len(v) > 1]
    if dups:
        print(f"\n--- 相同标准答（{len(dups)} 组，共用 wav 时 findByAnswerText 命中首条）---")
        for _, items in dups[:6]:
            print(f"  {items}")


if __name__ == "__main__":
    raise SystemExit(main())
