#!/usr/bin/env python3
"""对照主线/知识库文本与录音覆盖，输出缺录音与可上传清单。"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DUMP = json.loads((ROOT / "scripts/_kb_qa_dump.json").read_text(encoding="utf-8"))
PACK = json.loads(
    (ROOT / "ai-call-server/src/main/resources/dialog-scripts/loan-pack.json").read_text(encoding="utf-8")
)
AUDIO_DIR = ROOT / "ai-call-server" / "录音文件"
OUT = ROOT / "scripts/_audio_audit_report.json"

# 来自 match_upload_kb_audio.py 的主线 m4a 映射
FLOW_M4A = {
    "开场白": "01",
    "询问金额": "02",
    "开公司还是上班": "03",
    "公积金": "04",
    "询问公积金和社保": "04",
    "社保缴纳时间": "05",
    "询问开票交税": "08",
    "询问流水": "11",
    "车": "12",
    "房": "13",
    "询问最近贷款情况": "15",
    "姓名": "17",
    "怎么称呼": "17",
    "经理联系": "18",
    "邀约成功": "19",
    "意向不明": "20",
    "邀约失败": "21",
    "加微": "22",
    "微信号": "23",
    "加微成功": "24",
    "挽回1": "27",
    "挽回2": "28",
    "挽回3": "29",
    "异常挂断": "99",
    "异常": "99",
}

# fallback no -> 代表性 m4a（有则可直接上传）
FALLBACK_M4A_HINT = {
    "1": "做过一押", "2": "上不上征信", "8": "利息1", "42": "听得见吗",
    "101": "喂？在听吗？", "141": "是不是高利贷", "142": "麻烦",
}


def wav_ok(path: str | None) -> bool:
    if not path:
        return False
    p = Path(path.replace("\\", "/").replace("./", ""))
    if not p.is_absolute():
        p = ROOT / "ai-call-server" / p
    return p.exists() and p.stat().st_size > 44


def extract_key(remark: str | None) -> str | None:
    if not remark:
        return None
    m = re.search(r":(\w+)$", remark)
    return m.group(1) if m else None


def main():
    m4a_stems = {p.stem for p in AUDIO_DIR.glob("*.m4a")}
    flow_pack = {x["step"]: x for x in PACK["mainFlow"]}
    fallback_pack = {str(x["no"]): x for x in PACK["fallbacks"]}

    rows = [r for r in DUMP if r.get("kbId") == 1 and r.get("status") == 1]
    flow_rows = [r for r in rows if (r.get("remark") or "").startswith("flow:")]
    fb_rows = [r for r in rows if (r.get("remark") or "").startswith("fallback:")]

    report = {"mainFlow": [], "fallback": [], "summary": {}}

    for r in sorted(flow_rows, key=lambda x: x.get("remark", "")):
        step = extract_key(r["remark"])
        pack = flow_pack.get(step or "", {})
        has = wav_ok(r.get("answerWavPath"))
        m4a_hint = [s for s, st in FLOW_M4A.items() if st == step and s in m4a_stems]
        action = "OK"
        if not has:
            action = "上传m4a" if m4a_hint else "需新录"
        report["mainFlow"].append({
            "step": step,
            "scene": pack.get("scene", ""),
            "qaId": r["id"],
            "hasWav": has,
            "m4aAvailable": m4a_hint,
            "action": action,
            "scriptPreview": (r.get("standardAnswer") or "")[:80],
            "packScriptPreview": (pack.get("script") or "")[:80],
            "textMatch": (r.get("standardAnswer") or "").strip() == (pack.get("script") or "").strip(),
        })

    for r in sorted(
        fb_rows,
        key=lambda x: int(re.search(r"fallback:(\d+)", x.get("remark", "")).group(1))
        if re.search(r"fallback:(\d+)", x.get("remark", ""))
        else 0,
    ):
        no = extract_key(r["remark"])
        pack = fallback_pack.get(no or "", {})
        has = wav_ok(r.get("answerWavPath"))
        hint = FALLBACK_M4A_HINT.get(no or "")
        m4a_ok = hint in m4a_stems if hint else False
        action = "OK"
        if not has:
            action = "上传m4a" if m4a_ok else "需新录/找素材"
        report["fallback"].append({
            "no": no,
            "keywords": pack.get("keywords", ""),
            "qaId": r["id"],
            "hasWav": has,
            "m4aHint": hint if m4a_ok else None,
            "action": action,
            "answerPreview": (r.get("standardAnswer") or "")[:60],
        })

    mf = report["mainFlow"]
    fb = report["fallback"]
    report["summary"] = {
        "mainFlowTotal": len(mf),
        "mainFlowHasWav": sum(1 for x in mf if x["hasWav"]),
        "mainFlowUploadM4a": sum(1 for x in mf if x["action"] == "上传m4a"),
        "mainFlowNeedRecord": sum(1 for x in mf if x["action"] == "需新录"),
        "fallbackTotal": len(fb),
        "fallbackHasWav": sum(1 for x in fb if x["hasWav"]),
        "fallbackUploadM4a": sum(1 for x in fb if x["action"] == "上传m4a"),
        "fallbackNeedRecord": sum(1 for x in fb if x["action"] == "需新录/找素材"),
        "m4aFileCount": len(m4a_stems),
    }

    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    s = report["summary"]
    print("=== 录音覆盖审计 (kb=1, 数据源: _kb_qa_dump.json) ===")
    print(f"主线: {s['mainFlowHasWav']}/{s['mainFlowTotal']} 已有wav | "
          f"{s['mainFlowUploadM4a']} 可上传m4a | {s['mainFlowNeedRecord']} 需新录")
    print(f"FAQ:  {s['fallbackHasWav']}/{s['fallbackTotal']} 已有wav | "
          f"{s['fallbackUploadM4a']} 可上传m4a | {s['fallbackNeedRecord']} 需新录")
    print(f"本地m4a: {s['m4aFileCount']} 个")
    print(f"报告: {OUT}")
    print()
    print("--- 主线：需处理 ---")
    for x in mf:
        if x["action"] != "OK":
            print(f"  [{x['step']}] {x['scene']} | {x['action']} | m4a={x['m4aAvailable']} | qaId={x['qaId']}")
    print()
    print("--- FAQ：缺录音 (前40) ---")
    n = 0
    for x in fb:
        if x["action"] != "OK":
            print(f"  [{x['no']}] {x['keywords'][:36]} | {x['action']} | qaId={x['qaId']}")
            n += 1
            if n >= 40:
                rest = s["fallbackNeedRecord"] + s["fallbackUploadM4a"] - 40
                if rest > 0:
                    print(f"  ... 其余 {rest} 条见 {OUT}")
                break


if __name__ == "__main__":
    main()
