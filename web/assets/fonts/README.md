# خطوط المصحف

## الخط العثماني الأساسي

الملف `UthmanicHafs1Ver18.woff2` هو خط **KFGQPC HAFS Uthmanic Script** الإصدار **0.18**،
مأخوذ من الخط الذي يستخدمه موقع [quran.com](https://quran.com):

- WOFF2: <https://quran.com/fonts/quran/hafs/uthmanic_hafs/UthmanicHafs1Ver18.woff2>
- TTF (لنسخة سطح المكتب): <https://quran.com/fonts/quran/hafs/uthmanic_hafs/UthmanicHafs1Ver18.ttf>

يُستخدم هذا الخط في:

- نص الآيات العادي (Unicode) في سطح المكتب ونسخة الويب قبل تحميل خط QCF.
- نتائج البحث والتفسير والواجهة.
- نسخة سطح المكتب موجودة في:
  `src/main/resources/sim/quran/fonts/UthmanicHafs1Ver18.ttf`

## خطوط QCF لكل صفحة

الواجهة الافتراضية لموقع quran.com تستخدم نصوص `code_v2` (أشكال حروف مرمّزة)
مع خط مستقل لكل صفحة من المصحف (`p1` … `p604`). نسخة الويب تحاكي ذلك:

```
web/assets/fonts/qcf/pNNN.woff2    خط الصفحة NNN
web/css/qcf-fonts.css              قواعد @font-face المولَّدة
```

- المصدر: <https://quran.com/fonts/quran/hafs/v2/woff2/pNNN.woff2>
- أسماء عائلات الخطوط: `QCF-P1` … `QCF-P604`.
- النصوص المرمّزة موجودة في حقلي `qcf` و`qcf_page` داخل ملفات
  `quran-data/quran/NNN.json`، ويضيفها المولّد
  [`tools/build_qcf_data.py`](../../../tools/build_qcf_data.py).
- لا تُضمَّن إلا صفحات السور المتوفرة حاليًا (172 صفحة، نحو 25 ميغابايت).
- يحمّل التطبيق خط الصفحة عند اقتراب آياتها من مجال الرؤية، ويُبقي النص
  العادي ظاهرًا حتى ينتهي التحميل؛ فإن فشل التحميل لا تظهر مربعات.

لتحديث النصوص والخطوط:

```powershell
python tools\build_qcf_data.py
```

للتحقق دون اتصال (مناسب لـ CI):

```powershell
python tools\build_qcf_data.py --check
```

> ملفات QCF مأخوذة من quran.com لأغراض مطابقة العرض. راجع شروط استخدام
> quran.com/مصادر الخط قبل إعادة توزيعها في منتج آخر.
