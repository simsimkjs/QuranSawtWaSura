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
  expect(firstText).toContain("ال");

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

test("يُحمّل خط quran.com العثماني ويُطبَّق على نص الآيات", async ({ page }) => {
  await gotoApp(page);
  await openSurah(page, 2, "البقرة");

  const fontState = await page.evaluate(async () => {
    await document.fonts.ready;
    const ayah = document.querySelector("#ayahList .ayah-text");
    return {
      loaded: [...document.fonts].some(
        (font) => font.family === "UthmanicHafs" && font.status === "loaded"
      ),
      family: ayah ? getComputedStyle(ayah).fontFamily : "",
    };
  });

  expect(fontState.loaded).toBe(true);
  expect(fontState.family).toContain("UthmanicHafs");
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
