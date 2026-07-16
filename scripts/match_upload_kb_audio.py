#!/usr/bin/env python3
"""Match 录音文件/*.m4a to dialog_training_qa and upload via admin API."""
from __future__ import annotations

import json
import mimetypes
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_AUDIO_DIR = ROOT / "ai-call-server" / "录音文件"
BASE = "http://127.0.0.1:8081/api/admin"

# 录音文件-6-23：对照 kb_audio对照表.csv「需新录」条目（2025-06-23 补充批次）
MANUAL_6_23: dict[str, str] = {
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
    "非银行疑问": "fallback:63",
    "经营年限": "fallback:67",
    "担心": "fallback:69",
    "没时间": "fallback:75",
    "初次挽留": "fallback:83",
    "无房产证": "fallback:98",
    "婉拒": "fallback:104",
    "需要还嘛": "fallback:119",
    "非目标客群": "fallback:138",
    "公积金贷款": "fallback:43",
}

# 录音文件-6-24：听音核对重录 + 表格仍缺条目（2025-06-24）
MANUAL_6_24: dict[str, str] = {
    "申请": "fallback:137",  # 需要个贷产品（勿落到 fallback:107 申请条件）
    "外来号码": "fallback:63",  # 非银行来电疑问
    "贷款所需材料": "fallback:126",
    "听不清": "fallback:19",
    "听得到吗": "fallback:42",
    "信号不好": "fallback:21",
    "没听清": "fallback:97",
    "静默": "fallback:101",
    "质疑高利贷": "fallback:141",
    "初次拒绝": "fallback:83",
    "政策收紧": "fallback:90",
    "利息偏高": "fallback:31",
    "提前还款": "fallback:86",
    "还款方式": "fallback:66",
    "质疑套路贷": "fallback:87",
    "面积小": "fallback:78",
    "上不上征信": "fallback:2",
    "号码来源": "fallback:38",
    "追问": "fallback:136",
}
# 与 fallback:101 重复，仅保留「静默.m4a」上传；「没声」为同场景备用素材
MANUAL_6_24_SKIP: frozenset[str] = frozenset({"没声"})

# 文件名与知识库不完全一致时的手工映射（stem -> qa id 或 remark）
MANUAL_BY_STEM: dict[str, str] = {
    "开场白": "prompt:opening",
    "加微": "flow:22",
    "加微成功": "flow:24",
    "挽回1": "flow:27",
    "挽回2": "flow:28",
    "挽回3": "flow:29",
    "拒绝1": "flow:27",
    "拒绝2": "flow:28",
    "拒绝3": "flow:29",
    "邀约成功": "flow:19",
    "邀约失败": "flow:21",
    "异常挂断": "flow:99",
    "异常": "flow:99",
    "意向不明": "flow:20",
    "利息1": "fallback:8",
    "利息2": "fallback:31",
    "上不上征信2": "fallback:125",
    "怎么还款2": "fallback:66",
    "怎么操作2": "fallback:64",
    "没匹配1": "fallback:101",
    "没匹配2": "fallback:133",
    "没声音3": "fallback:101",
    "黑户1": "fallback:93",
    "黑户2": "fallback:93",
    "年龄大！小": "fallback:61",
    "超出1": "fallback:61",
    "超出2": "fallback:61",
    "超出3": "fallback:61",
    "年限1": "fallback:91",
    "年限2": "fallback:91",
    "房子类型1": "fallback:78",
    "房子类型2": "fallback:78",
    "材料1": "fallback:126",
    "不需要1": "fallback:70",
    "忙，资金不急2": "fallback:71",
    "号码哪来的？": "fallback:38",
    "中介？银行？哪个银行？": "fallback:18",
    "个人？企业？": "fallback:7",
    "了解过….": "fallback:33",
    "了解过…": "fallback:33",
    "网贷？": "fallback:87",
    "押证？": "fallback:81",
    "喂？": "fallback:42",
    "喂？在听吗？": "fallback:101",
    "什么位置": "fallback:16",
    "位置": "fallback:16",
    "名称": "fallback:27",
    "姓名": "flow:17",
    "怎么称呼": "flow:17",
    "微信号": "flow:23",
    "工号": "fallback:59",
    "经理联系": "flow:18",
    "银行签约": "fallback:88",
    "哪里放款": "fallback:45",
    "去哪里贷": "fallback:36",
    "怎么贷": "fallback:17",
    "信用贷怎么贷": "fallback:23",
    "贷款类型": "fallback:11",
    "哪些方案": "fallback:25",
    "先做产品": "fallback:120",
    "先说产品": "fallback:120",
    "说重点": "fallback:120",
    "问太多": "fallback:136",
    "问题太多": "fallback:136",
    "老打我电话": "fallback:111",
    "号码标记": "fallback:39",
    "号码外地": "fallback:39",
    "不在xx城市": "fallback:68",
    "上班没时间": "fallback:47",
    "在忙": "fallback:47",
    "忙": "fallback:47",
    "忙，资金不急": "fallback:90",
    "开车": "fallback:46",
    "生病": "fallback:105",
    "考虑": "fallback:112",
    "以后再说": "fallback:13",
    "申请不用": "fallback:106",
    "自己办": "fallback:129",
    "自己去银行": "fallback:115",
    "自己有渠道": "fallback:116",
    "办过了": "fallback:33",
    "办不下来": "fallback:35",
    "能不能办下来": "fallback:113",
    "能贷": "fallback:113",
    "审批通过率": "fallback:54",
    "担心前期费用": "fallback:82",
    "是不是高利贷": "fallback:141",
    "高利贷": "fallback:141",
    "套路贷": "fallback:87",
    "安全": "fallback:53",
    "风险安全": "fallback:140",
    "隐私": "fallback:123",
    "侵犯隐私": "fallback:20",
    "不方便透露": "fallback:6",
    "敏感部门": "fallback:29",
    "机器人": "fallback:95",
    "同行": "fallback:41",
    "捣乱": "fallback:85",
    "脏话": "fallback:114",
    "调戏": "fallback:121",
    "赞美": "fallback:132",
    "你说什么": "fallback:97",
    "听得见吗": "fallback:42",
    "信号不好": "fallback:21",
    "打断": "fallback:109",
    "案件": "fallback:96",
    "当逾": "fallback:93",
    "大数据乱": "fallback:50",
    "负债高": "fallback:122",
    "转贷": "fallback:135",
    "降息": "fallback:135",
    "急需": "fallback:130",
    "资金量大": "fallback:131",
    "最低申请多少": "fallback:89",
    "额度": "fallback:139",
    "期数": "fallback:124",
    "还款期限": "fallback:124",
    "还款方式": "fallback:66",
    "怎么还款": "fallback:66",
    "提前还款": "fallback:86",
    "违约金": "fallback:86",
    "月息年息": "fallback:92",
    "年化": "fallback:60",
    "利息高": "fallback:31",
    "利息低": "fallback:8",
    "了解利率": "fallback:8",
    "手续费贵": "fallback:128",
    "服务费": "fallback:94",
    "上不上征信": "fallback:2",
    "查征信": "fallback:125",
    "先息后本": "fallback:24",
    "要不要抵押": "fallback:118",
    "抵押贷款": "fallback:11",
    "抵押材料": "fallback:79",
    "押证？": "fallback:81",
    "车抵": "fallback:134",
    "车": "flow:12",
    "房": "flow:13",
    "房子贷款": "flow:13",
    "房龄超了": "fallback:78",
    "面积小": "fallback:78",
    "外地房": "fallback:49",
    "回迁可以吗": "fallback:44",
    "自建房": "fallback:72",
    "父母房子": "fallback:103",
    "个人名下": "fallback:103",
    "产证共有": "fallback:10",
    "按揭尾款": "fallback:73",
    "做过一押": "fallback:1",
    "单签": "fallback:34",
    "担保人": "fallback:77",
    "家人帮忙贷": "fallback:56",
    "家里人商量": "fallback:57",
    "找人周转": "fallback:52",
    "备用金": "fallback:48",
    "拿什么干嘛": "fallback:84",
    "拿钱干嘛": "fallback:84",
    "有营执不做了": "fallback:74",
    "没营执": "fallback:76",
    "没流水": "fallback:102",
    "没工作": "fallback:99",
    "没有公积金": "fallback:28",
    "公积金": "flow:04",
    "询问公积金和社保": "flow:04",
    "社保缴纳时间": "flow:05",
    "询问开票交税": "flow:08",
    "询问流水": "flow:11",
    "询问金额": "flow:02",
    "询问最近贷款情况": "flow:15",
    "开公司还是上班": "flow:03",
    "什么产品": "fallback:11",
    "什么都没有": "fallback:12",
    "需要什么材料": "fallback:126",
    "手续": "fallback:127",
    "手续麻烦": "fallback:80",
    "麻烦": "fallback:142",
    "怎么办理": "fallback:64",
    "办理多久": "fallback:108",
    "申请多久": "fallback:107",
    "申请条件": "fallback:107",
    "多久下款": "fallback:15",
    "多久放款": "fallback:32",
    "线上线下": "fallback:110",
    "只做银行吗": "fallback:37",
    "合作银行": "fallback:40",
    "哪家银行": "fallback:18",
    "号码哪来的": "fallback:38",
    "装修贷": "fallback:117",
    "信用卡": "fallback:22",
    "没有抵押物": "fallback:100",
    "没时间周末": "fallback:58",
    "上班时间": "fallback:3",
}


def norm(s: str) -> str:
    s = s.strip()
    for ch in "？?！!…，,。.;；：:（）()【】[]\"' ":
        s = s.replace(ch, "")
    return s.lower()


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


def api_get(token: str, path: str) -> Any:
    req = urllib.request.Request(f"{BASE}{path}", headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read())
    if data.get("code") != 200:
        raise RuntimeError(data.get("message") or f"GET {path} failed")
    return data["data"]


def upload_multipart(token: str, path: str, file_path: Path) -> Any:
    boundary = "----AiCallBulkUpload7MA4YWxk"
    mime = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
    content = file_path.read_bytes()
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{file_path.name}"\r\n'
        f"Content-Type: {mime}\r\n\r\n"
    ).encode("utf-8") + content + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        f"{BASE}{path}",
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read())
    if data.get("code") != 200:
        raise RuntimeError(data.get("message") or f"upload {path} failed")
    return data["data"]


def parse_qa(qa: dict[str, Any]) -> dict[str, Any]:
    remark = (qa.get("remark") or "").strip()
    question = qa.get("question") or ""
    scene = ""
    m = re.match(r"\[主线[^\]]+\](.+)", question)
    if m:
        scene = m.group(1).strip()
    label = question
    if "." in question and remark.startswith("fallback:"):
        label = question.split(".", 1)[1].strip()
    keywords: list[str] = []
    if remark.startswith("fallback:"):
        keywords = [k.strip() for k in label.split("|") if k.strip()]
    return {
        "id": qa["id"],
        "remark": remark,
        "scene": scene,
        "label": label,
        "keywords": keywords,
        "has_audio": bool(qa.get("answerWavPath")),
    }


def build_indexes(qas: list[dict[str, Any]]) -> tuple[dict[str, dict], dict[str, dict]]:
    by_remark: dict[str, dict] = {}
    by_id: dict[int, dict] = {}
    for qa in qas:
        meta = parse_qa(qa)
        by_id[meta["id"]] = meta
        if meta["remark"]:
            by_remark[meta["remark"]] = meta
    return by_remark, by_id


def score_match(stem: str, meta: dict[str, Any]) -> int:
    ns = norm(stem)
    if meta["scene"] and norm(meta["scene"]) == ns:
        return 100
    if meta["label"] and norm(meta["label"]) == ns:
        return 95
    for kw in meta["keywords"]:
        nk = norm(kw)
        if nk == ns:
            return 90
        if ns in nk or nk in ns:
            return 70
    if meta["scene"] and (ns in norm(meta["scene"]) or norm(meta["scene"]) in ns):
        return 60
    if meta["label"] and (ns in norm(meta["label"]) or norm(meta["label"]) in ns):
        return 55
    return 0


def match_file(stem: str, by_remark: dict[str, dict], metas: list[dict],
                 extra_manual: dict[str, str] | None = None) -> tuple[str, dict | None, int]:
    manual = {**MANUAL_BY_STEM, **(extra_manual or {})}
    if stem in manual:
        key = manual[stem]
        if key == "prompt:opening":
            return key, None, 100
        if key in by_remark:
            return key, by_remark[key], 100
    best: tuple[dict | None, int, str] = (None, 0, "")
    for meta in metas:
        sc = score_match(stem, meta)
        if sc > best[1]:
            best = (meta, sc, meta["remark"] or f"id:{meta['id']}")
    if best[1] >= 55:
        return best[2], best[0], best[1]
    return "", None, 0


def main() -> int:
    dry_run = "--dry-run" in sys.argv
    force = "--force" in sys.argv
    audio_dir = DEFAULT_AUDIO_DIR
    extra_manual: dict[str, str] | None = None
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--dir" and i + 1 < len(args):
            rel = args[i + 1]
            audio_dir = ROOT / rel if not Path(rel).is_absolute() else Path(rel)
            i += 2
            continue
        if args[i].startswith("--dir="):
            rel = args[i].split("=", 1)[1]
            audio_dir = ROOT / rel if not Path(rel).is_absolute() else Path(rel)
        i += 1
    if "6-23" in audio_dir.name or audio_dir.name == "录音文件-6-23":
        extra_manual = MANUAL_6_23
    elif "6-24" in audio_dir.name or audio_dir.name == "录音文件-6-24":
        extra_manual = MANUAL_6_24
        if not force and "--no-force" not in sys.argv:
            force = True
            print("6-24 补录批次：默认 --force 覆盖已有 wav")
    if not audio_dir.is_dir():
        print(f"目录不存在: {audio_dir}")
        return 1

    print(f"音频目录: {audio_dir}")

    token = login()
    qas = api_get(token, "/dialog-training/list")
    prompts = api_get(token, "/ai-prompt/list")
    active_prompt = next((p for p in prompts if p.get("isActive") == 1), prompts[0] if prompts else None)

    by_remark, _ = build_indexes(qas)
    metas = [parse_qa(q) for q in qas]

    files = sorted(audio_dir.glob("*"))
    matched: list[tuple[str, str, int, Path]] = []
    unmatched: list[str] = []
    skipped: list[str] = []
    seen_qa: set[int] = set()

    for fp in files:
        if not fp.is_file():
            continue
        stem = fp.stem
        if audio_dir.name == "录音文件-6-24" and stem in MANUAL_6_24_SKIP:
            skipped.append(f"{stem} (与静默同 QA，跳过)")
            continue
        key, meta, score = match_file(stem, by_remark, metas, extra_manual)
        if not key and score == 0:
            unmatched.append(stem)
            continue
        if key == "prompt:opening":
            matched.append((stem, f"ai-prompt:{active_prompt['id']}:opening", score, fp))
            continue
        if meta:
            if meta["id"] in seen_qa:
                if not force:
                    skipped.append(f"{stem} -> qa:{meta['id']} (同 QA 已有更优匹配，跳过)")
                    continue
                skipped.append(f"{stem} -> qa:{meta['id']} (覆盖同 QA 前序文件)")
            seen_qa.add(meta["id"])
            matched.append((stem, f"qa:{meta['id']} ({meta['remark']})", score, fp))
        else:
            unmatched.append(stem)

    print(f"录音文件 {len(files)} 个，匹配 {len(matched)} 个，未匹配 {len(unmatched)} 个")
    if unmatched:
        print("\n未匹配:")
        for u in unmatched:
            print(f"  - {u}")

    uploaded = 0
    for stem, target, score, fp in matched:
        if target.startswith("ai-prompt:"):
            pid = active_prompt["id"] if active_prompt else None
            if not pid:
                print(f"[跳过] {stem} 无启用话术模板")
                continue
            path = f"/ai-prompt/{pid}/upload-opening-audio"
            label = "开场白→话术模板"
        else:
            qa_id = int(re.search(r"qa:(\d+)", target).group(1))
            qa = next(q for q in qas if q["id"] == qa_id)
            if qa.get("answerWavPath") and not force:
                skipped.append(f"{stem} -> {target} (已有录音)")
                continue
            path = f"/dialog-training/{qa_id}/upload-audio"
            label = target

        print(f"{'[DRY]' if dry_run else '[上传]'} {stem} -> {label} (score={score})")
        if dry_run:
            continue
        try:
            upload_multipart(token, path, fp)
            uploaded += 1
        except Exception as e:
            print(f"  失败: {e}")

    if skipped:
        print(f"\n已跳过 {len(skipped)} 个（已有录音，加 --force 可覆盖）:")
        for s in skipped[:20]:
            print(f"  {s}")
        if len(skipped) > 20:
            print(f"  ... 共 {len(skipped)} 个")

    if not dry_run:
        print(f"\n完成：成功上传 {uploaded} 个")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
