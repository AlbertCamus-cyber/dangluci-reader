#!/usr/bin/env python3
"""从 ECDICT (MIT License, https://github.com/skywind3000/ECDICT) 生成 app 内置的精简词库。

用法: python3 tools/build_dict.py path/to/ecdict.csv app/src/main/assets/ecdict_brief.db
单词：只保留常用词（有词频/考试标签/柯林斯/牛津标记）或有词形还原信息的词。
词组：2–4 个词、每个词都在常用词里的词组（give up、in terms of）。
brief: 前 2 个词性行，每行前 2 个义项（卡片默认显示）；
full:  最多 6 个词性行的完整释义（「更多释义」展开时显示），与 brief 相同时留空。
[网络] 等方括号开头的释义丢弃。
表结构: dict(word TEXT PRIMARY KEY, phonetic TEXT, brief TEXT, lemma TEXT, full TEXT)
lemma: 该词是变形时的原形（来自 exchange 字段 0:），否则为空。
"""
import csv, os, re, sqlite3, sys

src, out = sys.argv[1], sys.argv[2]
csv.field_size_limit(10**9)
if os.path.exists(out):
    os.remove(out)
word_re = re.compile(r"^[a-z][a-z'\-]*$")
phrase_re = re.compile(r"^[a-z][a-z'\-]*( [a-z][a-z'\-]*){1,3}$")
pos_re = re.compile(r"^([a-z]+\.(?:\s*&\s*[a-z]+\.)?\s)(.*)$")

def clean_lines(r):
    return [l.strip() for l in r["translation"].replace("\\n", "\n").split("\n")
            if l.strip() and not l.strip().startswith("[")]

def brief_of(lines):
    out = []
    for l in lines[:2]:
        m = pos_re.match(l)
        pos, body = (m.group(1), m.group(2)) if m else ("", l)
        senses = [s.strip() for s in re.split("[,，；;]", body) if s.strip()][:2]
        out.append(pos + "，".join(senses))
    return "\n".join(out)

def full_of(lines, brief):
    f = "\n".join(l[:200] for l in lines[:6])
    return "" if f == brief else f

rows, phrases = {}, []
for r in csv.DictReader(open(src, encoding="utf-8")):
    w = r["word"].strip(); lw = w.lower()
    if len(lw) > 40:
        continue
    if phrase_re.match(lw):
        if w == lw:
            phrases.append(r)
        continue
    if not word_re.match(lw):
        continue
    lines = clean_lines(r)
    if not lines:
        continue
    lemma = ""
    for part in (r["exchange"] or "").split("/"):
        if part.startswith("0:"):
            lemma = part[2:].lower()
    if lemma == lw:
        lemma = ""
    common = (int(r["frq"] or 0) > 0 or int(r["bnc"] or 0) > 0 or r["tag"]
              or r["collins"] not in ("", "0") or r["oxford"] == "1")
    if not common and not lemma:
        continue
    if lw in rows and w != lw:
        continue
    b = brief_of(lines)
    rows[lw] = (lw, r["phonetic"], b, lemma, full_of(lines, b))

n_words = len(rows)
for r in phrases:
    lw = r["word"].strip()
    if lw in rows or not all(p in rows for p in lw.split(" ")):
        continue
    lines = clean_lines(r)
    if not lines:
        continue
    b = brief_of(lines)
    rows[lw] = (lw, r["phonetic"], b, "", full_of(lines, b))

db = sqlite3.connect(out)
db.execute("CREATE TABLE dict(word TEXT PRIMARY KEY, phonetic TEXT, brief TEXT, lemma TEXT, full TEXT) WITHOUT ROWID")
db.executemany("INSERT INTO dict VALUES(?,?,?,?,?)", rows.values())
db.commit(); db.execute("VACUUM"); db.close()
print(f"{n_words} words + {len(rows) - n_words} phrases -> {out} ({os.path.getsize(out)//1024} KB)")
