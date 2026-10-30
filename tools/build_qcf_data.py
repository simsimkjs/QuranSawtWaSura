#!/usr/bin/env python3
"""
يضيف نصوص QCF (code_v2) وخطوط صفحات quran.com إلى نسخة الويب.

لا يغيّر هذا المولّد نصوص الآيات العادية (Unicode) التي يستخدمها سطح المكتب
والبحث؛ بل يضيف لكل آية حقلين اختياريين فقط:

    "qcf":      "ﱁ ﱂ"     نص العرض المرمّز بخط الصفحة
    "qcf_page": 2            رقم صفحة المصحف (1..604)

ثم ينزّل خطوط الصفحات المطلوبة إلى:

    web/assets/fonts/qcf/pNNN.woff2

ويكتب ملف قواعد الخطوط:

    web/css/qcf-fonts.css

وأخيرًا يحدّث manifest.json وsearch-index.json لأن بصمة النص تعتمد على محتوى
ملفات السور. الاستخدام:

    python tools/build_qcf_data.py
    python tools/build_qcf_data.py --force       # أعد تنزيل كل الخطوط
    python tools/build_qcf_data.py --no-index    # لا تحدّث manifest/search-index
    python tools/build_qcf_data.py --check       # تحقق offline (مناسب لـ CI)

المصادر (quran.com):
  - النص:  https://api.quran.com/api/v4/quran/verses/code_v2?chapter_number=N
  - الخط:  https://quran.com/fonts/quran/hafs/v2/woff2/pN.woff2

لا يحتاج أي مكتبات خارجية. يحتاج اتصالًا بالإنترنت في وضع التحديث فقط.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
QURAN_DIR = ROOT / "quran-data" / "quran"
FONT_DIR = ROOT / "web" / "assets" / "fonts" / "qcf"
CSS_PATH = ROOT / "web" / "css" / "qcf-fonts.css"

API_URL = "https://api.quran.com/api/v4/quran/verses/code_v2?chapter_number={number}"
FONT_URL = "https://quran.com/fonts/quran/hafs/v2/woff2/p{page}.woff2"
# نسخة خطوط quran.com المستخدمة. غيّرها عند تحديث الخطوط لتحديث ذاكرة المتصفح.
FONT_REV = "2026-09-09-1"
MAX_PAGE = 604
QCF_MIN = 0xFB50
QCF_MAX = 0xFEFF
USER_AGENT = "QuranSawtWaSura/1.0 (+https://github.com/simsimkjs/QuranSawtWaSura)"

RETRIES = 3
TIMEOUT = 90


def read_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8-sig") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path}: الجذر يجب أن يكون كائن JSON")
    return value


def write_json(path: Path, payload: dict) -> None:
    # نحافظ على نمط ملفات quran-data: بلا BOM، وبلا سطر جديد في النهاية.
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    path.write_text(text, encoding="utf-8", newline="\n")


def fetch_bytes(url: str) -> bytes:
    last_error: Exception | None = None
    for attempt in range(RETRIES):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
                return response.read()
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = error
            if attempt + 1 < RETRIES:
                time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"فشل الطلب: {url} ({last_error})")


def is_qcf_code(value: str) -> bool:
    """نصوص code_v2 تستخدم أشكال الحروف العربية في نطاق Presentation Forms-A."""
    return bool(value) and all(ch == " " or QCF_MIN <= ord(ch) <= QCF_MAX for ch in value)


def fetch_verses(surah_number: int) -> dict[int, tuple[str, int]]:
    raw = json.loads(fetch_bytes(API_URL.format(number=surah_number)).decode("utf-8"))
    verses = raw.get("verses")
    if not isinstance(verses, list):
        raise ValueError(f"السورة {surah_number}: استجابة API بلا قائمة verses")

    result: dict[int, tuple[str, int]] = {}
    for verse in verses:
        if not isinstance(verse, dict):
            continue
        number = verse.get("verse_number")
        if not isinstance(number, int):
            # استجابة code_v2 تستخدم verse_key مثل "2:1" بدل verse_number.
            key = verse.get("verse_key")
            if isinstance(key, str) and ":" in key:
                try:
                    number = int(key.rsplit(":", 1)[1])
                except ValueError:
                    number = None
        code = verse.get("code_v2")
        page = verse.get("v2_page")
        if not isinstance(number, int) or not isinstance(code, str):
            raise ValueError(f"السورة {surah_number}: آية بلا رقم/code_v2")
        if isinstance(page, str) and page.isdigit():
            page = int(page)
        if not isinstance(page, int) or not 1 <= page <= MAX_PAGE:
            raise ValueError(f"السورة {surah_number}: صفحة غير صالحة للآية {number}: {page!r}")
        code = code.strip()
        if not code:
            raise ValueError(f"السورة {surah_number}: code_v2 فارغ للآية {number}")
        if not is_qcf_code(code):
            raise ValueError(f"السورة {surah_number}: code_v2 خارج نطاق QCF للآية {number}")
        result[number] = (code, page)

    if not result:
        raise ValueError(f"السورة {surah_number}: لا توجد آيات في الاستجابة")
    return result


def update_surah(path: Path, *, force: bool = False) -> tuple[int, int, set[int], bool]:
    data = read_json(path)
    number = data.get("surah_number")
    if not isinstance(number, int) or number <= 0:
        raise ValueError(f"{path.name}: surah_number غير صالح")

    verses = fetch_verses(number)
    ayahs = data.get("ayahs")
    if not isinstance(ayahs, list):
        raise ValueError(f"{path.name}: ayahs ليست قائمة")

    real_numbers = {
        item.get("ayah_number")
        for item in ayahs
        if isinstance(item, dict)
        and isinstance(item.get("ayah_number"), int)
        and item.get("ayah_number") > 0
    }
    missing = sorted(set(verses) - real_numbers)
    extra = sorted(real_numbers - set(verses))
    if missing or extra:
        raise ValueError(
            f"{path.name}: عدم تطابق أرقام الآيات مع API "
            f"(ناقص: {missing[:5]}{'…' if len(missing) > 5 else ''}، "
            f"زائد: {extra[:5]}{'…' if len(extra) > 5 else ''})"
        )

    changed = False
    pages: set[int] = set()
    for item in ayahs:
        if not isinstance(item, dict):
            continue
        ayah_number = item.get("ayah_number")
        if not isinstance(ayah_number, int) or ayah_number <= 0:
            continue
        code, page = verses[ayah_number]
        if item.get("qcf") != code or item.get("qcf_page") != page:
            item["qcf"] = code
            item["qcf_page"] = page
            changed = True
        pages.add(page)

    if changed or force:
        write_json(path, data)
    return number, len(real_numbers), pages, changed


def font_path(page: int) -> Path:
    return FONT_DIR / f"p{page}.woff2"


def download_font(page: int, *, force: bool, verbose: bool) -> tuple[int, str]:
    path = font_path(page)
    if path.is_file() and path.stat().st_size > 0 and not force:
        with path.open("rb") as handle:
            if handle.read(4) == b"wOF2":
                return page, "موجود"
    data = fetch_bytes(FONT_URL.format(page=page) + f"?rev={FONT_REV}")
    if data[:4] != b"wOF2":
        raise ValueError(f"p{page}.woff2 ليس ملف WOFF2 صالحًا")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    if verbose:
        print(f"  ↓ p{page}.woff2 ({len(data):,} بايت)")
    return page, "نُزّل"


def download_fonts(pages: set[int], *, force: bool, verbose: bool) -> tuple[int, int]:
    downloaded = 0
    existing = 0
    errors: list[str] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        futures = {
            pool.submit(download_font, page, force=force, verbose=verbose): page
            for page in sorted(pages)
        }
        for future in concurrent.futures.as_completed(futures):
            page = futures[future]
            try:
                _, status = future.result()
                if status == "نُزّل":
                    downloaded += 1
                else:
                    existing += 1
            except Exception as error:  # noqa: BLE001 - نجمع كل الأخطاء ثم نبلّغ
                errors.append(f"p{page}: {error}")
    if errors:
        raise RuntimeError("تعذّر تنزيل بعض الخطوط:\n  - " + "\n  - ".join(errors))
    return downloaded, existing


def css_text(pages: set[int]) -> str:
    lines = [
        "/* Auto-generated by tools/build_qcf_data.py — do not edit. */",
        "",
    ]
    for page in sorted(pages):
        lines.extend(
            [
                "@font-face {",
                f'  font-family: "QCF-P{page}";',
                f'  src: url("../assets/fonts/qcf/p{page}.woff2?rev={FONT_REV}") format("woff2");',
                "  font-display: swap;",
                "}",
                "",
            ]
        )
    return "\n".join(lines).rstrip() + "\n"


def write_css(pages: set[int]) -> bool:
    expected = css_text(pages)
    if CSS_PATH.is_file() and CSS_PATH.read_text(encoding="utf-8") == expected:
        return False
    CSS_PATH.parent.mkdir(parents=True, exist_ok=True)
    CSS_PATH.write_text(expected, encoding="utf-8", newline="\n")
    return True


def check() -> int:
    errors: list[str] = []
    pages: set[int] = set()
    surahs = 0
    ayahs = 0

    files = sorted(QURAN_DIR.glob("*.json"))
    if not files:
        print(f"لم أجد ملفات سور في {QURAN_DIR}", file=sys.stderr)
        return 2

    for path in files:
        try:
            data = read_json(path)
        except (OSError, ValueError, json.JSONDecodeError) as error:
            errors.append(f"{path.name}: {error}")
            continue

        items = data.get("ayahs")
        if not isinstance(items, list):
            errors.append(f"{path.name}: ayahs ليست قائمة")
            continue

        surah_has_ayahs = False
        for item in items:
            if not isinstance(item, dict):
                continue
            number = item.get("ayah_number")
            if not isinstance(number, int) or number <= 0:
                continue
            surah_has_ayahs = True
            ayahs += 1

            code = item.get("qcf")
            page = item.get("qcf_page")
            if not isinstance(code, str) or not is_qcf_code(code.strip()):
                errors.append(f"{path.name}: الآية {number} بلا qcf صالح")
                continue
            if not isinstance(page, int) or not 1 <= page <= MAX_PAGE:
                errors.append(f"{path.name}: الآية {number} بلا qcf_page صالحة ({page!r})")
                continue
            pages.add(page)

        if surah_has_ayahs:
            surahs += 1

    missing_fonts: list[int] = []
    bad_fonts: list[int] = []
    for page in sorted(pages):
        path = font_path(page)
        if not path.is_file() or path.stat().st_size == 0:
            missing_fonts.append(page)
            continue
        try:
            with path.open("rb") as handle:
                if handle.read(4) != b"wOF2":
                    bad_fonts.append(page)
        except OSError:
            bad_fonts.append(page)

    if missing_fonts:
        errors.append(f"خطوط ناقصة ({len(missing_fonts)}): {missing_fonts[:12]}")
    if bad_fonts:
        errors.append(f"ملفات ليست WOFF2 ({len(bad_fonts)}): {bad_fonts[:12]}")

    if CSS_PATH.is_file():
        if CSS_PATH.read_text(encoding="utf-8") != css_text(pages):
            errors.append(f"{CSS_PATH.relative_to(ROOT)} غير محدَّث")
    else:
        errors.append(f"{CSS_PATH.relative_to(ROOT)} ناقص")

    print(f"السور: {surahs} | الآيات: {ayahs} | الصفحات: {len(pages)}")
    if errors:
        for error in errors:
            print(f"  ✗ {error}", file=sys.stderr)
        print("شغّل: python tools/build_qcf_data.py", file=sys.stderr)
        return 1

    print("  ✓ نصوص QCF وخطوط الصفحات وqcf-fonts.css محدَّثة")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="يضيف نصوص QCF وخطوط صفحات quran.com إلى نسخة الويب"
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="تحقق offline من النصوص والخطوط وCSS دون اتصال",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="أعد تنزيل الخطوط واكتب ملفات السور حتى لو لم تتغير",
    )
    parser.add_argument(
        "--no-index",
        action="store_true",
        help="لا تحدّث manifest.json وsearch-index.json بعد التعديل",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="اطبع كل خط يُنزَّل",
    )
    args = parser.parse_args(argv)

    if not QURAN_DIR.is_dir():
        print(f"لم أجد {QURAN_DIR}", file=sys.stderr)
        return 2

    if args.check:
        return check()

    files = sorted(QURAN_DIR.glob("*.json"))
    if not files:
        print(f"لم أجد ملفات سور في {QURAN_DIR}", file=sys.stderr)
        return 2

    pages: set[int] = set()
    changed_surahs = 0
    total_ayahs = 0
    failures: list[str] = []

    for path in files:
        try:
            number, count, surah_pages, changed = update_surah(path, force=args.force)
        except Exception as error:  # noqa: BLE001 - نكمل السور الأخرى ثم نبلّغ
            failures.append(f"{path.name}: {error}")
            print(f"  ✗ {path.name}: {error}", file=sys.stderr)
            continue
        pages.update(surah_pages)
        total_ayahs += count
        if changed:
            changed_surahs += 1
            print(f"  ✓ السورة {number}: أُضيف QCF ({count} آية، {len(surah_pages)} صفحة)")

    if failures:
        print(f"فشلت {len(failures)} سورة؛ لم أنزّل خطوطًا.", file=sys.stderr)
        return 1

    print(f"السور المحدَّثة: {changed_surahs} | الآيات: {total_ayahs} | الصفحات: {len(pages)}")

    try:
        downloaded, existing = download_fonts(pages, force=args.force, verbose=args.verbose)
    except Exception as error:  # noqa: BLE001
        print(f"  ✗ {error}", file=sys.stderr)
        return 1

    css_changed = write_css(pages)
    print(f"  ✓ الخطوط: {downloaded} نُزّل، {existing} موجود")
    print(f"  {'✓' if css_changed else '='} {CSS_PATH.relative_to(ROOT)}")

    if not args.no_index:
        print("  … تحديث manifest.json وsearch-index.json")
        result = subprocess.run(
            [sys.executable, str(ROOT / "tools" / "build_quran_data.py"), "--root", str(ROOT)],
            cwd=ROOT,
        )
        if result.returncode != 0:
            print("  ✗ فشل تحديث الفهارس", file=sys.stderr)
            return result.returncode

    print("تم.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
