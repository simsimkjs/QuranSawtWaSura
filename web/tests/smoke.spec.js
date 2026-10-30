const { test, expect } = require("@playwright/test");
const fs = require("fs");
const path = require("path");

const AUDIO_2 = path.resolve(__dirname, "../../quran-data/audio/002.mp3");
const MISSING_MESSAGE = "ملف هذه السورة غير موجود";

async function gotoApp(page) {
  await page.goto("/web/?test=1");
  await expect(page.locator("#surahList button[data-surah]")).toHaveCount(114);
}

async function openSurah(page, number, name) {
  await page.locator(`#surahList button[data-surah="${number}"]`).click();
  await expect(page.locator("#surahTitle")).toContainText(name);
  await expect(page.locator("#ayahList .ayah").first()).toBeVisible();
}

/** يقرأ أول سورة بلا نص من manifest (بدل الاعتماد على رقم ثابت). */
async function firstMissingSurah(page) {
  return page.evaluate(async () => {
    const response = await fetch("/quran-data/manifest.json", { cache: "no-cache" });
    if (!response.ok) return null;
    const manifest = await response.json();
    return manifest?.surahs?.find((surah) => surah.text === false)?.number ?? null;
  });
}

test("يعرض نص الآيات ولا تغطيه طبقة الحالة الفارغة", async ({ page }) => {
  await gotoApp(page);
  await openSurah(page, 2, "البقرة");
  await expect(page.locator("#ayahList .ayah")).toHaveCount(286);

  const empty = page.locator("#emptyState");
  await expect(empty).toBeHidden();
  expect(await empty.evaluate((element) => getComputedStyle(element).display)).toBe("none");

  const firstText = await page.locator('#ayahList .ayah[data-ayah="1"] .ayah-text').innerText();
  expect(firstText.trim().length).toBeGreaterThan(0);
  // قد يكون النص العادي (Unicode) أو نص QCF (Arabic Presentation Forms).
  expect(firstText).toMatch(/[\u0600-\u06FF\uFB50-\uFEFF]/);

  // التأكد من أن أول آية هي العنصر الظاهر فعلًا في نقطة النقر (لا تغطيها طبقة أخرى)
  const hitsAyah = await page.evaluate(() => {
    const ayah = document.querySelector("#ayahList .ayah");
    if (!ayah) return false;
    const rect = ayah.getBoundingClientRect();
    const target = document.elementFromPoint(rect.left + 12, rect.top + rect.height / 2);
    return target?.closest(".ayah") === ayah;
  });
  expect(hitsAyah).toBe(true);
});

test("يُحمّل خط quran.com العثماني الأساسي", async ({ page }) => {
  await gotoApp(page);
  await openSurah(page, 2, "البقرة");

  const fontState = await page.evaluate(async () => {
    await document.fonts.ready;
    return {
      checked: document.fonts.check('16px "UthmanicHafs"'),
      loaded: [...document.fonts].some(
        (font) => font.family === "UthmanicHafs" && font.status === "loaded"
      ),
    };
  });

  expect(fontState.checked).toBe(true);
  expect(fontState.loaded).toBe(true);
});

test("يُطبّق خط QCF لصفحة quran.com على الآيات", async ({ page }) => {
  const font404 = [];
  page.on("response", (response) => {
    if (response.url().includes("/web/assets/fonts/qcf/") && response.status() >= 400) {
      font404.push(response.url());
    }
  });

  await gotoApp(page);
  await openSurah(page, 2, "البقرة");

  const first = page.locator('#ayahList .ayah[data-ayah="1"] .ayah-text.qcf-text');
  await expect(first).toBeVisible({ timeout: 30_000 });

  const state = await first.evaluate((element) => ({
    family: getComputedStyle(element).fontFamily,
    text: element.textContent,
    fontSize: parseFloat(getComputedStyle(element).fontSize),
  }));

  expect(state.family).toContain("QCF-P2");
  expect(state.text.trim().length).toBeGreaterThan(0);
  expect(state.fontSize).toBeGreaterThan(27);
  expect(font404).toEqual([]);

  // لا تتجاوز أسطر QCF حدود الآية التالية (خصوصًا مع الحروف ذات النزول).
  const overlaps = await page.evaluate(() => {
    const rows = [...document.querySelectorAll("#ayahList .ayah")].slice(0, 12);
    const boxes = rows.map((row) => {
      const range = document.createRange();
      range.selectNodeContents(row.querySelector(".ayah-text"));
      const rect = range.getBoundingClientRect();
      return { top: rect.top, bottom: rect.bottom };
    });
    return boxes.some(
      (box, index) => index + 1 < boxes.length && box.bottom > boxes[index + 1].top + 0.5
    );
  });
  expect(overlaps).toBe(false);

  // التحميل تدريجي: لا تُجلب كل خطوط السورة (48 صفحة) دفعة واحدة.
  const loadedAtStart = await page.evaluate(
    () => [...document.fonts].filter((font) => font.family.startsWith("QCF-P") && font.status === "loaded").length
  );
  expect(loadedAtStart).toBeLessThan(20);

  // الانتقال إلى آية متأخرة يحمّل خط صفحتها عند الحاجة.
  const late = page.locator('#ayahList .ayah[data-ayah="282"] .ayah-text');
  await late.scrollIntoViewIfNeeded();
  await expect(late).toHaveClass(/qcf-text/, { timeout: 30_000 });
  expect(await late.evaluate((element) => getComputedStyle(element).fontFamily)).toContain("QCF-P");
});

test("manifest يضيف شارات التوفر ويسمح بتصفية السور المتوفرة", async ({ page }) => {
  await gotoApp(page);

  await expect(page.locator("#surahCount")).toContainText("متوفرة");
  await expect(page.locator('#surahList button[data-surah="1"] .avail')).toHaveClass(/\bok\b/);

  const missing = await firstMissingSurah(page);
  expect(missing).toBeTruthy();
  await expect(page.locator(`#surahList button[data-surah="${missing}"] .avail`)).toHaveClass(/\boff\b/);

  await page.locator("#onlyAvailable").check();
  await expect(page.locator(`#surahList button[data-surah="${missing}"]`)).toHaveCount(0);
  await expect(page.locator('#surahList button[data-surah="1"]')).toHaveCount(1);
});

test("السورة غير المتوفرة تعرض رسالة واضحة دون طلب 404", async ({ page }) => {
  await gotoApp(page);
  const missing = await firstMissingSurah(page);
  test.skip(!missing, "كل السور متوفرة في هذه البيانات");

  const requested = [];
  page.on("request", (request) => {
    const file = String(missing).padStart(3, "0");
    if (request.url().includes(`/quran-data/quran/${file}.json`)) requested.push(request.url());
  });

  await page.locator(`#surahList button[data-surah="${missing}"]`).click();
  await expect(page.locator("#emptyState")).toBeVisible();
  await expect(page.locator("#emptyState")).toContainText(MISSING_MESSAGE);
  await expect(page.locator("#ayahList .ayah")).toHaveCount(0);
  expect(requested).toEqual([]);
});

test("البحث يستخدم الفهرس المسبق دون طلبات 404", async ({ page }) => {
  await gotoApp(page);

  const quran404 = [];
  page.on("response", (response) => {
    if (response.url().includes("/quran-data/quran/") && response.status() === 404) {
      quran404.push(response.url());
    }
  });

  await page.locator("#searchInput").fill("الرحمن");
  await page.locator('#searchForm button[type="submit"]').click();

  await expect(page.locator("#searchDialog")).toBeVisible();
  await expect(page.locator("#searchResults button").first()).toBeVisible();
  await expect(page.locator("#searchResults mark").first()).toBeVisible();
  expect(quran404).toEqual([]);
});

test("النقر على آية يبرزها ويفتح التفسير ويقفز بالصوت", async ({ page }) => {
  test.skip(!fs.existsSync(AUDIO_2), "ملف الصوت 002.mp3 غير موجود (يُوزَّع خارج git)");

  await gotoApp(page);
  await openSurah(page, 2, "البقرة");
  await expect(page.locator("#ayahList .ayah")).toHaveCount(286);

  // انتظر حتى تصبح مدة الصوت معروفة (وإلا لن ينفّذ _seekSeconds القفز)
  await expect.poll(
    async () => page.evaluate(() => {
      const audio = document.getElementById("audio");
      return Number.isFinite(audio.duration) && audio.duration > 0;
    }),
    { timeout: 20_000 }
  ).toBe(true);

  await page.locator('#ayahList .ayah[data-ayah="255"]').click();

  await expect(page.locator("#ayahList .ayah.current")).toHaveAttribute("data-ayah", "255");
  await expect(page.locator("#tafsirPanel")).toHaveJSProperty("open", true);

  await expect.poll(
    async () => page.evaluate(() => document.getElementById("audio").currentTime),
    { timeout: 10_000 }
  ).toBeGreaterThan(3500);
});

test("الإعدادات تُحفظ بعد إعادة تحميل الصفحة", async ({ page }) => {
  await gotoApp(page);

  await page.locator('[data-open="settingsDialog"]').first().click();
  await page.locator("#themeSelect").selectOption("dark");
  await expect(page.locator("body")).toHaveClass(/theme-dark/);

  await page.locator('#settingsDialog button.gold[value="close"]').click();
  await page.reload();

  await expect(page.locator("#surahList button[data-surah]")).toHaveCount(114);
  await expect(page.locator("body")).toHaveClass(/theme-dark/);
});
