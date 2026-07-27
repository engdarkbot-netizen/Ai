package sa.bidengine.draft;

import java.util.List;

/**
 * قسم واحد من العرض الفني (العطلة الثالثة: «المنهجية» و«سابقة الأعمال» فقط).
 * يُكتب في proposal-sections.json بالشكل المتفق عليه — لا يتغير، وحدات أخرى تُبنى عليه:
 * {"sections":[{"id":"methodology","title":"المنهجية","paragraphs":[...]}]}
 */
public record ProposalSection(
        String id,                  // معرف ثابت: methodology أو past-works
        String title,               // العنوان العربي المعروض
        List<Paragraph> paragraphs  // فقرات القسم — مسودة للمراجعة البشرية دائماً
) {}
