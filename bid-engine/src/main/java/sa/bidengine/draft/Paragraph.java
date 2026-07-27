package sa.bidengine.draft;

import java.util.List;

/**
 * فقرة واحدة من قسم العرض. حالتان لا ثالثة لهما:
 * 1) فقرة مسنودة: needsInput=false وكل ادعاء فيها له اقتباس متحقق منه برمجياً.
 * 2) فجوة معلنة: needsInput=true مع سؤال واضح للعميل بدل النص الادعائي —
 *    الفجوات تُعلن ولا تُردم بالاختراع؛ سدّها مع العميل هو الـ20% البشرية من الخدمة.
 */
public record Paragraph(
        String text,             // نص الفقرة بعربية رسمية — فارغ إن كانت فجوة
        boolean needsInput,      // هل تحتاج مدخلاً من العميل بدل نص مُدّعى؟
        String clientQuestion,   // السؤال الموجه للعميل عند needsInput=true
        List<Citation> citations // الاقتباسات المسنِدة — المتحقق منها فقط
) {}
