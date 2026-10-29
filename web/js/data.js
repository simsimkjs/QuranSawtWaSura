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

/* ------------------------ فهرس توفّر البيانات ------------------------ */

let manifestPromise = null;

/**
 * يقرأ quran-data/manifest.json (فهرس توفّر النص/الصوت/التفسير).
 * يعيد null بصمت إذا لم يكن الملف موجودًا، للتوافق مع بيانات قديمة.
 */
export function loadManifest() {
  if (!manifestPromise) {
    manifestPromise = fetchJson(dataUrl("manifest.json"))
      .then((manifest) => (manifest && Array.isArray(manifest.surahs) ? manifest : null))
      .catch((error) => {
        // اسمح بإعادة المحاولة عند خطأ شبكة/خادم، ولا تكررها عند 404
        if (!(error instanceof HttpError) || error.status !== 404) manifestPromise = null;
        return null;
      });
  }
  return manifestPromise;
}

function findManifestSurah(manifest, number) {
  return (manifest?.surahs || []).find((item) => item?.number === number) || null;
}

const surahCache = new Map();

/** بيانات سورة واحدة من quran-data/quran/NNN.json (مع تخزين مؤقت). */
export async function loadSurahData(number) {
  if (surahCache.has(number)) return surahCache.get(number);

  // تجنّب طلب ملف سورة نعرف مسبقًا أنه غير موجود (يمنع ضجيج 404)
  const manifest = await loadManifest();
  const info = findManifestSurah(manifest, number);
  if (info && info.text === false) {
    throw new HttpError(404, dataUrl(`quran/${pad3(number)}.json`));
  }

  // قد يكون طلب آخر بدأ أثناء انتظار manifest
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
export async function loadTafsir(number) {
  if (tafsirCache.has(number)) return tafsirCache.get(number);

  const manifest = await loadManifest();
  const info = findManifestSurah(manifest, number);
  if (info && info.tafsir === false) {
    const empty = Promise.resolve(null);
    tafsirCache.set(number, empty);
    return empty;
  }

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
let searchProgress = null;

/**
 * يبني فهرس البحث. يفضّل quran-data/search-index.json (مولَّد مسبقًا)،
 * ويعود إلى تحميل ملفات السور عند غيابه أو تقادمه.
 * السور غير المتوفرة تُتجاهل بصمت.
 * @param {(percent:number)=>void} [onProgress]
 */
export function buildSearchIndex(onProgress) {
  if (typeof onProgress === "function") searchProgress = onProgress;
  if (searchIndex) return Promise.resolve(searchIndex);
  if (searchBuilding) return searchBuilding;

  searchBuilding = (async () => {
    const surahs = await loadSurahIndex();
    const byNumber = new Map(surahs.map((surah) => [surah.number, surah]));
    const manifest = await loadManifest();

    const loadPrebuilt = async () => {
      try {
        const raw = await fetchJson(dataUrl("search-index.json"));
        if (!raw || !Array.isArray(raw.entries)) return null;

        // إن وُجد manifest وبصمة مختلفة، فالفهرس قديم (أُضيفت/عُدّلت سور)
        if (manifest && raw.fingerprint && raw.fingerprint !== manifest.fingerprint) {
          console.warn("فهرس البحث قديم؛ سيُعاد بناؤه من ملفات السور.");
          return null;
        }

        const entries = [];
        for (const row of raw.entries) {
          if (!Array.isArray(row) || row.length < 3) continue;
          const surahNumber = Number(row[0]);
          const ayahNumber = Number(row[1]);
          const text = cleanText(row[2]);
          const surah = byNumber.get(surahNumber);
          if (!surah || !Number.isInteger(ayahNumber) || ayahNumber <= 0 || !text) continue;
          entries.push({
            surah: surah.number,
            surahName: surah.name,
            ayah: ayahNumber,
            text,
            normalized: normalizeArabic(text),
          });
        }

        if (entries.length === 0) return null;
        entries.sort((a, b) => a.surah - b.surah || a.ayah - b.ayah);
        return entries;
      } catch (error) {
        if (!(error instanceof HttpError) || error.status !== 404) {
          console.warn("تعذّر تحميل فهرس البحث المسبق؛ سيُبنى من ملفات السور.", error);
        }
        return null;
      }
    };

    const prebuilt = await loadPrebuilt();
    if (prebuilt) {
      searchIndex = prebuilt;
      if (searchProgress) searchProgress(100);
      return prebuilt;
    }

    // بناء احتياطي: فقط السور التي يعرف manifest أن نصها متوفر
    const candidates = manifest
      ? surahs.filter((surah) => {
          const info = findManifestSurah(manifest, surah.number);
          return !info || info.text !== false;
        })
      : surahs;

    const entries = [];
    let done = 0;
    let cursor = 0;
    const total = Math.max(1, candidates.length);
    const CONCURRENCY = 6;

    async function worker() {
      while (cursor < candidates.length) {
        const surah = candidates[cursor++];
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
        if (searchProgress) {
          searchProgress(Math.round((done / total) * 100));
        }
      }
    }

    await Promise.all(Array.from({ length: Math.min(CONCURRENCY, total) }, worker));
    entries.sort((a, b) => a.surah - b.surah || a.ayah - b.ayah);
    searchIndex = entries;
    return entries;
  })();

  const building = searchBuilding;
  building.finally(() => {
    searchBuilding = null;
    searchProgress = null;
  });
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
