/**
 * منطق تطبيق الويب: ربط قائمة السور، القراءة، الصوت، البحث، العلامات، والتقدّم.
 */

import {
  buildSearchIndex,
  loadSurahData,
  loadSurahIndex,
  normalizeArabic,
  normalizeArabicWithMap,
  searchText,
} from "./data.js";
import { listHizb, listJuz, listQuarters } from "./divisions.js";
import { computeStats, recordProgress } from "./progress.js";
import {
  emptyProgress,
  isTrackingPaused,
  loadBookmarks,
  loadLastPosition,
  loadProgress,
  loadSettings,
  saveBookmarks,
  saveLastPosition,
  saveProgress,
  saveSettings,
  setTrackingPaused,
} from "./storage.js";
import { Reader, toArabicDigits } from "./reader.js";

const $ = (id) => document.getElementById(id);

const state = {
  surahs: [],
  settings: loadSettings(),
  bookmarks: loadBookmarks(),
  progress: loadProgress(),
  trackingPaused: isTrackingPaused(),
  currentSurah: null,
  data: null,
  divisionMode: "juz",
};

/* ============================ أدوات مساعدة ============================ */

function setStatus(message, kind) {
  const el = $("status");
  if (!el) return;
  el.textContent = message;
  el.classList.toggle("error", kind === "error");
  el.classList.toggle("ok", kind === "ok");
}

let toastTimer = 0;
function toast(message) {
  let el = document.querySelector(".toast");
  if (!el) {
    el = document.createElement("div");
    el.className = "toast";
    document.body.appendChild(el);
  }
  el.textContent = message;
  requestAnimationFrame(() => el.classList.add("show"));
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 2800);
}

function surahName(number) {
  return state.surahs.find((s) => s.number === number)?.name || `السورة ${number}`;
}

function toWesternDigits(value) {
  return String(value).replace(/[٠-٩]/g, (d) => String("٠١٢٣٤٥٦٧٨٩".indexOf(d)));
}

function closeSidebar() {
  document.body.classList.remove("sidebar-open");
}

function openDialog(id) {
  const dialog = document.getElementById(id);
  if (!dialog) return;
  if (typeof dialog.showModal === "function") {
    if (!dialog.open) dialog.showModal();
  } else {
    dialog.setAttribute("open", "");
  }
}

function closeDialog(id) {
  const dialog = document.getElementById(id);
  if (!dialog) return;
  if (typeof dialog.close === "function") dialog.close();
  else dialog.removeAttribute("open");
}

/* ============================ الإعدادات ============================ */

function applySettings() {
  const s = state.settings;

  document.body.classList.remove("theme-classic", "theme-light", "theme-dark", "design-mushaf", "design-modern");
  document.body.classList.add(`theme-${s.theme}`, `design-${s.design}`);
  document.documentElement.style.setProperty("--quran-size", `${s.fontSize}px`);

  const range = $("fontSizeRange");
  if (range) range.value = String(s.fontSize);
  if ($("fontSizeValue")) $("fontSizeValue").textContent = toArabicDigits(s.fontSize);
  if ($("themeSelect")) $("themeSelect").value = s.theme;
  if ($("designSelect")) $("designSelect").value = s.design;
  if ($("autoScrollCheck")) $("autoScrollCheck").checked = s.autoScroll;
  if ($("trackProgressCheck")) $("trackProgressCheck").checked = s.trackProgress;
}

function persistSettings() {
  saveSettings(state.settings);
}

/* ============================ قائمة السور ============================ */

function renderSurahList(filter = "") {
  const listEl = $("surahList");
  if (!listEl) return;

  const raw = filter.trim();
  const query = normalizeArabic(raw);
  const numeric = toWesternDigits(raw).replace(/\D/g, "");

  const fragment = document.createDocumentFragment();
  let shown = 0;

  for (const surah of state.surahs) {
    const nameMatches = !query || normalizeArabic(surah.name).includes(query);
    const numberMatches = numeric && String(surah.number).includes(numeric);
    if (!nameMatches && !numberMatches) continue;

    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.dataset.surah = String(surah.number);
    if (state.currentSurah?.number === surah.number) button.classList.add("active");

    const number = document.createElement("span");
    number.className = "num";
    number.textContent = toArabicDigits(surah.number);

    const name = document.createElement("span");
    name.className = "name";
    name.textContent = surah.name;

    const count = document.createElement("span");
    count.className = "count";
    count.textContent = `${toArabicDigits(surah.ayahs)} آية`;

    button.append(number, name, count);
    button.addEventListener("click", () => openSurah(surah.number));
    item.appendChild(button);
    fragment.appendChild(item);
    shown += 1;
  }

  if (shown === 0) {
    const item = document.createElement("li");
    item.className = "empty";
    item.textContent = "لا توجد سورة مطابقة.";
    fragment.appendChild(item);
  }

  listEl.replaceChildren(fragment);
  $("surahCount").textContent = `${toArabicDigits(shown)} سورة`;
}

function markActiveSurah(number) {
  for (const button of document.querySelectorAll("#surahList button[data-surah]")) {
    button.classList.toggle("active", Number(button.dataset.surah) === number);
  }
}

/* ============================ فتح السور ============================ */

let openToken = 0;

async function openSurah(number, options = {}) {
  const surah = state.surahs.find((s) => s.number === number);
  if (!surah) return;

  const token = ++openToken;
  closeSidebar();
  setStatus(`جارٍ تحميل سورة ${surah.name}...`);

  try {
    const data = await loadSurahData(number);
    if (token !== openToken) return;

    state.currentSurah = surah;
    state.data = data;

    const jumpAyah = Number.isInteger(options.ayah) && options.ayah > 0 ? options.ayah : null;
    reader.load(surah, data, { ayah: jumpAyah, autoplay: Boolean(options.autoplay) });
    if (!jumpAyah) reader.setCurrent(0, { scroll: false, notify: false });

    $("bookmarkBtn").disabled = false;
    markActiveSurah(number);
    document.title = `سورة ${surah.name} — القرآن الكريم`;
    setStatus(`سورة ${surah.name} — ${toArabicDigits(reader.ayahs.length)} آية`, "ok");
  } catch (error) {
    if (token !== openToken) return;

    state.currentSurah = surah;
    state.data = null;

    reader.clear();
    reader.titleEl.textContent = `سورة ${surah.name}`;
    reader.metaEl.textContent = "البيانات غير متوفرة";
    reader.showEmpty(
      `سورة ${surah.name}`,
      error?.status === 404
        ? "ملف هذه السورة غير موجود بعد في quran-data/quran. أضف الملف (بنفس صيغة بقية السور) ثم أعد تحميل الصفحة."
        : "تعذّر تحميل بيانات السورة. تأكد من تقديم الموقع عبر خادم محلي (وليس فتح الملف مباشرة) ومن وجود مجلد quran-data."
    );

    $("bookmarkBtn").disabled = true;
    markActiveSurah(number);
    document.title = `سورة ${surah.name} — القرآن الكريم`;
    setStatus("تعذّر تحميل السورة المطلوبة.", "error");
  }
}

/* ============================ التقدّم ============================ */

function updateProgressUI() {
  if (state.surahs.length === 0) return;

  const stats = computeStats(state.progress, state.surahs);
  const percent = Math.round(stats.percent);

  $("miniProgressLabel").textContent = `تقدّمك: ${toArabicDigits(percent)}٪`;
  $("miniProgressBar").style.width = `${stats.percent}%`;

  if ($("progressDialog")?.open) renderProgress();
}

function renderProgress() {
  const body = $("progressBody");
  if (!body) return;

  const stats = computeStats(state.progress, state.surahs);
  const percent = Math.round(stats.percent);

  const position = state.progress.furthestSurah > 0
    ? `سورة ${surahName(state.progress.furthestSurah)} — الآية ${toArabicDigits(state.progress.furthestAyah)}`
    : "لم تبدأ بعد";

  const remaining = stats.daysRemaining == null
    ? "—"
    : `${toArabicDigits(stats.daysRemaining)} يوم`;

  body.replaceChildren();

  const bar = document.createElement("div");
  bar.className = "big-bar";
  const fill = document.createElement("div");
  fill.style.width = `${stats.percent}%`;
  bar.appendChild(fill);

  const label = document.createElement("div");
  label.className = "hint";
  label.textContent = `أتممت ${toArabicDigits(percent)}٪ من القرآن — ${toArabicDigits(stats.furthest)} من ${toArabicDigits(stats.total)} آية.`;

  const grid = document.createElement("div");
  grid.className = "progress-grid";
  grid.append(
    statCard("الموضع الحالي", position),
    statCard("الجزء الحالي", stats.currentJuz > 0 ? toArabicDigits(stats.currentJuz) : "—"),
    statCard("المعدل اليومي", `${toArabicDigits(Math.round(stats.dailyAverage))} آية`),
    statCard("الأيام المتبقية", remaining),
    statCard("المستوى", stats.levelLabel),
    statCard("الآيات المتبقية", toArabicDigits(stats.remaining))
  );

  const note = document.createElement("p");
  note.className = "note";
  note.textContent = "يُحدَّث التقدّم تلقائيًا أثناء الاستماع/القراءة عند تفعيل «متابعة المتابعة»، ولا يتراجع عند مراجعة آيات سابقة.";

  const reset = document.createElement("button");
  reset.type = "button";
  reset.className = "wide";
  reset.style.marginTop = "14px";
  reset.textContent = "إعادة تعيين تقدّم المشوار";
  reset.addEventListener("click", () => {
    if (!confirm("هل تريد إعادة تعيين تقدّم «مشواري مع القرآن»؟ لن تُحذف العلامات أو آخر موضع.")) return;
    state.progress = emptyProgress();
    saveProgress(state.progress);
    updateProgressUI();
    renderProgress();
    toast("تمت إعادة تعيين التقدّم.");
  });

  body.append(bar, label, grid, note, reset);
}

function statCard(key, value) {
  const card = document.createElement("div");
  card.className = "stat";
  const k = document.createElement("div");
  k.className = "k";
  k.textContent = key;
  const v = document.createElement("div");
  v.className = "v";
  v.textContent = value;
  card.append(k, v);
  return card;
}

function updateTrackingUI() {
  const button = $("trackingBtn");
  if (!button) return;
  button.classList.toggle("paused", state.trackingPaused);
  button.classList.toggle("running", !state.trackingPaused);
  button.setAttribute("aria-pressed", String(!state.trackingPaused));
  button.textContent = state.trackingPaused
    ? "▶ استئناف المتابعة"
    : "⏸ إيقاف المتابعة (تجميد الموضع)";
}

function handleAyahChange(ayahNumber, _index) {
  if (!state.currentSurah) return;
  if (state.trackingPaused) return;

  saveLastPosition(state.currentSurah.number, ayahNumber);

  if (!state.settings.trackProgress) return;

  const result = recordProgress(state.progress, state.currentSurah.number, ayahNumber, state.surahs);
  if (!result.changed) return;

  state.progress = result.progress;
  saveProgress(state.progress);
  updateProgressUI();

  if (result.completedJuz) {
    toast(result.completedJuz === 30
      ? "🎉 ما شاء الله! أتممت القرآن كاملًا."
      : `🎉 أتممت الجزء ${toArabicDigits(result.completedJuz)}.`);
  }
}

function continueReading() {
  const last = loadLastPosition();

  state.trackingPaused = false;
  setTrackingPaused(false);
  updateTrackingUI();

  if (last) openSurah(last.surah, { ayah: last.ayah, autoplay: true });
  else openSurah(1, { autoplay: true });

  toast(last ? "تم استئناف المتابعة من آخر موضع." : "بدأنا من سورة الفاتحة.");
}

/* ============================ العلامات ============================ */

function renderBookmarks() {
  const list = $("bookmarksList");
  if (!list) return;

  list.replaceChildren();

  if (state.bookmarks.length === 0) {
    const item = document.createElement("li");
    item.className = "empty";
    item.textContent = "لا توجد علامات بعد.";
    list.appendChild(item);
    return;
  }

  const sorted = [...state.bookmarks].sort((a, b) => a.surah - b.surah || a.ayah - b.ayah);

  for (const bookmark of sorted) {
    const item = document.createElement("li");
    item.className = "bookmark-item";

    const go = document.createElement("button");
    go.type = "button";
    const title = document.createElement("span");
    title.className = "r-title";
    title.textContent = `سورة ${surahName(bookmark.surah)} — الآية ${toArabicDigits(bookmark.ayah)}`;
    go.appendChild(title);
    go.addEventListener("click", () => {
      closeDialog("bookmarksDialog");
      openSurah(bookmark.surah, { ayah: bookmark.ayah, autoplay: true });
    });

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "del";
    remove.title = "حذف العلامة";
    remove.textContent = "🗑";
    remove.addEventListener("click", () => {
      state.bookmarks = state.bookmarks.filter(
        (b) => !(b.surah === bookmark.surah && b.ayah === bookmark.ayah)
      );
      saveBookmarks(state.bookmarks);
      renderBookmarks();
    });

    item.append(go, remove);
    list.appendChild(item);
  }
}

function addBookmark() {
  if (!state.currentSurah || !state.data) {
    toast("اختر سورة أولًا.");
    return;
  }

  const ayah = reader.currentAyahNumber() ?? 1;
  const exists = state.bookmarks.some(
    (b) => b.surah === state.currentSurah.number && b.ayah === ayah
  );

  if (exists) {
    toast("هذه الآية محفوظة مسبقًا.");
    return;
  }

  state.bookmarks.push({ surah: state.currentSurah.number, ayah, createdAt: Date.now() });
  saveBookmarks(state.bookmarks);
  toast(`تم حفظ علامة: سورة ${state.currentSurah.name} — الآية ${toArabicDigits(ayah)}`);
  renderBookmarks();
}

/* ============================ التصفّح ============================ */

function renderDivisions(mode) {
  state.divisionMode = mode;

  for (const tab of document.querySelectorAll("#navDialog .tab")) {
    tab.classList.toggle("active", tab.dataset.tab === mode);
  }

  const divisions = mode === "hizb" ? listHizb() : mode === "quarter" ? listQuarters() : listJuz();
  const list = $("divisionList");
  const fragment = document.createDocumentFragment();

  for (const division of divisions) {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";

    const title = document.createElement("span");
    title.className = "r-title";
    title.textContent = division.label;

    const sub = document.createElement("span");
    sub.className = "r-sub";
    sub.textContent = `سورة ${surahName(division.surah)} — الآية ${toArabicDigits(division.ayah)}`;

    button.append(title, sub);
    button.addEventListener("click", () => {
      closeDialog("navDialog");
      openSurah(division.surah, { ayah: division.ayah, autoplay: true });
    });

    item.appendChild(button);
    fragment.appendChild(item);
  }

  list.replaceChildren(fragment);
}

/* ============================ البحث ============================ */

let searchToken = 0;

function highlightInto(parent, text, normalizedQuery) {
  const { normalized, map } = normalizeArabicWithMap(text);
  const position = normalizedQuery ? normalized.indexOf(normalizedQuery) : -1;

  if (position < 0) {
    parent.textContent = text;
    return;
  }

  const start = map[position];
  const end = map[position + normalizedQuery.length - 1] + 1;

  const mark = document.createElement("mark");
  mark.textContent = text.slice(start, end);

  parent.append(
    document.createTextNode(text.slice(0, start)),
    mark,
    document.createTextNode(text.slice(end))
  );
}

async function runSearch(rawQuery) {
  const query = rawQuery.trim();
  const normalizedQuery = normalizeArabic(query);

  if (!normalizedQuery) {
    toast("اكتب كلمة أو عبارة للبحث.");
    return;
  }

  const token = ++searchToken;

  $("searchTitle").textContent = `نتائج البحث عن «${query}»`;
  const resultsEl = $("searchResults");
  const progressEl = $("searchProgress");
  resultsEl.replaceChildren();
  progressEl.hidden = false;
  progressEl.textContent = "جارٍ تجهيز فهرس البحث...";
  openDialog("searchDialog");

  try {
    await buildSearchIndex((percent) => {
      if (token !== searchToken) return;
      progressEl.textContent = `جارٍ تجهيز فهرس البحث... ${toArabicDigits(percent)}٪`;
    });

    if (token !== searchToken) return;

    const results = await searchText(query, 200);
    if (token !== searchToken) return;

    if (results.length === 0) {
      progressEl.textContent = "لا توجد نتائج مطابقة.";
      const item = document.createElement("li");
      item.className = "empty";
      item.textContent = `لم يُعثر على «${query}» في السور المتوفرة.`;
      resultsEl.appendChild(item);
      return;
    }

    progressEl.textContent = `عدد النتائج: ${toArabicDigits(results.length)}${results.length >= 200 ? " (أول ٢٠٠ نتيجة)" : ""}`;

    const fragment = document.createDocumentFragment();

    for (const result of results) {
      const item = document.createElement("li");
      const button = document.createElement("button");
      button.type = "button";

      const title = document.createElement("span");
      title.className = "r-title";
      title.textContent = `سورة ${result.surahName} — الآية ${toArabicDigits(result.ayah)}`;

      const text = document.createElement("span");
      text.className = "r-text";
      highlightInto(text, result.text, normalizedQuery);

      button.append(title, text);
      button.addEventListener("click", () => {
        closeDialog("searchDialog");
        openSurah(result.surah, { ayah: result.ayah, autoplay: true });
      });

      item.appendChild(button);
      fragment.appendChild(item);
    }

    resultsEl.replaceChildren(fragment);
  } catch {
    progressEl.textContent = "تعذّر بناء فهرس البحث.";
    setStatus("تعذّر البحث — تحقق من تحميل بيانات القرآن.", "error");
  }
}

/* ============================ ربط الواجهة ============================ */

const reader = new Reader({
  ayahList: $("ayahList"),
  title: $("surahTitle"),
  meta: $("surahMeta"),
  empty: $("emptyState"),
  tafsirPanel: $("tafsirPanel"),
  tafsirContent: $("tafsirContent"),
  audio: $("audio"),
  controls: {
    playBtn: $("playBtn"),
    stopBtn: $("stopBtn"),
    prevBtn: $("prevBtn"),
    nextBtn: $("nextBtn"),
    repeatBtn: $("repeatBtn"),
    slider: $("progressSlider"),
    timeLabel: $("timeLabel"),
  },
  onStatus: setStatus,
  onAyahChange: handleAyahChange,
  getSettings: () => state.settings,
});

function bindEvents() {
  // قائمة السور
  $("surahFilter").addEventListener("input", (event) => renderSurahList(event.target.value));

  // البحث
  $("searchForm").addEventListener("submit", (event) => {
    event.preventDefault();
    runSearch($("searchInput").value);
  });

  // أزرار فتح النوافذ
  for (const trigger of document.querySelectorAll("[data-open]")) {
    trigger.addEventListener("click", () => {
      closeSidebar();
      const id = trigger.dataset.open;
      if (id === "bookmarksDialog") renderBookmarks();
      else if (id === "progressDialog") renderProgress();
      else if (id === "navDialog") renderDivisions(state.divisionMode);
      openDialog(id);
    });
  }

  $("navOpenBtn")?.addEventListener("click", () => {
    renderDivisions(state.divisionMode);
    openDialog("navDialog");
  });
  $("progressOpenBtn")?.addEventListener("click", () => {
    renderProgress();
    openDialog("progressDialog");
  });

  // إغلاق النوافذ
  for (const closer of document.querySelectorAll("[data-close]")) {
    closer.addEventListener("click", () => closer.closest("dialog")?.close());
  }

  // تبويبات التصفّح
  for (const tab of document.querySelectorAll("#navDialog .tab")) {
    tab.addEventListener("click", () => renderDivisions(tab.dataset.tab));
  }

  // المتابعة والعلامات
  $("continueBtn").addEventListener("click", continueReading);
  $("trackingBtn").addEventListener("click", () => {
    state.trackingPaused = !state.trackingPaused;
    setTrackingPaused(state.trackingPaused);
    updateTrackingUI();
    toast(state.trackingPaused ? "تم تجميد المتابعة — لن يُحدَّث الموضع." : "تم استئناف المتابعة.");
  });
  $("bookmarkBtn").addEventListener("click", addBookmark);

  // الإعدادات
  $("fontSizeRange").addEventListener("input", (event) => {
    state.settings.fontSize = Number(event.target.value);
    $("fontSizeValue").textContent = toArabicDigits(event.target.value);
    applySettings();
    persistSettings();
  });
  $("themeSelect").addEventListener("change", (event) => {
    state.settings.theme = event.target.value;
    applySettings();
    persistSettings();
  });
  $("designSelect").addEventListener("change", (event) => {
    state.settings.design = event.target.value;
    applySettings();
    persistSettings();
  });
  $("autoScrollCheck").addEventListener("change", (event) => {
    state.settings.autoScroll = event.target.checked;
    persistSettings();
  });
  $("trackProgressCheck").addEventListener("change", (event) => {
    state.settings.trackProgress = event.target.checked;
    persistSettings();
  });

  // السرعة
  $("speedSelect").addEventListener("change", (event) => reader.setRate(event.target.value));

  // القائمة الجانبية على الجوال
  $("menuBtn").addEventListener("click", (event) => {
    event.stopPropagation();
    document.body.classList.toggle("sidebar-open");
  });
  document.addEventListener("click", (event) => {
    if (!document.body.classList.contains("sidebar-open")) return;
    if (event.target.closest("#sidebar") || event.target.closest("#menuBtn")) return;
    closeSidebar();
  });

  // اختصارات لوحة المفاتيح
  document.addEventListener("keydown", (event) => {
    const tag = (event.target.tagName || "").toLowerCase();
    if (tag === "input" || tag === "select" || tag === "textarea") return;
    if (document.querySelector("dialog[open]")) return;

    if (event.code === "Space") {
      event.preventDefault();
      reader.toggle();
    } else if (event.key === "ArrowLeft") {
      event.preventDefault();
      reader.step(1);
    } else if (event.key === "ArrowRight") {
      event.preventDefault();
      reader.step(-1);
    }
  });

  // إغلاق القائمة الجانبية عند تغيير حجم الشاشة
  window.addEventListener("resize", () => {
    if (window.innerWidth > 900) closeSidebar();
  });
}

/* ============================ التشغيل ============================ */

async function init() {
  applySettings();
  updateTrackingUI();
  bindEvents();

  reader.showEmpty(
    "مرحبًا بك",
    "اختر سورة من القائمة، أو اضغط «متابعة من آخر موضع» للاستئناف من حيث توقفت."
  );

  try {
    state.surahs = await loadSurahIndex();
  } catch {
    reader.showEmpty(
      "تعذّر تحميل البيانات",
      "لم يتم العثور على quran-data/surahs.json. شغّل الموقع عبر خادم محلي من جذر المشروع، مثل: python -m http.server 8000 ثم افتح http://localhost:8000/web/"
    );
    setStatus("تعذّر تحميل فهرس السور — راجع تعليمات التشغيل في web/README.md.", "error");
    return;
  }

  renderSurahList();
  updateProgressUI();

  const last = loadLastPosition();
  if (last) {
    await openSurah(last.surah, { ayah: last.ayah });
  } else {
    await openSurah(1);
  }

  setStatus("جاهز — اختر سورة أو تابع من آخر موضع.", "ok");
}

init();
