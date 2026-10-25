/**
 * طبقة الوصول إلى البيانات: تقرأ نفس ملفات نسخة سطح المكتب من مجلد quran-data.
 *
 * ملاحظة: يجب تقديم الموقع عبر خادم HTTP (وليس فتح index.html مباشرة)،
 * لأن المتصفح يمنع fetch للملفات المحلية عبر بروتوكول file://.
 */

const DEFAULT_BASE = "../quran-data/";

function resolveBase() {
  const raw = (typeof window !== "undefined" && window.QURAN_DATA_BASE) || DEFAULT_BASE;
  try {
    return new URL(raw, document.baseURI).href;
  } catch {
    return DEFAULT_BASE;
  }
}

/** المسار الكامل لمجلد البيانات. يمكن تجاوزه من index.html عبر window.QURAN_DATA_BASE. */
export const DATA_BASE = resolveBase();

function dataUrl(relative) {
  return new URL(relative, DATA_BASE).href;
}

function pad3(n) {
  return String(n).padStart(3, "0");
}

export class HttpError extends Error {
  constructor(status, url) {
    super(`تعذّر تحميل الملف (HTTP ${status}): ${url}`);
    this.name = "HttpError";
    this.status = status;
    this.url = url;
  }
}

async function fetchJson(url, options) {
  const res = await fetch(url, { cache: "no-cache", ...(options || {}) });
  if (!res.ok) throw new HttpError(res.status, url);
  return res.json();
}

/* ------------------------------ النصوص ------------------------------ */

/** يزيل BOM وأي فراغات زائدة من بداية/نهاية نص الآية. */
export function cleanText(text) {
  return String(text ?? "").replace(/^\uFEFF/, "").trim();
}

/** هل هذا العنصر آية حقيقية لها نص (وليس مقطعًا افتتاحيًا فارغًا)؟ */
export function hasText(ayah) {
  return !!ayah && ayah.ayah_number > 0 && cleanText(ayah.text).length > 0;
}

const MARKS_RE = /[\u064B-\u065F\u0670\u06D6-\u06ED\u0640]/;
const CHAR_MAP = { "أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي" };

/** تطبيع عربي بسيط للبحث: إزالة التشكيل وتوحيد الهمزات والألف. */
export function normalizeArabic(text) {
  return String(text ?? "")
    .replace(/[\u064B-\u065F\u0670\u06D6-\u06ED\u0640]/g, "")
    .replace(/[أإآٱ]/g, "ا")
    .replace(/ى/g, "ي")
    .trim();
}

/**
 * تطبيع مع خريطة رجوع للفهرس الأصلي (لإبراز نتيجة البحث داخل النص الأصلي).
 * map[i] = موضع الحرف المطبَّع i داخل النص الأصلي.
 */
export function normalizeArabicWithMap(text) {
  const src = String(text ?? "");
  let out = "";
  const map = [];

  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (MARKS_RE.test(ch)) continue;
    out += CHAR_MAP[ch] || ch;
    map.push(i);
  }

  let start = 0;
  let end = out.length;
  while (start < end && /\s/.test(out[start])) start += 1;
  while (end > start && /\s/.test(out[end - 1])) end -= 1;

  return { normalized: out.slice(start, end), map: map.slice(start, end) };
}

/* ------------------------------ السور ------------------------------ */

let surahIndexPromise = null;

/** فهرس السور الكامل (114 سورة) من quran-data/surahs.json. */
export function loadSurahIndex() {
  if (!surahIndexPromise) {
    surahIndexPromise = fetchJson(dataUrl("surahs.json")).then((list) => {
      if (!Array.isArray(list)) throw new Error("صيغة surahs.json غير صحيحة");
      return list;
    });
    surahIndexPromise.catch(() => { surahIndexPromise = null; });
  }
  return surahIndexPromise;
}

const surahCache = new Map();

/** بيانات سورة واحدة من quran-data/quran/NNN.json (مع تخزين مؤقت). */
export function loadSurahData(number) {
  if (surahCache.has(number)) return surahCache.get(number);

  const url = dataUrl(`quran/${pad3(number)}.json`);
  const promise = fetchJson(url).then((data) => {
    data.ayahs = Array.isArray(data.ayahs) ? data.ayahs : [];
    return data;
  });

  surahCache.set(number, promise);
  promise.catch(() => surahCache.delete(number)); // اسمح بإعادة المحاولة عند الطلب التالي
  return promise;
}

/** رابط ملف التلاوة. */
export function audioUrl(fileName, surahNumber) {
  const file = fileName || `${pad3(surahNumber)}.mp3`;
  return dataUrl(`audio/${encodeURIComponent(file)}`);
}

/** ملاحظة: الآيات المعروضة/المتزامنة هي فقط التي لها نص. */
export function visibleAyahs(ayahs) {
  return (ayahs || []).filter(hasText);
}

/* ------------------------------ التفسير ------------------------------ */

const tafsirCache = new Map();

/**
 * يحاول تحميل تفسير سورة. الصيغة المفترضة:
 * { "source": "...", "ayahs": [ { "ayah_number": 1, "text": "..." } ] }
 * يعيد null بصمت إن لم يكن الملف موجودًا بعد.
 */
export function loadTafsir(number) {
  if (tafsirCache.has(number)) return tafsirCache.get(number);

  const url = dataUrl(`tafsir/${pad3(number)}.json`);
  const promise = fetchJson(url)
    .then((raw) => {
      const byAyah = new Map();
      for (const item of raw.ayahs || []) {
        if (item && Number.isInteger(item.ayah_number)) {
          byAyah.set(item.ayah_number, cleanText(item.text));
        }
      }
      return { source: raw.source || "", byAyah };
    })
    .catch(() => null);

  tafsirCache.set(number, promise);
  return promise;
}

/* ------------------------------ البحث ------------------------------ */

let searchIndex = null;
let searchBuilding = null;

/**
 * يبني فهرس بحث نصيًا عبر كل السور المتوفرة. السور غير المرفوعة تُتجاهل بصمت.
 * @param {(percent:number)=>void} [onProgress]
 */
export function buildSearchIndex(onProgress) {
  if (searchIndex) return Promise.resolve(searchIndex);
  if (searchBuilding) return searchBuilding;

  searchBuilding = (async () => {
    const surahs = await loadSurahIndex();
    const entries = [];
    let done = 0;
    let cursor = 0;
    const CONCURRENCY = 6;

    async function worker() {
      while (cursor < surahs.length) {
        const surah = surahs[cursor++];
        try {
          const data = await loadSurahData(surah.number);
          for (const ayah of data.ayahs) {
            if (!hasText(ayah)) continue;
            entries.push({
              surah: surah.number,
              surahName: surah.name,
              ayah: ayah.ayah_number,
              text: cleanText(ayah.text),
              normalized: normalizeArabic(ayah.text),
            });
          }
        } catch {
          // السورة غير متوفرة بعد — تجاهل
        }
        done += 1;
        if (onProgress) onProgress(Math.round((done / surahs.length) * 100));
      }
    }

    await Promise.all(Array.from({ length: CONCURRENCY }, worker));
    entries.sort((a, b) => a.surah - b.surah || a.ayah - b.ayah);
    searchIndex = entries;
    return entries;
  })();

  const building = searchBuilding;
  building.finally(() => { searchBuilding = null; });
  return building;
}

/**
 * بحث نصي متسامح مع التشكيل.
 * @returns {Array<{surah:number,surahName:string,ayah:number,text:string,normalized:string}>}
 */
export async function searchText(query, limit = 200) {
  const q = normalizeArabic(query);
  if (!q) return [];
  const entries = await buildSearchIndex();
  const out = [];
  for (const entry of entries) {
    if (entry.normalized.includes(q)) {
      out.push(entry);
      if (out.length >= limit) break;
    }
  }
  return out;
}
