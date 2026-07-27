package sa.bidengine.comply;

import java.util.HashSet;
import java.util.Set;

/**
 * تطبيع عربي بسيط لأغراض الاسترجاع والمطابقة — عمداً بلا embeddings ولا مكتبات:
 * إزالة التشكيل والتطويل، توحيد أشكال الألف والياء والتاء المربوطة، ثم تقاطع كلمات.
 * البداية البسيطة الصحيحة — لا تقطيع دلالي قبل أن تثبت وثائق حقيقية أنك تحتاجه.
 */
public final class ArabicText {

    private ArabicText() {}

    /** تطبيع المسافات فقط — يستعمله التحقق الحرفي من الدليل (حائط الهلوسة). */
    public static String normalizeSpaces(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").strip();
    }

    /**
     * تطبيع عربي للاسترجاع: إزالة التشكيل (ً-ٟ وٰ) والتطويل (ـ)،
     * توحيد أ/إ/آ/ٱ إلى ا، وى إلى ي، وة إلى ه — ثم تطبيع المسافات.
     */
    public static String normalizeArabic(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "");
        out = out.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا');
        out = out.replace('ى', 'ي');
        out = out.replace('ة', 'ه');
        return normalizeSpaces(out);
    }

    /** كلمات النص بعد التطبيع — لحساب تقاطع الكلمات في ترتيب المقاطع. */
    public static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String t : normalizeArabic(s).toLowerCase().split("[^\\p{L}\\p{Nd}]+")) {
            if (t.length() >= 2) out.add(t);  // كلمة بحرف واحد ضجيج لا إشارة
        }
        return out;
    }
}
