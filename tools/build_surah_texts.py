#!/usr/bin/env python3
"""
ينشئ ملفات السور الناقصة في quran-data/quran من نص quran.com العثماني.

الهدف: تجهيز الملفات الأساسية (النص العادي + start_ms) للسور التي لا يوجد لها
ملف بعد، ثم يضيف tools/build_qcf_data.py نصوص code_v2 وخطوط QCF تلقائيًا.

لا يلمس هذا المولّد أي ملف موجود (حفاظًا على توقيتات الصوت والتعديلات اليدوية).
السور الجديدة تكون نصًّا فقط: start_ms = 0 ولا ملف صوت.

الاستخدام:

    python tools/build_surah_texts.py            # أنشئ كل الملفات الناقصة
    python tools/build_surah_texts.py --surah 5  # أنشئ سورة واحدة فقط
    python tools/build_surah_texts.py --check    # تحقق offline (مناسب لـ CI)

المصدر:
  https://api.quran.com/api/v4/quran/verses/uthmani?chapter_number=N

لا يحتاج أي مكتبات خارجية. يحتاج اتصالًا بالإنترنت في وضع الإنشاء فقط.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SURAH_INDEX = ROOT / "quran-data" / "surahs.json"
QURAN_DIR = ROOT / "quran-data" / "quran"

API_URL = "https://api.quran.com/api/v4/quran/verses/uthmani?chapter_number={number}"
USER_AGENT = "QuranSawtWaSura/1.0 (+https://github.com/simsimkjs/QuranSawtWaSura)"
RETRIES = 3
TIMEOUT = 90

# لا نستخدم 0 كرقم سورة؛ رقم الآية 0 في الملف هو مدخل تمهيدي لا نص له.
def clean_text(value: object) -> str:
    return str(value or "").lstrip("\ufeff").strip()


def read_json(path: Path) -> object:
    with path.open("r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def fetch_json(url: str) -> dict:
    last_error: Exception | None = None
    for attempt in range(RETRIES):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as error:
            last_error = error
            if attempt + 1 < RETRIES:
                time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"فشل الطلب: {url} ({last_error})")


def surah_path(number: int) -> Path:
    return QURAN_DIR / f"{number:03d}.json"


def load_meta() -> list[dict]:
    data = read_json(SURAH_INDEX)
    if not isinstance(data, list):
        raise ValueError(f"{SURAH_INDEX}: الجذر يجب أن يكون قائمة")
    return data


def create_surah(meta: dict, *, verbose: bool = False) -> tuple[int, int]:
    number = meta.get("number")
    name = meta.get("name")
    expected = meta.get("ayahs")
    if not isinstance(number, int) or not 1 <= number <= 114:
        raise ValueError(f"رقم سورة غير صالح: {number!r}")
    if not isinstance(name, str) or not name.strip():
        raise ValueError(f"اسم سورة غير صالح للسورة {number}")
    if not isinstance(expected, int) or expected <= 0:
        raise ValueError(f"عدد آيات غير صالح للسورة {number}")

    raw = fetch_json(API_URL.format(number=number))
    verses = raw.get("verses")
    if not isinstance(verses, list):
        raise ValueError(f"السورة {number}: استجابة API بلا قائمة verses")

    by_number: dict[int, str] = {}
    for verse in verses:
        if not isinstance(verse, dict):
            continue
        key = verse.get("verse_key")
        text = clean_text(verse.get("text_uthmani"))
        if not isinstance(key, str) or ":" not in key:
            raise ValueError(f"السورة {number}: verse_key غير صالح")
        prefix, _, suffix = key.partition(":")
        if prefix != str(number) or not suffix.isdigit():
            raise ValueError(f"السورة {number}: verse_key غير متوقع: {key}")
        verse_number = int(suffix)
        if not text:
            raise ValueError(f"السورة {number}: نص فارغ للآية {verse_number}")
        if verse_number in by_number:
            raise ValueError(f"السورة {number}: تكرار الآية {verse_number}")
        by_number[verse_number] = text

    expected_numbers = set(range(1, expected + 1))
    missing = sorted(expected_numbers - set(by_number))
    extra = sorted(set(by_number) - expected_numbers)
    if missing or extra:
        raise ValueError(
            f"السورة {number}: عدم تطابق الآيات مع API "
            f"(ناقص: {missing[:5]}{'…' if len(missing) > 5 else ''}، "
            f"زائد: {extra[:5]}{'…' if len(extra) > 5 else ''})"
        )

    ayahs = [{"index": 0, "ayah_number": 0, "text": "", "start_ms": 0, "export": True}]
    for verse_number in range(1, expected + 1):
        ayahs.append(
            {
                "index": verse_number,
                "ayah_number": verse_number,
                "text": by_number[verse_number],
                "start_ms": 0,
                "export": True,
            }
        )

    payload = {
        "format": "quran-ayah-splitter-project",
        "version": 1,
        "surah_number": number,
        "surah_name": name,
        "audio_file_name": str(meta.get("audio") or f"{number:03d}.mp3"),
        "saved_at": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "ayahs": ayahs,
    }

    path = surah_path(number)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    if verbose:
        print(f"  ✓ {path.name}: {expected} آية ({name})")
    return number, expected


def check() -> int:
    try:
        metas = load_meta()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"تعذّر قراءة {SURAH_INDEX}: {error}", file=sys.stderr)
        return 2

    errors: list[str] = []
    missing: list[int] = []
    total_ayahs = 0
    present = 0

    for meta in metas:
        if not isinstance(meta, dict):
            errors.append("surahs.json: عنصر غير صالح")
            continue
        number = meta.get("number")
        expected = meta.get("ayahs")
        if not isinstance(number, int) or not isinstance(expected, int):
            errors.append(f"surahs.json: سورة برقم/عدد آيات غير صالح: {meta!r}")
            continue
        path = surah_path(number)
        if not path.is_file():
            missing.append(number)
            continue
        present += 1
        try:
            data = read_json(path)
        except (OSError, ValueError, json.JSONDecodeError) as error:
            errors.append(f"{path.name}: {error}")
            continue
        if not isinstance(data, dict):
            errors.append(f"{path.name}: الجذر يجب أن يكون كائن JSON")
            continue
        if data.get("surah_number") != number:
            errors.append(f"{path.name}: surah_number={data.get('surah_number')!r} لا يطابق {number}")
            continue
        ayahs = data.get("ayahs")
        if not isinstance(ayahs, list):
            errors.append(f"{path.name}: ayahs ليست قائمة")
            continue
        numbers = sorted(
            item.get("ayah_number")
            for item in ayahs
            if isinstance(item, dict)
            and isinstance(item.get("ayah_number"), int)
            and item.get("ayah_number") > 0
            and clean_text(item.get("text"))
        )
        total_ayahs += len(numbers)
        if numbers != list(range(1, expected + 1)):
            errors.append(
                f"{path.name}: الآيات لا تطابق المتوقع {expected} "
                f"(وجدت {len(numbers)}، ناقص: {sorted(set(range(1, expected + 1)) - set(numbers))[:5]})"
            )

    print(f"السور: {len(metas)} | الملفات الموجودة: {present} | الآيات: {total_ayahs}")
    if missing:
        errors.append(f"ملفات ناقصة ({len(missing)}): {missing[:20]}")
    if errors:
        for error in errors:
            print(f"  ✗ {error}", file=sys.stderr)
        print("شغّل: python tools/build_surah_texts.py", file=sys.stderr)
        return 1

    print("  ✓ كل ملفات السور موجودة وتحتوي الآيات المتوقعة")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="ينشئ ملفات السور الناقصة من نص quran.com العثماني (نص فقط، بلا صوت)"
    )
    parser.add_argument("--check", action="store_true", help="تحقق offline من وجود كل ملفات السور وآياتها")
    parser.add_argument("--surah", type=int, help="أنشئ سورة واحدة فقط (رقم 1..114)")
    parser.add_argument("--verbose", action="store_true", help="اطبع كل سورة تُنشأ")
    args = parser.parse_args(argv)

    if not QURAN_DIR.is_dir():
        print(f"لم أجد {QURAN_DIR}", file=sys.stderr)
        return 2

    if args.check:
        return check()

    try:
        metas = load_meta()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"تعذّر قراءة {SURAH_INDEX}: {error}", file=sys.stderr)
        return 2

    if args.surah is not None:
        metas = [meta for meta in metas if isinstance(meta, dict) and meta.get("number") == args.surah]
        if not metas:
            print(f"رقم السورة غير موجود في {SURAH_INDEX}: {args.surah}", file=sys.stderr)
            return 2

    created = 0
    skipped = 0
    total_ayahs = 0
    failures: list[str] = []

    for meta in metas:
        number = meta.get("number") if isinstance(meta, dict) else None
        if not isinstance(number, int):
            failures.append(f"عنصر غير صالح في surahs.json: {meta!r}")
            continue
        path = surah_path(number)
        if path.is_file():
            skipped += 1
            if args.verbose:
                print(f"  = {path.name}: موجود (لن يُستبدل)")
            continue
        try:
            _, count = create_surah(meta, verbose=args.verbose)
        except Exception as error:  # noqa: BLE001 - نكمل السور الأخرى ثم نبلّغ
            failures.append(f"{path.name}: {error}")
            print(f"  ✗ {path.name}: {error}", file=sys.stderr)
            continue
        created += 1
        total_ayahs += count
        if not args.verbose:
            print(f"  ✓ {path.name}")

    print(f"أُنشئت: {created} | موجودة: {skipped} | الآيات الجديدة: {total_ayahs}")
    if failures:
        print(f"فشلت {len(failures)} سورة؛ راجع الأخطاء أعلاه.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
