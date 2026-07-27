package sa.bidengine.comply;

/**
 * حكم الامتثال على بند متطلب واحد — سطر واحد في compliance.json.
 * حائط الهلوسة يسري هنا بصرامة مضاعفة: حكم COVERED لا يُقبل إلا إذا كان
 * evidenceQuote موجوداً حرفياً في نص المقطع المشار إليه — يتحقق منه الكود
 * ميكانيكياً بعد تطبيع المسافات، لا يُؤخذ من النموذج على الثقة.
 */
public record ComplianceItem(
        String requirementId,   // معرف البند: REQ-001 ...
        String status,          // COVERED / MISSING / NEEDS_INPUT — لا قيمة رابعة
        String evidenceQuote,   // اقتباس حرفي من مقطع العميل (لحالة COVERED فقط)
        String evidenceSource,  // اسم ملف العميل مصدر الدليل
        int evidencePage,       // رقم الصفحة داخل المصدر
        String notes,           // ملاحظات للمراجع البشري (منها ملاحظات الترقية الآمنة)
        String clientQuestion   // سؤال واضح للعميل (لحالة NEEDS_INPUT)
) {
    public static final String COVERED = "COVERED";
    public static final String MISSING = "MISSING";
    public static final String NEEDS_INPUT = "NEEDS_INPUT";
}
