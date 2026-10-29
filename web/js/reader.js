/**
 * لوحة القراءة: عرض آيات السورة، مزامنة التظليل مع الصوت، والتحكم بالتشغيل.
 * كل الآيات تُرسم مرة واحدة (المتصفح يتعامل مع مئات العناصر بكفاءة)،
 * والتظليل ينتقل حسب start_ms لكل آية.
 */

import { audioUrl, cleanText, hasText, loadTafsir } from "./data.js";

const AR_DIGITS = ["٠", "١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩"];

export function toArabicDigits(value) {
  return String(value).replace(/[0-9]/g, (d) => AR_DIGITS[Number(d)]);
}

export function formatTime(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) seconds = 0;
  const total = Math.floor(seconds);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

export class Reader {
  constructor(options) {
    this.el = options.ayahList;
    this.titleEl = options.title;
    this.metaEl = options.meta;
    this.emptyEl = options.empty;
    this.tafsirPanel = options.tafsirPanel;
    this.tafsirContent = options.tafsirContent;
    this.audio = options.audio;
    this.controls = options.controls;

    this.onStatus = options.onStatus || (() => {});
    this.onAyahChange = options.onAyahChange || (() => {});
    this.getSettings = options.getSettings || (() => ({}));

    this.surah = null;
    this.data = null;
    this.ayahs = [];
    this.currentIndex = -1;
    this.repeatIndex = -1;
    this.tafsir = null;
    this.dragging = false;
    this.rafId = 0;
    this.audioOk = false;
    this.loadToken = 0;
    this.pending = null;
    this._suppressAudioError = false;

    this._bindAudio();
    this._bindControls();
    this._bindAyahClicks();
  }

  /* ------------------------------ ربط الأحداث ------------------------------ */

  _bindAyahClicks() {
    this.el.addEventListener("click", (event) => {
      const node = event.target.closest(".ayah");
      if (!node || !this.el.contains(node)) return;
      const index = Number(node.dataset.index);
      if (!Number.isInteger(index)) return;
      this.activateAyah(index, { scroll: false, seek: true, showTafsir: true });
    });
  }

  _bindControls() {
    const c = this.controls;
    c.playBtn?.addEventListener("click", () => this.toggle());
    c.stopBtn?.addEventListener("click", () => this.stop());
    c.prevBtn?.addEventListener("click", () => this.step(-1));
    c.nextBtn?.addEventListener("click", () => this.step(+1));
    c.repeatBtn?.addEventListener("click", () => this.setRepeat(c.repeatBtn.getAttribute("aria-pressed") !== "true"));

    c.slider?.addEventListener("input", () => {
      this.dragging = true;
      this._setTimeLabel(Number(c.slider.value));
    });
    c.slider?.addEventListener("change", () => {
      this.dragging = false;
      const t = Number(c.slider.value);
      if (Number.isFinite(t)) this._seekSeconds(t);
    });
  }

  _bindAudio() {
    const audio = this.audio;

    audio.addEventListener("loadedmetadata", () => {
      this.audioOk = Number.isFinite(audio.duration) && audio.duration > 0;
      this._updateControlsEnabled();
      if (this.controls.slider) {
        this.controls.slider.max = String(this.audioOk ? audio.duration : 0);
        this.controls.slider.value = "0";
      }
      this._setTimeLabel(0, this.audioOk ? audio.duration : 0);

      // تنفيذ الانتقال/التشغيل المؤجّل حتى تصبح مدة الصوت معروفة
      this._applyPending();
    });

    // بعض الخوادم لا تُعلن المدة إلا بعد وصول بيانات إضافية
    audio.addEventListener("durationchange", () => {
      if (this.audioOk) return;
      if (!Number.isFinite(audio.duration) || audio.duration <= 0) return;
      this.audioOk = true;
      this._updateControlsEnabled();
      if (this.controls.slider) this.controls.slider.max = String(audio.duration);
      this._setTimeLabel(audio.currentTime, audio.duration);
      this._applyPending();
    });

    audio.addEventListener("play", () => {
      this._updatePlayButton();
      this._startLoop();
    });
    audio.addEventListener("pause", () => {
      this._updatePlayButton();
      this._stopLoop();
      this._syncOnce();
    });
    audio.addEventListener("ended", () => {
      this._stopLoop();
      this._updatePlayButton();
    });
    audio.addEventListener("error", () => {
      if (this._suppressAudioError) return;
      this.audioOk = false;
      this.pending = null;
      this._updateControlsEnabled();
      const src = audio.currentSrc || audio.src || "";
      if (src) {
        this.onStatus("تعذّر تشغيل التلاوة — قد يكون ملف الصوت غير متوفر في quran-data/audio.", "error");
      }
    });
  }

  /* ------------------------------ تحميل سورة ------------------------------ */

  /**
   * @param {object} surah عنصر من surahs.json
   * @param {object} data محتوى quran/NNN.json
   * @param {{ayah?: number, autoplay?: boolean, hasAudio?: boolean}} [opts]
   */
  load(surah, data, opts = {}) {
    const token = ++this.loadToken;

    this._stopLoop();
    this.audio.pause();

    this.surah = surah;
    this.data = data;
    this.ayahs = (data.ayahs || []).filter(hasText);
    this.currentIndex = -1;
    this.repeatIndex = -1;
    this.tafsir = null;
    this.audioOk = false;
    this.pending = null;
    this.controls.repeatBtn?.setAttribute("aria-pressed", "false");
    this.controls.repeatBtn?.classList.remove("active");

    const name = data.surah_name || surah.name || `سورة ${surah.number}`;
    this.titleEl.textContent = `سورة ${name}`;
    this.metaEl.textContent = `${toArabicDigits(this.ayahs.length)} آية`;

    // نحدّد الوجهة قبل تحميل الصوت حتى لا يضيع الحدث إن وصل loadedmetadata مبكرًا
    this.pending = {
      ayah: Number.isInteger(opts.ayah) && opts.ayah > 0 ? opts.ayah : null,
      autoplay: Boolean(opts.autoplay),
    };

    this._render();

    // ملف الصوت (نتجنّب طلبه إن كان manifest يعرف أنه غير متوفر)
    if (opts.hasAudio === false) {
      this._suppressAudioError = true;
      this.audio.removeAttribute("src");
      this.audio.load();
      setTimeout(() => { this._suppressAudioError = false; }, 0);
      this.audioOk = false;
    } else {
      const src = audioUrl(data.audio_file_name, surah.number);
      this.audio.src = src;
      this.audio.load();
    }
    if (this.controls.slider) {
      this.controls.slider.value = "0";
      this.controls.slider.max = "1000";
    }
    this._setTimeLabel(0, 0);
    this._updateControlsEnabled();

    // التفسير (اختياري)
    loadTafsir(surah.number).then((tafsir) => {
      if (token !== this.loadToken) return;
      this.tafsir = tafsir;
      this._updateTafsirTitle();
      this.tafsirContent.textContent = "اختر آية لعرض تفسيرها.";
      this.tafsirPanel.open = false;
    });

    if (this.pending.ayah) {
      // نُبرز الآية فورًا، ونؤجّل التمرير/التشغيل حتى تُحمَّل مدة الصوت
      this.jumpToAyahNumber(this.pending.ayah, { play: false, center: true });
    } else {
      this._updatePlayButton();
    }
  }

  /** ينفّذ الانتقال/التشغيل المؤجّل بعد معرفة مدة الصوت. */
  _applyPending() {
    if (!this.audioOk || !this.pending) return;

    const pending = this.pending;
    this.pending = null;

    if (Number.isInteger(pending.ayah) && pending.ayah > 0) {
      const index = this._indexForAyahNumber(pending.ayah);
      this.setCurrent(index, { scroll: true, smooth: true, center: true });
      this._seekSeconds(this.ayahs[index].start_ms / 1000);
    }

    if (pending.autoplay) this.play();
  }

  _render() {
    const fragment = document.createDocumentFragment();

    for (let i = 0; i < this.ayahs.length; i++) {
      const ayah = this.ayahs[i];
      const row = document.createElement("div");
      row.className = "ayah";
      row.dataset.index = String(i);
      row.dataset.ayah = String(ayah.ayah_number);

      const text = document.createElement("span");
      text.className = "ayah-text";
      text.textContent = `${cleanText(ayah.text)} `;

      const number = document.createElement("span");
      number.className = "ayah-num";
      number.textContent = toArabicDigits(ayah.ayah_number);

      row.append(text, number);
      fragment.appendChild(row);
    }

    this.el.replaceChildren(fragment);
    this.emptyEl.hidden = true;
  }

  /** يعرض رسالة في مساحة القراءة بدل الآيات (لغياب البيانات أو الصوت). */
  showEmpty(title, message) {
    this.el.replaceChildren();
    this.emptyEl.replaceChildren();
    const strong = document.createElement("strong");
    strong.textContent = title;
    const p = document.createElement("div");
    p.textContent = message;
    this.emptyEl.append(strong, p);
    this.emptyEl.hidden = false;
  }

  /** يوقف الصوت ويفرّغ لوحة القراءة (يُستخدم عند غياب بيانات السورة). */
  clear() {
    this.loadToken += 1; // يُلغي أي تفسير/صوت قيد التحميل من السورة السابقة
    this._stopLoop();
    this._suppressAudioError = true;
    this.audio.pause();
    this.audio.removeAttribute("src");
    this.audio.load();
    setTimeout(() => { this._suppressAudioError = false; }, 0);

    this.surah = null;
    this.data = null;
    this.ayahs = [];
    this.currentIndex = -1;
    this.repeatIndex = -1;
    this.tafsir = null;
    this.audioOk = false;

    this.el.replaceChildren();
    this._updateTafsirTitle();
    if (this.tafsirContent) this.tafsirContent.textContent = "اختر آية لعرض تفسيرها.";
    if (this.tafsirPanel) this.tafsirPanel.open = false;
    this.controls.repeatBtn?.setAttribute("aria-pressed", "false");
    this.controls.repeatBtn?.classList.remove("active");
    if (this.controls.slider) {
      this.controls.slider.value = "0";
      this.controls.slider.max = "1000";
    }
    this._setTimeLabel(0, 0);
    this._updateControlsEnabled();
  }

  /* --------------------------- التظليل والانتقال --------------------------- */

  setCurrent(index, { scroll = false, smooth = true, center = false, notify = true } = {}) {
    if (index < 0 || index >= this.ayahs.length) return;

    const previous = this.el.querySelector(".ayah.current");
    if (previous) previous.classList.remove("current");

    const node = this.el.querySelector(`.ayah[data-index="${index}"]`);
    if (!node) return;
    node.classList.add("current");

    const changed = index !== this.currentIndex;
    this.currentIndex = index;

    if (scroll) {
      try {
        node.scrollIntoView({ block: center ? "center" : "nearest", behavior: smooth ? "smooth" : "auto" });
      } catch {
        node.scrollIntoView();
      }
    }

    if (changed && notify) {
      this.onAyahChange(this.ayahs[index].ayah_number, index);
    }
  }

  /** نقرة المستخدم على آية: إبراز + انتقال للصوت + عرض التفسير. */
  activateAyah(index, { scroll = false, seek = true, showTafsir = true } = {}) {
    if (index < 0 || index >= this.ayahs.length) return;
    this.setCurrent(index, { scroll, smooth: true });
    if (seek) this._seekSeconds(this.ayahs[index].start_ms / 1000);
    if (showTafsir) this._showTafsir(this.ayahs[index].ayah_number);
  }

  jumpToAyahNumber(ayahNumber, { play = false, center = true } = {}) {
    if (this.ayahs.length === 0) return;
    const index = this._indexForAyahNumber(ayahNumber);

    this.setCurrent(index, { scroll: true, smooth: true, center });
    this._seekSeconds(this.ayahs[index].start_ms / 1000);
    if (play) this.play();
    return index;
  }

  _indexForAyahNumber(ayahNumber) {
    let index = this.ayahs.findIndex((a) => a.ayah_number === ayahNumber);
    if (index < 0) {
      index = this.ayahs.findIndex((a) => a.ayah_number >= ayahNumber);
      if (index < 0) index = this.ayahs.length - 1;
    }
    return Math.max(0, index);
  }

  /* ------------------------------ التشغيل ------------------------------ */

  play() {
    if (!this.data || this.ayahs.length === 0) return;
    if (!this.audioOk) {
      this.onStatus("ملف الصوت غير متوفر لهذه السورة.", "error");
      return;
    }
    const promise = this.audio.play();
    if (promise && typeof promise.catch === "function") {
      promise.catch((error) => {
        if (error && error.name === "AbortError") return; // أُلغي بسبب تحميل جديد
        if (error && error.name === "NotAllowedError") {
          this.onStatus("لم يسمح المتصفح بالتشغيل التلقائي — اضغط زر «تشغيل».", "error");
          return;
        }
        this.onStatus("تعذّر تشغيل التلاوة — قد يكون ملف الصوت غير متوفر في quran-data/audio.", "error");
      });
    }
  }

  pause() {
    this.audio.pause();
  }

  toggle() {
    if (this.audio.paused) this.play();
    else this.pause();
  }

  stop() {
    this.audio.pause();
    this._seekSeconds(0);
    this._updatePlayButton();
    this._syncOnce();
  }

  step(delta) {
    if (this.ayahs.length === 0) return;
    const from = this.currentIndex >= 0 ? this.currentIndex : 0;
    const next = Math.max(0, Math.min(this.ayahs.length - 1, from + delta));
    this.setCurrent(next, { scroll: true, smooth: true, center: false });
    this._seekSeconds(this.ayahs[next].start_ms / 1000);
    if (this.audioOk && this.audio.paused) this.play();
  }

  setRate(rate) {
    const value = Number(rate);
    if (Number.isFinite(value) && value > 0) this.audio.playbackRate = value;
  }

  setRepeat(on) {
    const enabled = Boolean(on);
    this.repeatIndex = enabled && this.currentIndex >= 0 ? this.currentIndex : -1;
    this.controls.repeatBtn?.setAttribute("aria-pressed", String(enabled));
    this.controls.repeatBtn?.classList.toggle("active", enabled);
    if (enabled && this.repeatIndex < 0) {
      this.onStatus("اختر آية أولًا ثم فعّل تكرار الآية.", "error");
    }
  }

  /* ------------------------------ المزامنة ------------------------------ */

  _startLoop() {
    if (this.rafId) return;
    const tick = () => {
      this._syncOnce();
      this.rafId = requestAnimationFrame(tick);
    };
    this.rafId = requestAnimationFrame(tick);
  }

  _stopLoop() {
    if (this.rafId) {
      cancelAnimationFrame(this.rafId);
      this.rafId = 0;
    }
  }

  _syncOnce() {
    if (!this.data || !this.audioOk) return;
    const seconds = this.audio.currentTime;
    const ms = seconds * 1000;

    if (!this.dragging) {
      const slider = this.controls.slider;
      if (slider) slider.value = String(seconds);
      this._setTimeLabel(seconds, this.audio.duration);
    }

    // تكرار الآية المحددة
    if (this.repeatIndex >= 0 && !this.audio.paused) {
      const endMs = this._endMs(this.repeatIndex);
      if (ms >= endMs) {
        this._seekSeconds(this.ayahs[this.repeatIndex].start_ms / 1000);
        return;
      }
    }

    const index = this._indexForTime(ms);
    if (index !== this.currentIndex) {
      const settings = this.getSettings() || {};
      this.setCurrent(index, { scroll: settings.autoScroll !== false, smooth: true });
    }
  }

  _indexForTime(ms) {
    const list = this.ayahs;
    let lo = 0;
    let hi = list.length - 1;
    let found = 0;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (list[mid].start_ms <= ms) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return found;
  }

  _endMs(index) {
    if (index + 1 < this.ayahs.length) return this.ayahs[index + 1].start_ms;
    return this.audioOk ? this.audio.duration * 1000 : Infinity;
  }

  _seekSeconds(seconds) {
    const max = this.audio.duration;
    if (!Number.isFinite(max) || max <= 0) return;
    if (!Number.isFinite(seconds)) return;

    const clamped = Math.max(0, Math.min(seconds, max));
    this.audio.currentTime = clamped;
    this._setTimeLabel(clamped, max);
    if (this.controls.slider) this.controls.slider.value = String(clamped);
  }

  /* ------------------------------ واجهة مساعدة ------------------------------ */

  _updatePlayButton() {
    const btn = this.controls.playBtn;
    if (!btn) return;
    btn.textContent = this.audio.paused ? "▶ تشغيل" : "⏸ إيقاف مؤقت";
  }

  _updateControlsEnabled() {
    const c = this.controls;
    if (c.playBtn) c.playBtn.disabled = !this.audioOk;
    if (c.stopBtn) c.stopBtn.disabled = !this.audioOk;
    if (c.repeatBtn) c.repeatBtn.disabled = !this.audioOk;
    if (c.slider) c.slider.disabled = !this.audioOk;
    // التنقل بين الآيات يظل متاحًا حتى بدون صوت
    if (c.prevBtn) c.prevBtn.disabled = this.ayahs.length === 0;
    if (c.nextBtn) c.nextBtn.disabled = this.ayahs.length === 0;
    this._updatePlayButton();
  }

  _setTimeLabel(seconds, duration) {
    if (!this.controls.timeLabel) return;
    this.controls.timeLabel.textContent = `${formatTime(seconds)} / ${formatTime(duration)}`;
  }

  _updateTafsirTitle() {
    const summary = this.tafsirPanel?.querySelector("summary");
    if (!summary) return;
    summary.textContent = this.tafsir ? `التفسير${this.tafsir.source ? ` (${this.tafsir.source})` : ""}` : "التفسير (غير متاح لهذه السورة بعد)";
  }

  _showTafsir(ayahNumber) {
    this._updateTafsirTitle();
    if (this.tafsir && this.tafsir.byAyah.has(ayahNumber)) {
      this.tafsirContent.textContent = this.tafsir.byAyah.get(ayahNumber);
    } else if (this.tafsir) {
      this.tafsirContent.textContent = "لا يتوفر تفسير لهذه الآية في الملف الحالي.";
    } else {
      this.tafsirContent.textContent = "لا يتوفر تفسير محمّل لهذه السورة حتى الآن.";
    }
    this.tafsirPanel.open = true;
  }

  /** رقم الآية الحالية (أو null). */
  currentAyahNumber() {
    return this.currentIndex >= 0 ? this.ayahs[this.currentIndex].ayah_number : null;
  }

  destroy() {
    this._stopLoop();
    this.audio.pause();
  }
}
