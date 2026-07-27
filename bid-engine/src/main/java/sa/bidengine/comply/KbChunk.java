package sa.bidengine.comply;

/**
 * مقطع واحد من فهرس قاعدة معرفة العميل (kb-index.json).
 * الفهرس تنتجه وحدة قاعدة المعرفة — هذه الحزمة تقرؤه فقط ولا تعدّله.
 */
public record KbChunk(
        String id,      // معرف المقطع: KB-0001 ...
        String source,  // اسم ملف العميل الذي أُخذ منه المقطع
        int page,       // رقم الصفحة داخل المصدر
        String text     // نص المقطع — مرجع التحقق الحرفي من أي دليل
) {}
