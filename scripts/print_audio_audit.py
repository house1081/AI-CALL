#!/usr/bin/env python3
"""生成主线+FAQ 文本/录音对照清单（m4a 素材 vs 知识库 vs 上传记录）。"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK = json.loads(
    (ROOT / "ai-call-server/src/main/resources/dialog-scripts/loan-pack.json").read_text(encoding="utf-8")
)
UPLOAD_LOG = (ROOT / "scripts/_upload_result.txt").read_text(encoding="utf-8")
MISSING = json.loads((ROOT / "scripts/_missing_audio.json").read_text(encoding="utf-8"))
AUDIO_DIR = ROOT / "ai-call-server" / "录音文件"

# parse upload log: stem -> {qaId, type, key}
upload_by_key = {"flow": {}, "fallback": {}}
upload_stems = set()
for line in UPLOAD_LOG.splitlines():
    m = re.match(r"\[上传\] (.+?) -> qa:(\d+) \((flow|fallback):([^)]+)\)", line)
    if m:
        stem, qa_id, typ, key = m.group(1), int(m.group(2)), m.group(3), m.group(4)
        upload_stems.add(stem)
        upload_by_key[typ].setdefault(key, []).append({"stem": stem, "qaId": qa_id})

m4a_all = {p.stem for p in AUDIO_DIR.glob("*.m4a")}
m4a_unused = sorted(m4a_all - upload_stems)

flow_pack = {x["step"]: x for x in PACK["mainFlow"]}
fb_pack = {str(x["no"]): x for x in PACK["fallbacks"]}

print("=" * 60)
print("一、总体情况")
print("=" * 60)
print(f"  主线步骤（loan-pack）     : {len(flow_pack)} 步")
print(f"  FAQ 兜底（loan-pack）     : {len(fb_pack)} 条")
print(f"  本地 m4a 素材             : {len(m4a_all)} 个")
print(f"  已匹配并上传（日志记录）  : {len(upload_stems)} 个")
print(f"  未使用的 m4a              : {len(m4a_unused)} 个")
print(f"  _missing_audio 缺 wav 条目: {len(MISSING)} 条（DB 快照，可能需重新上传）")
print()

print("=" * 60)
print("二、主线流程对照（step | 场景 | 文本摘要 | 录音状态 | 建议）")
print("=" * 60)
for step in sorted(flow_pack.keys(), key=lambda s: (len(s), s)):
    p = flow_pack[step]
    up = upload_by_key["flow"].get(step, [])
    if up:
        stems = ", ".join(u["stem"] for u in up)
        action = f"[有素材] 可上传/已传 ({stems})"
    else:
        action = "[缺录音] 需新录（无对应 m4a）"
    txt = p["script"][:45].replace("\n", "")
    print(f"  {step:>3} | {p['scene']:<14} | {txt}… | {action}")

need_record_flow = [s for s in flow_pack if s not in upload_by_key["flow"]]
print()
print(f"  → 主线需新录: {len(need_record_flow)} 步 → {', '.join(need_record_flow)}")
print()

print("=" * 60)
print("三、FAQ 知识库对照（按录音状态分组）")
print("=" * 60)

has_m4a = []
no_m4a = []
for no in sorted(fb_pack.keys(), key=int):
    p = fb_pack[no]
    up = upload_by_key["fallback"].get(no, [])
    if up:
        has_m4a.append((no, p, up))
    else:
        no_m4a.append((no, p))

print(f"\n  【A】有 m4a 可上传/已上传：{len(has_m4a)} 条")
print(f"  【B】无 m4a 需新录或找素材：{len(no_m4a)} 条")
print()

print("  --- B 类：无本地 m4a（需新录）---")
for no, p in no_m4a:
    kw = p["keywords"][:32]
    ans = p["answer"][:40]
    print(f"  [{no:>3}] {kw:<34} | {ans}…")

print()
print("  --- A 类：有 m4a（建议批量上传绑定）前 20 条示例 ---")
for no, p, up in has_m4a[:20]:
    stems = ", ".join(u["stem"] for u in up)
    print(f"  [{no:>3}] {p['keywords'][:28]:<28} ← {stems}")

print()
print("=" * 60)
print("四、需重点核对的「文本 vs 录音」差异")
print("=" * 60)
warnings = [
    ("42", "听得到吗", "听得见吗.m4a / 喂？.m4a", "标准答已改为「在听的，您接着说」，需确认 m4a 内容是否一致，不一致则重录"),
    ("19", "听不清|重复一遍", "无专用 m4a", "标准答是重播开场白，不是「听不清」应答，建议单独录一条"),
    ("21", "信号不好", "信号不好.m4a", "标准答「喂，您好，您这边能听见么？」与听感确认 #42 不同，勿混用"),
    ("101", "静默/没声音", "喂？在听吗？.m4a / 没匹配1/2", "多段话术用分号分隔，一个 m4a 可能只覆盖其中一句"),
    ("06/07/09/10/14", "主线企业/保单", "无 m4a", "企业贷主线 5 句必须新录，否则智能预录会跳步/静音"),
    ("25/26/AA", "加微/转接", "无 m4a", "结束/转接话术需新录"),
]
for item in warnings:
    print(f"  • [{item[0]}] {item[1]}")
    print(f"      素材: {item[2]}")
    print(f"      说明: {item[3]}")
    print()

print("=" * 60)
print("五、操作建议（优先级）")
print("=" * 60)
print("  P0  运行 scripts/match_upload_kb_audio.py 批量上传 188 个 m4a → 知识库 wav")
print("  P0  新录主线 8 步: 06,07,09,10,14,25,26,AA")
print("  P1  新录 FAQ B 类", len(no_m4a), "条（或从话术包精简合并）")
print("  P1  核对 #42 听感确认、#19 听不清 录音与文本一致")
print("  P2  管理端「训练知识库」→ 重建 RAG 索引")
print()
if m4a_unused:
    print("  未映射 m4a（可能重复/备用）:", ", ".join(m4a_unused[:15]), ("…" if len(m4a_unused)>15 else ""))
