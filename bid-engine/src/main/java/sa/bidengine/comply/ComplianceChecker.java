package sa.bidengine.comply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sa.bidengine.llm.LlmClient;
import sa.bidengine.model.Requirement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * قلب طبقة التحقق: يقارن كل بند متطلب بمقاطع وثائق العميل ويحكم:
 * مُغطى (COVERED) / ناقص (MISSING) / يحتاج مدخلاً (NEEDS_INPUT).
 *
 * الاسترجاع بسيط عمداً: تطبيع عربي ثم تقاطع كلمات — لا embeddings قبل أن تثبت
 * وثائق حقيقية أنك تحتاجها (قاعدة منع الإفراط الهندسي).
 *
 * حائط الهلوسة هنا ميكانيكي لا يعتمد على أمانة النموذج:
 * 1) حكم COVERED يُقبل فقط إذا كان evidenceQuote موجوداً فعلاً في نص المقطع
 *    المشار إليه (بعد تطبيع المسافات). دليل غير موجود = يتحول البند إلى
 *    NEEDS_INPUT مع ملاحظة أن النموذج ادعى تغطية بلا دليل.
 * 2) بند لم يرد عليه النموذج أصلاً = MISSING افتراضاً — الافتراض الآمن دائماً
 *    هو الأسوأ: بند يظهر ناقصاً خطأً يكشفه المراجع، وبند يظهر مغطى خطأً يُسقط العرض.
 * 3) الناتج مسودة للمراجعة البشرية دائماً — لا أتمتة للقرار النهائي.
 */
public class ComplianceChecker {

    /** عدد البنود في كل استدعاء للنموذج — دفعات صغيرة تحفظ التركيز وتقلل التكلفة. */
    static final int BATCH_SIZE = 10;

    /** عدد المقاطع المرشحة لكل بند. */
    static final int TOP_CHUNKS = 3;

    /** حد نص المقطع داخل المطالبة — التحقق الحرفي يجري على النص الكامل لا المقتطع. */
    static final int MAX_CHUNK_CHARS_IN_PROMPT = 2_000;

    static final String SYSTEM_PROMPT = """
        أنت مدقق امتثال متخصص في العروض الفنية للمنافسات الحكومية السعودية على منصة اعتماد.
        تُعطى بنود متطلبات من كراسة الشروط، ومع كل بند مقاطع مرشحة من وثائق العميل.
        مهمتك الحكم لكل بند: هل وثائق العميل تغطي هذا البند؟ بدقة متناهية وبلا أي اجتهاد.

        أعد JSON فقط — بلا مقدمات ولا أسوار markdown — بهذا الشكل:
        {"items":[{"requirementId":"REQ-001","status":"COVERED","chunkId":"KB-0001",
         "evidenceQuote":"اقتباس حرفي من نص المقطع","clientQuestion":"","notes":""}]}

        قواعد صارمة:
        - status واحدة من ثلاث قيم فقط: COVERED أو MISSING أو NEEDS_INPUT.
        - COVERED فقط إذا وجدت في المقاطع المعطاة نصاً يثبت أن وثائق العميل تلبي البند؛
          عندها ضع chunkId معرف المقطع، وevidenceQuote اقتباساً حرفياً منه دون أي تعديل
          أو إعادة صياغة. الاقتباس غير الحرفي سيُرفض آلياً.
        - MISSING إذا لم تجد في المقاطع ما يغطي البند ولا سبيل لتغطيته إلا بعمل جديد.
        - NEEDS_INPUT إذا كانت التغطية ممكنة لكنها تحتاج معلومة أو وثيقة من العميل؛
          عندها ضع clientQuestion سؤالاً واضحاً ومحدداً للعميل بالعربية الرسمية.
        - لا تخترع اقتباسات ولا تفترض وثائق غير معروضة. الأمانة للنص أهم من الاكتمال.
        - أعد عنصراً واحداً لكل بند معطى — لا تُسقط أي بند ولا تضف بنوداً من عندك.
        """;

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public ComplianceChecker(LlmClient llm) {
        this.llm = llm;
    }

    /** يفحص كل البنود ويعيد حكماً لكل بند بترتيب البنود نفسه — لا بند بلا حكم. */
    public List<ComplianceItem> check(List<Requirement> requirements, List<KbChunk> chunks)
            throws Exception {
        Map<String, KbChunk> byId = new HashMap<>();
        for (KbChunk c : chunks) byId.put(c.id(), c);

        // أحكام النموذج تُجمع هنا ثم تمر كلها عبر حائط الهلوسة قبل القبول.
        Map<String, JsonNode> verdicts = new LinkedHashMap<>();

        int batches = (requirements.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        for (int b = 0; b < batches; b++) {
            List<Requirement> batch = requirements.subList(
                    b * BATCH_SIZE, Math.min((b + 1) * BATCH_SIZE, requirements.size()));
            System.out.printf("  فحص الدفعة %d/%d (%d بنداً)...%n", b + 1, batches, batch.size());

            StringBuilder prompt = new StringBuilder("بنود المتطلبات ومقاطع وثائق العميل المرشحة لكل بند:\n");
            for (Requirement r : batch) {
                appendRequirement(prompt, r, topChunks(r, chunks));
            }

            String raw = llm.complete(SYSTEM_PROMPT, prompt.toString());
            for (JsonNode item : parse(raw)) {
                // أول حكم لكل بند يفوز — التكرار داخل الرد لا يقلب حكماً سابقاً.
                verdicts.putIfAbsent(item.path("requirementId").asText(""), item);
            }
        }

        List<ComplianceItem> out = new ArrayList<>();
        for (Requirement r : requirements) {
            out.add(judge(r, verdicts.get(r.id()), byId));
        }
        return out;
    }

    // ---------- الاسترجاع: تطبيع عربي + تقاطع كلمات ----------

    /** أنسب المقاطع للبند: ترتيب بعدد الكلمات المشتركة بعد التطبيع — صفر تقاطع لا يُرشح. */
    List<KbChunk> topChunks(Requirement r, List<KbChunk> chunks) {
        Set<String> query = ArabicText.tokens(r.description() + " " + r.sourceQuote());
        record Scored(KbChunk chunk, long score) {}
        List<Scored> scored = new ArrayList<>();
        for (KbChunk c : chunks) {
            long s = ArabicText.tokens(c.text()).stream().filter(query::contains).count();
            if (s > 0) scored.add(new Scored(c, s));
        }
        scored.sort((a, z) -> Long.compare(z.score(), a.score()));
        List<KbChunk> top = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_CHUNKS, scored.size()); i++) {
            top.add(scored.get(i).chunk());
        }
        return top;
    }

    private void appendRequirement(StringBuilder p, Requirement r, List<KbChunk> top) {
        p.append("\n=== البند ").append(r.id())
         .append(" (").append(r.category()).append(r.mandatory() ? "، إلزامي" : "").append(")\n")
         .append("الوصف: ").append(r.description()).append('\n')
         .append("نص البند في الكراسة: ").append(r.sourceQuote()).append('\n');
        if (top.isEmpty()) {
            p.append("لا مقاطع مرشحة من وثائق العميل لهذا البند.\n");
            return;
        }
        for (KbChunk c : top) {
            String text = c.text();
            if (text.length() > MAX_CHUNK_CHARS_IN_PROMPT) {
                text = text.substring(0, MAX_CHUNK_CHARS_IN_PROMPT) + " …";
            }
            p.append("--- المقطع ").append(c.id())
             .append(" | المصدر: ").append(c.source())
             .append(" | صفحة ").append(c.page()).append('\n')
             .append(text).append('\n');
        }
    }

    // ---------- حائط الهلوسة الميكانيكي ----------

    /** يحوّل حكم النموذج الخام إلى حكم مقبول — أو يطبق الافتراض الآمن. */
    private ComplianceItem judge(Requirement r, JsonNode v, Map<String, KbChunk> byId) {
        String defaultQuestion = "يرجى تزويدنا بوثيقة أو معلومة تثبت تلبية البند "
                + r.id() + ": " + r.description();

        if (v == null) {
            // القاعدة 2: بند بلا رد = ناقص افتراضاً — الافتراض الآمن دائماً هو الأسوأ.
            return new ComplianceItem(r.id(), ComplianceItem.MISSING, "", "", 0,
                    "لم يرد النموذج على هذا البند — اعتُبر ناقصاً افتراضاً (الافتراض الآمن).", "");
        }

        String status = v.path("status").asText("");
        String notes = v.path("notes").asText("");

        switch (status) {
            case ComplianceItem.COVERED -> {
                String quote = v.path("evidenceQuote").asText("");
                KbChunk chunk = byId.get(v.path("chunkId").asText(""));
                // القاعدة 1: الدليل يُتحقق منه برمجياً في نص المقطع المشار إليه —
                // بعد تطبيع المسافات — ولا يُقبل على كلام النموذج.
                if (chunk != null && !quote.isBlank()
                        && ArabicText.normalizeSpaces(chunk.text())
                                     .contains(ArabicText.normalizeSpaces(quote))) {
                    return new ComplianceItem(r.id(), ComplianceItem.COVERED,
                            quote, chunk.source(), chunk.page(), notes, "");
                }
                return new ComplianceItem(r.id(), ComplianceItem.NEEDS_INPUT, "", "", 0,
                        "ادعى النموذج تغطية هذا البند بدليل غير موجود في المقطع المشار إليه"
                        + " — رُفض الادعاء آلياً (حائط الهلوسة). راجع يدوياً أو اطلب مدخل العميل.",
                        defaultQuestion);
            }
            case ComplianceItem.NEEDS_INPUT -> {
                String question = v.path("clientQuestion").asText("");
                return new ComplianceItem(r.id(), ComplianceItem.NEEDS_INPUT, "", "", 0,
                        notes, question.isBlank() ? defaultQuestion : question);
            }
            case ComplianceItem.MISSING -> {
                return new ComplianceItem(r.id(), ComplianceItem.MISSING, "", "", 0, notes, "");
            }
            default -> {
                // حالة غير معروفة = كأن لا رد — الافتراض الآمن.
                return new ComplianceItem(r.id(), ComplianceItem.MISSING, "", "", 0,
                        "أعاد النموذج حالة غير معروفة (" + status
                        + ") — اعتُبر البند ناقصاً افتراضاً (الافتراض الآمن).", "");
            }
        }
    }

    private List<JsonNode> parse(String raw) {
        List<JsonNode> out = new ArrayList<>();
        try {
            String json = raw.strip();
            // النموذج قد يغلف الرد بأسوار رغم التعليمات — نظّف بدفاعية:
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
            }
            for (JsonNode item : mapper.readTree(json).path("items")) {
                if (!item.path("requirementId").asText("").isBlank()) out.add(item);
            }
        } catch (Exception e) {
            // دفعة فشل تحليلها لا تُسقط الفحص كله — بنودها تسقط إلى MISSING افتراضاً:
            System.err.println("  تحذير: تعذر تحليل رد النموذج لهذه الدفعة — بنودها ستُعتبر ناقصة. "
                    + e.getMessage());
        }
        return out;
    }
}
