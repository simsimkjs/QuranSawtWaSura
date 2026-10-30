/**
 * خطوط QCF (code_v2) من quran.com.
 *
 * كل صفحة من المصحف لها خط مستقل اسمه QCF-P{page}. نعرض النص العادي أولًا،
 * ثم نستبدله بنص QCF بعد التأكد من تحميل خط الصفحة؛ وإن فشل التحميل يبقى
 * النص العادي مقروءًا بدل ظهور مربعات.
 */

const fontCache = new Map();

export function qcfFamily(page) {
  return `QCF-P${page}`;
}

export function hasQcf(ayah) {
  return Boolean(
    ayah &&
      typeof ayah.qcf === "string" &&
      ayah.qcf.trim() &&
      Number.isInteger(ayah.qcf_page) &&
      ayah.qcf_page > 0
  );
}

/**
 * يحمّل خطوط الصفحات المطلوبة (مرة واحدة لكل صفحة).
 * @param {number[]} pages
 * @returns {Promise<Map<number, boolean>>} هل نجح تحميل خط كل صفحة؟
 */
export function ensureQcfFonts(pages) {
  const unique = [...new Set(pages.filter((page) => Number.isInteger(page) && page > 0))];
  const result = new Map();

  return Promise.all(
    unique.map((page) => {
      let promise = fontCache.get(page);
      if (!promise) {
        const family = qcfFamily(page);
        const canLoad = typeof document !== "undefined" && document.fonts && document.fonts.load;
        if (canLoad) {
          try {
            promise = document.fonts
              .load(`16px "${family}"`)
              .then((faces) => Array.isArray(faces) && faces.length > 0)
              .catch(() => false);
          } catch {
            promise = Promise.resolve(false);
          }
        } else {
          promise = Promise.resolve(false);
        }
        fontCache.set(page, promise);
      }
      return promise.then((ok) => {
        result.set(page, ok);
        return ok;
      });
    })
  ).then(() => result);
}
