#!/usr/bin/env python3
"""
يبني ملفَّي البيانات المشتقَّين اللذين تقرأهما نسخة الويب:

  quran-data/manifest.json       فهرس توفّر النص/الصوت/التفسير لكل سورة
  quran-data/search-index.json   فهرس بحث مسبق مبني من الآيات المتوفرة

شغّله بعد إضافة/حذف/تعديل أي سورة أو ملف صوت أو تفسير:

    python tools/build_quran_data.py

للتحقق فقط من أن الملفات المشتقة محدَّثة (مناسب لـ CI):

    python tools/build_quran_data.py --check

لا يحتاج أي مكتبات خارجية. لا يعدّل ملفات quran-data الأصلية.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

MANIFEST_NAME = "manifest.json"
SEARCH_INDEX_NAME = "search-index.json"
MANIFEST_FORMAT = "qss-manifest"
SEARCH_INDEX_FORMAT = "qss-search-index"
FORMAT_VERSION = 1


def clean_text(value: object) -> str:
    return str(value or "").lstrip("\ufeff").strip()


def hash_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path) -> object:
    with path.open("r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def write_json(path: Path, payload: object, *, pretty: bool) -> str:
    if pretty:
        text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    else:
        text = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    return text


def parse_surah_file(path: Path) -> tuple[dict, list[tuple[int, str, int | float | None]]]:
    raw = read_json(path)
    if not isinstance(raw, dict):
        raise ValueError(f"{path}: الجذر يجب أن يكون كائن JSON")

    ayahs = raw.get("ayahs")
    if not isinstance(ayahs, list):
        ayahs = []

    displayable: list[tuple[int, str, int | float | None]] = []
    seen: set[int] = set()
    for item in ayahs:
        if not isinstance(item, dict):
            continue
        number = item.get("ayah_number")
        text = clean_text(item.get("text"))
        if not isinstance(number, int) or number <= 0 or not text:
            continue
        if number in seen:
            print(f"  ! {path.name}: رقم آية مكرر ({number})", file=sys.stderr)
        seen.add(number)
        start_ms = item.get("start_ms")
        if not isinstance(start_ms, (int, float)):
            start_ms = None
        displayable.append((number, text, start_ms))

    displayable.sort(key=lambda row: row[0])
    return raw, displayable


def format_ranges(numbers: list[int]) -> str:
    """يجمع الأرقام المتتالية في نطاقات مختصرة: 5–12، 19–39، 41–114."""
    if not numbers:
        return ""
    numbers = sorted(set(numbers))
    ranges: list[str] = []
    start = previous = numbers[0]
    for number in numbers[1:]:
        if number == previous + 1:
            previous = number
            continue
        ranges.append(f"{start}" if start == previous else f"{start}–{previous}")
        start = previous = number
    ranges.append(f"{start}" if start == previous else f"{start}–{previous}")
    return "، ".join(ranges)


def build(root: Path, *, verbose: bool = False) -> tuple[dict, dict, str, str]:
    data_dir = root / "quran-data"
    surahs_path = data_dir / "surahs.json"
    quran_dir = data_dir / "quran"
    audio_dir = data_dir / "audio"
    tafsir_dir = data_dir / "tafsir"

    surahs_raw = read_json(surahs_path)
    if not isinstance(surahs_raw, list):
        raise ValueError(f"{surahs_path}: يجب أن يكون مصفوفة")

    entries: list[list[object]] = []
    manifest_surahs: list[dict] = []
    missing_text: list[int] = []

    for surah in surahs_raw:
        if not isinstance(surah, dict):
            continue
        number = surah.get("number")
        name = str(surah.get("name") or "")
        if not isinstance(number, int) or number <= 0:
            continue

        expected_ayahs = surah.get("ayahs")
        if not isinstance(expected_ayahs, int):
            expected_ayahs = 0

        text_path = quran_dir / f"{number:03d}.json"
        text_available = False
        text_ayahs = 0
        text_sha256 = None
        text_raw: dict = {}
        displayable: list[tuple[int, str, int | float | None]] = []

        if text_path.is_file():
            text_raw, displayable = parse_surah_file(text_path)
            text_available = len(displayable) > 0
            text_ayahs = len(displayable)
            text_sha256 = hash_file(text_path)

            source_number = text_raw.get("surah_number")
            if isinstance(source_number, int) and source_number != number:
                print(
                    f"  ! {text_path.name}: surah_number={source_number} لا يطابق {number}",
                    file=sys.stderr,
                )
            if expected_ayahs and text_ayahs != expected_ayahs:
                print(
                    f"  ! {text_path.name}: {text_ayahs} آية فعلية، والمتوقع {expected_ayahs}",
                    file=sys.stderr,
                )

            previous_start = None
            for ayah_number, text, start_ms in displayable:
                if start_ms is None:
                    print(
                        f"  ! {text_path.name}: الآية {ayah_number} بلا start_ms",
                        file=sys.stderr,
                    )
                else:
                    if previous_start is not None and start_ms < previous_start:
                        print(
                            f"  ! {text_path.name}: start_ms غير تصاعدي عند الآية {ayah_number}",
                            file=sys.stderr,
                        )
                    previous_start = start_ms
                # البحث لا يحتاج start_ms؛ أضف الآية حتى لو كان التوقيت ناقصًا
                entries.append([number, ayah_number, text])
        else:
            missing_text.append(number)
            if verbose:
                print(f"  - لا يوجد نص للسورة {number} ({name})", file=sys.stderr)

        audio_name = str(text_raw.get("audio_file_name") or f"{number:03d}.mp3")
        audio_path = audio_dir / audio_name
        audio_available = audio_path.is_file() and audio_path.stat().st_size > 0
        audio_bytes = audio_path.stat().st_size if audio_available else None

        tafsir_path = tafsir_dir / f"{number:03d}.json"
        tafsir_available = tafsir_path.is_file() and tafsir_path.stat().st_size > 0
        tafsir_sha256 = hash_file(tafsir_path) if tafsir_available else None

        item: dict[str, object] = {
            "number": number,
            "name": name,
            "text": text_available,
            "audio": audio_available,
            "tafsir": tafsir_available,
            "ayahs": expected_ayahs,
            "text_ayahs": text_ayahs,
        }
        if text_sha256:
            item["text_sha256"] = text_sha256
        if audio_available:
            item["audio_file"] = audio_name
            item["audio_bytes"] = audio_bytes
        if tafsir_sha256:
            item["tafsir_sha256"] = tafsir_sha256

        manifest_surahs.append(item)

    entries.sort(key=lambda row: (int(row[0]), int(row[1])))

    if missing_text:
        print(
            f"  - بلا نص: {len(missing_text)} سورة ({format_ranges(missing_text)})",
            file=sys.stderr,
        )

    counts = {
        "surahs": len(manifest_surahs),
        "text": sum(1 for item in manifest_surahs if item["text"]),
        "audio": sum(1 for item in manifest_surahs if item["audio"]),
        "tafsir": sum(1 for item in manifest_surahs if item["tafsir"]),
        "ayahs": len(entries),
    }

    # البصمة تخصّ النص فقط (وليس الصوت/التفسير):
    # - الصوت يُوزَّع خارج git، فلا يمكن التحقق منه في CI.
    # - تغيير التفسير لا يؤثر في فهرس البحث، فلا داعي لإبطاله.
    text_keys = ("number", "name", "text", "ayahs", "text_ayahs", "text_sha256")
    fingerprint_entries = [
        {key: item[key] for key in text_keys if key in item}
        for item in manifest_surahs
    ]
    fingerprint = hash_bytes(
        json.dumps(
            fingerprint_entries,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    )

    manifest = {
        "format": MANIFEST_FORMAT,
        "version": FORMAT_VERSION,
        "fingerprint": fingerprint,
        "search_index": SEARCH_INDEX_NAME,
        "counts": counts,
        "surahs": manifest_surahs,
    }

    search_index = {
        "format": SEARCH_INDEX_FORMAT,
        "version": FORMAT_VERSION,
        "fingerprint": fingerprint,
        "count": len(entries),
        "surahs": [item["number"] for item in manifest_surahs if item["text"]],
        "entries": entries,
    }

    manifest_text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    index_text = json.dumps(search_index, ensure_ascii=False, separators=(",", ":")) + "\n"
    return manifest, search_index, manifest_text, index_text


def manifest_without_audio(manifest: object) -> object:
    """نسخة من manifest بلا حقول الصوت، لمقارنة --check في بيئة بلا ملفات صوت."""
    if not isinstance(manifest, dict):
        return manifest
    normalized = json.loads(json.dumps(manifest))
    for item in normalized.get("surahs", []):
        if not isinstance(item, dict):
            continue
        for key in ("audio", "audio_file", "audio_bytes"):
            item.pop(key, None)
    counts = normalized.get("counts")
    if isinstance(counts, dict):
        counts.pop("audio", None)
    return normalized


def canonical(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="يبني manifest.json وsearch-index.json من quran-data")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help="جذر المشروع (الافتراضي: المجلد الأب لـ tools)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="تحقق فقط؛ أرجع 1 إذا كانت الملفات المشتقة ناقصة أو قديمة",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="اطبع تنبيهًا لكل سورة بلا نص",
    )
    args = parser.parse_args(argv)

    root = args.root.resolve()
    data_dir = root / "quran-data"
    if not (data_dir / "surahs.json").is_file():
        print(f"لم أجد {data_dir / 'surahs.json'}", file=sys.stderr)
        return 2

    try:
        manifest, _, manifest_text, index_text = build(root, verbose=args.verbose)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"تعذّر بناء البيانات: {error}", file=sys.stderr)
        return 2

    outputs = {
        data_dir / MANIFEST_NAME: manifest_text,
        data_dir / SEARCH_INDEX_NAME: index_text,
    }

    counts = manifest["counts"]
    print(
        "السور: {surahs} | النص: {text} | الصوت: {audio} | التفسير: {tafsir} | الآيات: {ayahs}".format(
            **counts
        )
    )
    print(f"بصمة البيانات: {manifest['fingerprint'][:16]}…")

    if args.check:
        stale = False
        manifest_path = data_dir / MANIFEST_NAME
        index_path = data_dir / SEARCH_INDEX_NAME

        if not manifest_path.is_file():
            print(f"  ✗ ناقص: {manifest_path.relative_to(root)}")
            stale = True
        else:
            try:
                actual_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                same_manifest = canonical(manifest_without_audio(actual_manifest)) == canonical(
                    manifest_without_audio(manifest)
                )
            except (OSError, json.JSONDecodeError):
                same_manifest = False
            if same_manifest:
                print(f"  ✓ محدَّث: {manifest_path.relative_to(root)} (تُجوهلت حقول الصوت)")
            else:
                print(f"  ✗ قديم: {manifest_path.relative_to(root)}")
                stale = True

        if not index_path.is_file():
            print(f"  ✗ ناقص: {index_path.relative_to(root)}")
            stale = True
        else:
            actual_index = index_path.read_text(encoding="utf-8")
            if actual_index == index_text:
                print(f"  ✓ محدَّث: {index_path.relative_to(root)}")
            else:
                print(f"  ✗ قديم: {index_path.relative_to(root)}")
                stale = True

        if stale:
            print("شغّل: python tools/build_quran_data.py")
            return 1
        return 0

    for path, text in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"  ✓ كُتب: {path.relative_to(root)} ({path.stat().st_size:,} بايت)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
