#!/usr/bin/env python3
"""
خادم ملفات ثابت محلي لنسخة الويب، يدعم HTTP Range requests.

لماذا؟ ملفات التلاوة كبيرة (مئات الميغابايت)، وبدون دعم Range لا يستطيع
المتصفح القفز إلى موضع معيّن داخل الملف (الانتقال لآية أو تحريك شريط التقدّم).

الاستخدام (من أي مكان):
    python web/serve.py
    python web/serve.py -p 9000
    python web/serve.py -d "E:\\prg\\GitHub\\java\\QuranSawtWaSura"
"""

from __future__ import annotations

import argparse
import functools
import os
import re
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")
CHUNK_SIZE = 64 * 1024


class RangeRequestHandler(SimpleHTTPRequestHandler):
    """SimpleHTTPRequestHandler مع دعم Range وAccept-Ranges."""

    protocol_version = "HTTP/1.1"

    def send_head(self):
        self._range_remaining = None

        path = self.translate_path(self.path)
        if os.path.isdir(path):
            return super().send_head()

        try:
            file_handle = open(path, "rb")
        except OSError:
            self.send_error(404, "File not found")
            return None

        try:
            stat = os.fstat(file_handle.fileno())
            size = stat.st_size
            content_type = self.guess_type(path)
            range_header = self.headers.get("Range", "").strip()
            match = RANGE_RE.match(range_header) if range_header else None

            if match and not (match.group(1) == "" and match.group(2) == ""):
                start_text, end_text = match.group(1), match.group(2)

                if start_text == "":
                    suffix = int(end_text)
                    start = max(0, size - suffix)
                    end = size - 1
                else:
                    start = int(start_text)
                    end = int(end_text) if end_text else size - 1
                    end = min(end, size - 1)

                if start >= size or end < start:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    file_handle.close()
                    return None

                self.send_response(206)
                self.send_header("Content-Type", content_type)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                self.send_header("Content-Length", str(end - start + 1))
                self.send_header("Last-Modified", self.date_time_string(stat.st_mtime))
                self.end_headers()

                file_handle.seek(start)
                self._range_remaining = end - start + 1
                return file_handle

            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(size))
            self.send_header("Last-Modified", self.date_time_string(stat.st_mtime))
            self.end_headers()
            return file_handle
        except Exception:
            file_handle.close()
            raise

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
            # المتصفح أغلق الاتصال قبل إرسال الطلب (شائع عند إعادة التحميل/الإغلاق)
            self.close_connection = True

    def copyfile(self, source, outputfile):
        remaining = getattr(self, "_range_remaining", None)
        if remaining is None:
            try:
                return super().copyfile(source, outputfile)
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
                return  # المتصفح أغلق الاتصال (إلغاء تحميل/قفز) - ليس خطأً

        try:
            while remaining > 0:
                chunk = source.read(min(CHUNK_SIZE, remaining))
                if not chunk:
                    break
                outputfile.write(chunk)
                remaining -= len(chunk)
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
            pass  # المتصفح أغلق الاتصال أثناء البث - طبيعي عند القفز في الصوت

    def log_message(self, fmt, *args):
        # أقل ضجيجًا: لا نطبع كل طلب ملف
        if args and str(args[1]).startswith(("4", "5")):
            super().log_message(fmt, *args)

    def end_headers(self):
        # أثناء التطوير: HTML/CSS/JS/JSON دائمًا حديثة (تمنع بقاء واجهة أو فهرس قديم في الذاكرة)
        path = self.path.split("?", 1)[0].lower()
        if path.endswith((".html", ".css", ".js", ".json")) or path.endswith("/"):
            self.send_header("Cache-Control", "no-cache")
        else:
            self.send_header("Cache-Control", "public, max-age=3600")
        super().end_headers()


def main() -> None:
    default_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    parser = argparse.ArgumentParser(description="خادم القرآن المحلي (يدعم Range)")
    parser.add_argument("-p", "--port", type=int, default=8000, help="المنفذ (افتراضي 8000)")
    parser.add_argument("-b", "--bind", default="127.0.0.1", help="العنوان (افتراضي 127.0.0.1)")
    parser.add_argument("-d", "--directory", default=default_root, help="جذر الملفات (افتراضي: جذر المشروع)")
    args = parser.parse_args()

    root = os.path.abspath(args.directory)
    handler = functools.partial(RangeRequestHandler, directory=root)

    ThreadingHTTPServer.daemon_threads = True
    with ThreadingHTTPServer((args.bind, args.port), handler) as server:
        print()
        print("  القرآن الكريم — نسخة الويب")
        print(f"  المجلد: {root}")
        print(f"  الرابط: http://{args.bind}:{args.port}/web/")
        print("  للإيقاف: Ctrl+C")
        print()
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nتم الإيقاف.")


if __name__ == "__main__":
    main()
