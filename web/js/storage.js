/**
 * تخزين محلي (localStorage) للإعدادات والعلامات وآخر موضع وتقدّم «مشواري».
 * كل شيء يبقى على جهاز المستخدم — لا يوجد خادم ولا إرسال بيانات.
 */

const KEYS = {
  settings: "qss.settings.v1",
  bookmarks: "qss.bookmarks.v1",
  last: "qss.lastPosition.v1",
  progress: "qss.progress.v1",
  tracking: "qss.trackingPaused.v1",
};

function read(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw == null ? fallback : JSON.parse(raw);
  } catch {
    return fallback;
  }
}

function write(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

/* ------------------------------ الإعدادات ------------------------------ */

export const DEFAULT_SETTINGS = Object.freeze({
  fontSize: 27,
  theme: "classic",     // classic | light | dark
  design: "mushaf",     // mushaf | modern
  autoScroll: true,
  trackProgress: true,
});

export function loadSettings() {
  const saved = read(KEYS.settings, {});
  return { ...DEFAULT_SETTINGS, ...(saved && typeof saved === "object" ? saved : {}) };
}

export function saveSettings(settings) {
  return write(KEYS.settings, settings);
}

/* ------------------------- العلامات المرجعية ------------------------- */

export function loadBookmarks() {
  const list = read(KEYS.bookmarks, []);
  return Array.isArray(list) ? list.filter(isBookmark) : [];
}

export function saveBookmarks(list) {
  return write(KEYS.bookmarks, list);
}

function isBookmark(b) {
  return b && typeof b === "object"
    && Number.isInteger(b.surah) && b.surah >= 1 && b.surah <= 114
    && Number.isInteger(b.ayah) && b.ayah >= 1;
}

/* ---------------------------- آخر موضع ---------------------------- */

export function loadLastPosition() {
  const pos = read(KEYS.last, null);
  if (!pos || !Number.isInteger(pos.surah) || !Number.isInteger(pos.ayah)) return null;
  return pos;
}

export function saveLastPosition(surah, ayah) {
  return write(KEYS.last, { surah, ayah, at: Date.now() });
}

/* --------------------------- مشوار الحفظ --------------------------- */

export function emptyProgress() {
  return { furthestSurah: 0, furthestAyah: 0, dailyLog: {}, congratulatedJuz: [] };
}

export function loadProgress() {
  const p = read(KEYS.progress, null);
  if (!p || typeof p !== "object") return emptyProgress();
  return {
    furthestSurah: Number.isInteger(p.furthestSurah) ? p.furthestSurah : 0,
    furthestAyah: Number.isInteger(p.furthestAyah) ? p.furthestAyah : 0,
    dailyLog: p.dailyLog && typeof p.dailyLog === "object" ? p.dailyLog : {},
    congratulatedJuz: Array.isArray(p.congratulatedJuz) ? p.congratulatedJuz.filter(Number.isInteger) : [],
  };
}

export function saveProgress(progress) {
  return write(KEYS.progress, progress);
}

/* ------------------------ إيقاف/استئناف المتابعة ------------------------ */

export function isTrackingPaused() {
  return read(KEYS.tracking, true) !== false;
}

export function setTrackingPaused(paused) {
  return write(KEYS.tracking, Boolean(paused));
}
