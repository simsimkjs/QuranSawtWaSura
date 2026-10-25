/**
 * «مشوار حفظ القرآن»: يتتبّع أبعد نقطة وصلها المستخدم فعليًا، ويحسب نسبة الإنجاز
 * والمعدل اليومي والمستوى — بنفس منطق MemorizationStore في نسخة سطح المكتب.
 */

import { cumulativeAyah, juzOf, totalAyahCount } from "./divisions.js";

const DAILY_WINDOW_DAYS = 14;

function todayKey() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function parseDay(key) {
  const d = new Date(`${key}T00:00:00`);
  return Number.isNaN(d.getTime()) ? null : d;
}

function daysBetween(from, to) {
  const ms = to.getTime() - from.getTime();
  return Math.round(ms / 86400000);
}

/**
 * يسجّل تقدّمًا جديدًا (لا يتراجع أبدًا عند المراجعة).
 * @returns {{progress:object, completedJuz:number|null, changed:boolean}}
 */
export function recordProgress(progress, surahNumber, ayahNumber, allSurahs) {
  const newCumulative = cumulativeAyah(surahNumber, ayahNumber, allSurahs);
  const previousCumulative = progress.furthestSurah === 0
    ? 0
    : cumulativeAyah(progress.furthestSurah, progress.furthestAyah, allSurahs);

  if (progress.furthestSurah !== 0 && newCumulative <= previousCumulative) {
    return { progress, completedJuz: null, changed: false };
  }

  const next = {
    ...progress,
    furthestSurah: surahNumber,
    furthestAyah: ayahNumber,
    dailyLog: { ...progress.dailyLog, [todayKey()]: newCumulative },
    congratulatedJuz: [...progress.congratulatedJuz],
  };

  let completedJuz = null;
  const newJuz = juzOf(surahNumber, ayahNumber);
  const justFinished = newJuz - 1;

  if (justFinished >= 1 && !next.congratulatedJuz.includes(justFinished)) {
    next.congratulatedJuz.push(justFinished);
    completedJuz = justFinished;
  }

  if (newCumulative >= totalAyahCount(allSurahs) && !next.congratulatedJuz.includes(30)) {
    next.congratulatedJuz.push(30);
    completedJuz = 30;
  }

  return { progress: next, completedJuz, changed: true };
}

/** إحصاءات العرض (النسبة، الجزء الحالي، المعدل اليومي، الأيام المتبقية، المستوى). */
export function computeStats(progress, allSurahs) {
  const total = totalAyahCount(allSurahs) || 1;
  const furthest = progress.furthestSurah === 0
    ? 0
    : cumulativeAyah(progress.furthestSurah, progress.furthestAyah, allSurahs);
  const currentJuz = progress.furthestSurah === 0
    ? 0
    : juzOf(progress.furthestSurah, progress.furthestAyah);

  const dailyAverage = computeDailyAverage(progress, furthest);
  const remaining = Math.max(0, total - furthest);
  const daysRemaining = dailyAverage > 0.01 ? Math.ceil(remaining / dailyAverage) : null;

  let level = "BEGINNER";
  if (dailyAverage >= 40) level = "EXPERT";
  else if (dailyAverage >= 15) level = "ADVANCED";
  else if (dailyAverage >= 5) level = "INTERMEDIATE";

  return {
    total,
    furthest,
    currentJuz,
    percent: Math.min(100, (furthest / total) * 100),
    dailyAverage,
    daysRemaining,
    level,
    levelLabel: LEVEL_LABELS[level],
    remaining,
  };
}

const LEVEL_LABELS = {
  BEGINNER: "مبتدئ",
  INTERMEDIATE: "متوسط",
  ADVANCED: "متقدّم",
  EXPERT: "متمكّن",
};

function computeDailyAverage(progress, currentFurthest) {
  const log = progress.dailyLog || {};
  const keys = Object.keys(log).sort();
  if (keys.length === 0) return 0;

  const today = new Date();
  const cutoff = new Date(today.getTime() - DAILY_WINDOW_DAYS * 86400000);

  const windowKeys = keys.filter((k) => {
    const d = parseDay(k);
    return d && d >= cutoff;
  });
  if (windowKeys.length === 0) return 0;

  const firstKey = windowKeys[0];
  const firstDay = parseDay(firstKey);
  const firstValue = log[firstKey];
  if (!firstDay) return 0;

  const daysElapsed = daysBetween(firstDay, today) + 1;
  const gained = currentFurthest - firstValue;
  if (gained <= 0 || daysElapsed <= 0) return 0;
  return gained / daysElapsed;
}
