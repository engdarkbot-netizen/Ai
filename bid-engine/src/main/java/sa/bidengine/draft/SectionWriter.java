package sa.bidengine.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sa.bidengine.kb.ClientKnowledgeBase;
import sa.bidengine.kb.ClientKnowledgeBase.KbChunk;
import sa.bidengine.llm.LlmClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * صياغة قسم واحد من العرض الفني اعتماداً حصرياً على قاعدة معرفة العميل.
 *
 * حائط الهلوسة هنا ميكانيكي لا بالمطالبة فقط — بعد تحليل رد النموذج:
 * 1) كل اقتباس يُتحقق برمجياً أنه موجود فعلاً في نص المقطع المشار إليه
 *    (بعد تطبيع المسافات). اقتباس غير موجود = يُحذف مع تحذير.
 * 2) فقرة بقيت بلا أي اقتباس صالح = تتحول إلى needsInput=true مع سؤال واضح
 *    للعميل بدل نصها الادعائي. الفجوات تُعلن ولا تُردم بالاختراع — جوهر المنتج.
 * 3) الناتج مسودة للمراجعة البشرية دائماً.
 */
public class SectionWriter {

    /** كم مقطعاً يُسترجع لكل استعلام، وكم مقطعاً كحد أقصى يدخل المطالبة. */
    private static final int RESULTS_PER_QUERY = 3;
    private static final int MAX_CHUNKS_PER_SECTION = 8;

    static final String SYSTEM_PROMPT = """
        أنت كاتب عروض فنية متخصص في منافسات المشتريات الحكومية السعودية على منصة اعتماد.
        مهمتك صياغة فقرات قسم واحد من العرض الفني بعربية رسمية، اعتماداً حصرياً على
        مقاطع قاعدة معرفة العميل المعطاة — بدقة متناهية وبلا أي اجتهاد.

        أعد JSON فقط — بلا مقدمات ولا أسوار markdown — بهذا الشكل:
        {"paragraphs":[{"text":"...","needsInput":false,"clientQuestion":"","citations":[{"chunkId":"KB-0001","quote":"اقتباس حرفي من نص المقطع"}]}]}

        قواعد صارمة:
        - كل فقرة تدّعي شيئاً عن العميل (خبرة، مشروع، قدرة، منهجية، شهادة، رقم) يجب أن
          تحمل citations باقتباسات حرفية من نص المقاطع المعطاة مع chunkId المقطع المقتبس منه.
        - quote اقتباس حرفي كما ورد في نص المقطع — لا تُعد صياغته ولا تختصره بتصرف.
        - إن احتاج القسم معلومة لا تسندها المقاطع فلا تخترعها: أخرج فقرة needsInput=true
          مع clientQuestion سؤالاً واضحاً للعميل عن المعلومة الناقصة، واترك text فارغاً.
        - لا تنسب للعميل مشاريع أو أرقاماً أو جهات أو شهادات لم ترد حرفياً في المقاطع.
          الأمانة للنص أهم من الاكتمال ومن جمال الصياغة.
        - إن خلت المقاطع مما يفيد القسم أعد فقرات needsInput فقط أو: {"paragraphs":[]}
        """;

    private final LlmClient llm;
    private final ClientKnowledgeBase kb;
    private final ObjectMapper mapper = new ObjectMapper();

    public SectionWriter(LlmClient llm, ClientKnowledgeBase kb) {
        this.llm = llm;
        this.kb = kb;
    }

    /**
     * يسترجع المقاطع الأنسب للاستعلامات المعطاة، يطلب صياغة القسم من النموذج،
     * ثم يمرر كل فقرة على التحقق الميكانيكي من الاقتباسات قبل قبولها.
     *
     * @param context سياق يوضع في المطالبة قبل المقاطع (مثل بنود المتطلبات الفنية).
     */
    public ProposalSection write(String id, String title, List<String> queries, String context)
            throws Exception {
        Map<String, KbChunk> selected = new LinkedHashMap<>();
        for (String query : queries) {
            for (KbChunk c : kb.search(query, RESULTS_PER_QUERY)) {
                selected.putIfAbsent(c.id(), c);
            }
            if (selected.size() >= MAX_CHUNKS_PER_SECTION) break;
        }

        if (selected.isEmpty()) {
            // لا وثائق تسند القسم إطلاقاً — فجوة كاملة تُعلن للعميل، لا تُرتجل.
            System.out.println("   لا مقاطع مناسبة لقسم «" + title + "» — القسم كله فجوة معلنة.");
            return new ProposalSection(id, title, List.of(new Paragraph("", true,
                    "لم نجد في وثائقكم المتاحة ما يغطي قسم «" + title
                            + "». ما الوثائق أو المعلومات التي ترغبون اعتمادها لهذا القسم؟",
                    List.of())));
        }

        StringBuilder user = new StringBuilder();
        user.append("القسم المطلوب: ").append(title).append("\n\n");
        if (context != null && !context.isBlank()) {
            user.append(context).append("\n\n");
        }
        user.append("مقاطع قاعدة معرفة العميل (المرجع الوحيد المسموح بالاقتباس منه):\n");
        for (KbChunk c : selected.values()) {
            user.append("\n[").append(c.id()).append(" | المصدر: ").append(c.source())
                .append(" | صفحة ").append(c.page()).append("]\n")
                .append(c.text()).append("\n");
        }

        String raw = llm.complete(SYSTEM_PROMPT, user.toString());
        return new ProposalSection(id, title, verify(parse(raw)));
    }

    private JsonNode parse(String raw) {
        try {
            String json = raw.strip();
            // النموذج قد يغلف الرد بأسوار رغم التعليمات — نظّف بدفاعية:
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
            }
            return mapper.readTree(json);
        } catch (Exception e) {
            // قسم فشل تحليله لا يُسقط التشغيلة كلها — لكنه لا يمر بصمت أيضاً:
            System.err.println("  تحذير: تعذر تحليل رد النموذج لهذا القسم — راجعه يدوياً. "
                    + e.getMessage());
            return null;
        }
    }

    /** التحقق الميكانيكي: يمرر كل فقرة على فحص الاقتباسات ويعيد الصالح منها فقط. */
    private List<Paragraph> verify(JsonNode root) {
        List<Paragraph> out = new ArrayList<>();
        if (root == null) return out;
        for (JsonNode p : root.path("paragraphs")) {
            Paragraph verified = verifyParagraph(p);
            if (verified != null) out.add(verified);
        }
        return out;
    }

    private Paragraph verifyParagraph(JsonNode p) {
        String text = p.path("text").asText("");
        boolean modelNeedsInput = p.path("needsInput").asBoolean(false);
        String modelQuestion = p.path("clientQuestion").asText("");

        List<Citation> valid = new ArrayList<>();
        for (JsonNode c : p.path("citations")) {
            String chunkId = c.path("chunkId").asText("");
            String quote = c.path("quote").asText("");
            KbChunk chunk = kb.get(chunkId);
            if (chunk == null || quote.isBlank()) {
                System.err.println("  تحذير: اقتباس يشير إلى مقطع غير موجود ("
                        + chunkId + ") — حُذف.");
                continue;
            }
            if (normalizeSpaces(chunk.text()).contains(normalizeSpaces(quote))) {
                // المصدر والصفحة يُملآن من المقطع نفسه لا من رد النموذج — حقيقة لا ادعاء.
                valid.add(new Citation(chunkId, chunk.source(), chunk.page(), quote));
            } else {
                System.err.println("  تحذير: اقتباس غير موجود حرفياً في المقطع "
                        + chunkId + " — حُذف: «" + gist(quote) + "»");
            }
        }

        if (modelNeedsInput) {
            // النموذج نفسه أعلن الفجوة — نحترم ذلك ونضمن وجود سؤال للعميل.
            String question = modelQuestion.isBlank()
                    ? "وردت في هذا القسم فجوة معلومات لم توضح. ما المعلومات التي ترغبون إضافتها هنا؟"
                    : modelQuestion;
            return new Paragraph("", true, question, List.of());
        }
        if (text.isBlank() && valid.isEmpty()) {
            return null; // فقرة فارغة بلا اقتباسات ولا سؤال — لا قيمة لها.
        }
        if (valid.isEmpty()) {
            // قاعدة الأمان: فقرة بلا أي اقتباس صالح لا تمر كنص — تتحول إلى سؤال للعميل.
            return new Paragraph("", true,
                    "لم نجد في وثائقكم ما يسند هذا النص المقترح: «" + gist(text)
                            + "». ما المعلومات أو الوثائق التي تثبته حتى نعتمده في العرض؟",
                    List.of());
        }
        return new Paragraph(text, false, "", List.copyOf(valid));
    }

    /** تطبيع المسافات فقط — معيار المطابقة الحرفية المتفق عليه للاقتباسات. */
    private static String normalizeSpaces(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    /** مقتطف قصير للتحذيرات وسؤال العميل — لا نكرر ادعاءً طويلاً غير مسنود. */
    private static String gist(String s) {
        String t = normalizeSpaces(s);
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }
}
