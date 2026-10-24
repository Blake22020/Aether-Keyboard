"""
Словари для ввода китайского пиньинем.

  python -I tools/zh/build_pinyin.py <папка с исходниками> app/src/main/assets/zh

Исходники:
  pinyin.txt         — mozillazg/pinyin-data (MIT): чтения иероглифов
  large_pinyin.txt   — mozillazg/phrase-pinyin-data (MIT): чтения слов
  zh_cn_50k.txt, zh_tw_50k.txt — hermitdave/FrequencyWords (MIT, частоты OpenSubtitles)
  TSCharacters.txt   — BYVoid/OpenCC (Apache 2.0): традиционные → упрощённые, чтобы найти чтение слова

Результат — zh_cn.txt и zh_tw.txt: «слово\\tчтение» по убыванию частоты, чтение без тонов, слоги через «'»
(ü пишется как v: «nv'er» — 女儿). У иероглифа основное чтение стоит на месте его частоты, остальные — в конце.
"""
import os
import sys
import unicodedata

LIMIT = 40000
TAB = "\t"


def toneless(s):
    s = unicodedata.normalize("NFD", s.lower()).replace("ü", "v")
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


def is_han(ch):
    o = ord(ch)
    return 0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF or 0xF900 <= o <= 0xFAFF


def load_chars(path):
    out = {}
    for line in open(path, encoding="utf8"):
        if line.startswith("#") or ":" not in line:
            continue
        code, rest = line.split(":", 1)
        ch = chr(int(code.strip()[2:], 16))
        readings = []
        for r in rest.split("#")[0].strip().split(","):
            t = toneless(r.strip())
            if t and t.isalpha() and t not in readings:
                readings.append(t)
        if readings:
            out[ch] = readings
    return out


def load_phrases(path):
    out = {}
    for line in open(path, encoding="utf8"):
        if line.startswith("#") or ":" not in line:
            continue
        word, rest = line.split(":", 1)
        syl = [toneless(x) for x in rest.split()]
        if len(syl) == len(word) and all(s.isalpha() for s in syl):
            out[word] = "'".join(syl)
    return out


def load_t2s(path):
    out = {}
    for line in open(path, encoding="utf8"):
        if line.startswith("#") or TAB not in line:
            continue
        t, s = line.strip().split(TAB, 1)
        out[t] = s.split()[0]
    return out


def build(src, chars, phrases, t2s, out_path):
    lines, seen_chars, rare = [], set(), []
    count = 0
    for line in open(src, encoding="utf8"):
        parts = line.split()
        if len(parts) < 2:
            continue
        w = parts[0]
        if not all(is_han(c) for c in w) or not all(c in chars for c in w):
            continue
        if len(w) == 1:
            if w in seen_chars:
                continue
            seen_chars.add(w)
            lines.append(w + TAB + chars[w][0])
            rare += [w + TAB + r for r in chars[w][1:]]
        else:
            simple = "".join(t2s.get(c, c) for c in w)
            reading = phrases.get(w) or phrases.get(simple) or "'".join(chars[c][0] for c in w)
            lines.append(w + TAB + reading)
        count += 1
        if count >= LIMIT:
            break
    # иероглифы из слов, которых нет в списке отдельно
    for line in list(lines):
        for c in line.split(TAB)[0]:
            if c not in seen_chars:
                seen_chars.add(c)
                lines.append(c + TAB + chars[c][0])
                rare += [c + TAB + r for r in chars[c][1:]]
    lines += rare
    with open(out_path, "w", encoding="utf8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(out_path, len(lines))


def main():
    dl, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    chars = load_chars(os.path.join(dl, "pinyin.txt"))
    phrases = load_phrases(os.path.join(dl, "large_pinyin.txt"))
    t2s = load_t2s(os.path.join(dl, "TSCharacters.txt"))
    build(os.path.join(dl, "zh_cn_50k.txt"), chars, phrases, t2s, os.path.join(out, "zh_cn.txt"))
    build(os.path.join(dl, "zh_tw_50k.txt"), chars, phrases, t2s, os.path.join(out, "zh_tw.txt"))


if __name__ == "__main__":
    main()
