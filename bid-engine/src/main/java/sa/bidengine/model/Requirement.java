package sa.bidengine.model;

/**
 * بند متطلب واحد مستخرج من الكراسة.
 * قاعدة ذهبية: sourceQuote يجب أن يكون اقتباساً حرفياً من الكراسة —
 * هو خط دفاعك ضد الهلوسة: أي بند بلا اقتباس قابل للتحقق = بند مرفوض.
 */
public record Requirement(
        String id,            // معرف تسلسلي: REQ-001 ...
        String category,      // فني / إداري / مالي / محتوى محلي
        String description,   // وصف البند بصياغة واضحة
        String sourceQuote,   // اقتباس حرفي من الكراسة يثبت البند
        int sourcePage,       // رقم الصفحة التقريبي (من ترويسة المقطع)
        boolean mandatory     // هل عدم تلبيته سبب استبعاد؟
) {}
