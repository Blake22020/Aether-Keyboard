"""
Собирает каталог эмодзи и встроенные стили эмодзи для клавиатуры.

  python -I tools/emoji/build_emoji.py <папка с исходниками> app/src/main/assets/emoji

Исходники (скачиваются заранее, см. tools/emoji/README.md):
  emoji-test.txt                       — Unicode 16.0, полный список и порядок
  ann_en.json, ann_ru.json             — CLDR annotations (имена и ключевые слова)
  der_en.json, der_ru.json             — CLDR annotationsDerived (тона кожи, флаги, семьи)
  ds_emoji.json                        — emoji-datasource 15.1.2 (координаты в листах)
  ds-twitter_64.png, ds-google_64.png  — листы Twemoji (CC-BY 4.0) и Noto (Apache 2.0)
  openmoji72.zip                       — OpenMoji 15.1 72x72 (CC BY-SA 4.0)
  blob_png.zip                         — Blobmoji 15.0 PNG (Apache 2.0)

Результат:
  emoji.txt — группы и эмодзи: «#группа», затем «эмодзи\tварианты тона\tимя en\tимя ru\tключевые слова»;
  <стиль>/<n>.webp — листы 16×16 клеток по CELL px; порядковый номер эмодзи — его номер в emoji.txt
  (сначала основной вариант, потом варианты тона), клетка = номер % 256, лист = номер // 256.
"""
import io
import json
import os
import sys
import zipfile

from PIL import Image

CELL = 64
GRID = 16
PER_SHEET = GRID * GRID
SKIN = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}
GROUPS = {
    "Smileys & Emotion": "smileys", "People & Body": "people", "Animals & Nature": "nature",
    "Food & Drink": "food", "Travel & Places": "travel", "Activities": "activities",
    "Objects": "objects", "Symbols": "symbols", "Flags": "flags",
}


def key(cps):
    """Ключ без FE0F — так совпадают имена файлов, CLDR и emoji-datasource."""
    return "-".join("%X" % c for c in cps if c != 0xFE0F)


def parse_test(path):
    groups, group = [], None
    for line in open(path, encoding="utf8"):
        line = line.rstrip("\n")
        if line.startswith("# group:"):
            name = line.split(":", 1)[1].strip()
            group = GROUPS.get(name)
            if group:
                groups.append((group, []))
            continue
        if not line or line.startswith("#") or group is None:
            continue
        code, rest = line.split(";", 1)
        if "fully-qualified" not in rest.split("#")[0]:
            continue
        cps = [int(x, 16) for x in code.split()]
        groups[-1][1].append(cps)
    return groups


def load_names(dl, lang):
    out = {}
    for fname, root in ((f"ann_{lang}.json", "annotations"), (f"der_{lang}.json", "annotationsDerived")):
        data = json.load(open(os.path.join(dl, fname), encoding="utf8"))[root]["annotations"]
        for e, v in data.items():
            out[key([ord(c) for c in e])] = (v.get("tts", [""])[0], v.get("default", []))
    return out


def clean(s):
    return s.replace("\t", " ").replace("\n", " ").strip()


def build_catalog(dl, out_dir):
    groups = parse_test(os.path.join(dl, "emoji-test.txt"))
    en, ru = load_names(dl, "en"), load_names(dl, "ru")
    order = []  # плоский список кодов в порядке номеров
    lines = []
    for gid, items in groups:
        lines.append("#" + gid)
        bases = {}
        seq = []
        for cps in items:
            base = [c for c in cps if c not in SKIN]
            k = key(base)
            if len(base) != len(cps) and k in bases:
                bases[k][1].append(cps)
            else:
                bases[k] = (cps, [])
                seq.append(k)
        for k in seq:
            cps, variants = bases[k]
            name_en, kw_en = en.get(key(cps), ("", []))
            name_ru, kw_ru = ru.get(key(cps), ("", []))
            words = []
            for w in kw_en + kw_ru:
                w = clean(w).lower()
                if w and w not in words:
                    words.append(w)
            emoji = "".join(map(chr, cps))
            var = " ".join("".join(map(chr, v)) for v in variants)
            lines.append("\t".join([emoji, var, clean(name_en), clean(name_ru), " | ".join(words)]))
            order.append(cps)
            order.extend(variants)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "emoji.txt"), "w", encoding="utf8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("emoji:", len(order), "base:", sum(1 for l in lines if not l.startswith("#")))
    return order


def fit(im):
    im = im.convert("RGBA")
    w, h = im.size
    side = max(w, h)
    if w != h:
        sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        sq.paste(im, ((side - w) // 2, (side - h) // 2))
        im = sq
    return im.resize((CELL, CELL), Image.LANCZOS)


def datasource_source(dl, sheet, flag):
    data = json.load(open(os.path.join(dl, "ds_emoji.json"), encoding="utf8"))
    pos = {}
    for e in data:
        if e.get(flag):
            pos[key(int(x, 16) for x in e["unified"].split("-"))] = (e["sheet_x"], e["sheet_y"])
        for v in (e.get("skin_variations") or {}).values():
            if v.get(flag):
                pos[key(int(x, 16) for x in v["unified"].split("-"))] = (v["sheet_x"], v["sheet_y"])
    img = Image.open(os.path.join(dl, sheet)).convert("RGBA")
    step = 66  # 64 px + по 1 px поля

    def get(k):
        p = pos.get(k)
        if p is None:
            return None
        x, y = p
        return img.crop((x * step + 1, y * step + 1, x * step + 65, y * step + 65))
    return get


def zip_source(dl, name, to_name):
    z = zipfile.ZipFile(os.path.join(dl, name))
    files = {}
    for n in z.namelist():
        k = to_name(n)
        if k:
            files[k] = n

    def get(k):
        n = files.get(k)
        return Image.open(io.BytesIO(z.read(n))) if n else None
    return get


def openmoji_name(n):
    if not n.endswith(".png") or "/" in n:
        return None
    try:
        return key(int(x, 16) for x in n[:-4].split("-"))
    except ValueError:
        return None


def blob_name(n):
    b = os.path.basename(n)
    if not b.startswith("emoji_u") or not b.endswith(".png"):
        return None
    try:
        return key(int(x, 16) for x in b[7:-4].split("_"))
    except ValueError:
        return None


def build_style(order, get, out_dir, style):
    d = os.path.join(out_dir, style)
    os.makedirs(d, exist_ok=True)
    found = 0
    sheets = (len(order) + PER_SHEET - 1) // PER_SHEET
    for s in range(sheets):
        sheet = Image.new("RGBA", (GRID * CELL, GRID * CELL), (0, 0, 0, 0))
        for i in range(PER_SHEET):
            n = s * PER_SHEET + i
            if n >= len(order):
                break
            im = get(key(order[n]))
            if im is None:
                continue
            found += 1
            sheet.paste(fit(im), ((i % GRID) * CELL, (i // GRID) * CELL))
        sheet.save(os.path.join(d, f"{s}.webp"), "WEBP", quality=80, method=6)
    # какие номера есть в стиле: остальные клавиатура рисует системным шрифтом
    have = "".join("1" if get(key(c)) is not None else "0" for c in order)
    with open(os.path.join(d, "index.txt"), "w") as f:
        f.write(have)
    print(style, found, "/", len(order))


def main():
    dl, out_dir = sys.argv[1], sys.argv[2]
    order = build_catalog(dl, out_dir)
    build_style(order, datasource_source(dl, "ds-twitter_64.png", "has_img_twitter"), out_dir, "twemoji")
    build_style(order, datasource_source(dl, "ds-google_64.png", "has_img_google"), out_dir, "noto")
    build_style(order, zip_source(dl, "openmoji72.zip", openmoji_name), out_dir, "openmoji")
    build_style(order, zip_source(dl, "blob_png.zip", blob_name), out_dir, "blob")


if __name__ == "__main__":
    main()
