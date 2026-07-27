package sa.bidengine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sa.bidengine.llm.LlmClient;
import sa.bidengine.model.Requirement;
import sa.bidengine.pipeline.TextChunker.Chunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * قلب خط الاستخراج: يمرر كل مقطع للنموذج بمطالبة صارمة، يجمع البنود، ويزيل التكرار.
 *
 * قاعدتا الأمان غير القابلتين للنقاش:
 * 1) أي بند يعيده النموذج بلا اقتباس حرفي (sourceQuote) يُرمى — لا استثناء.
 * 2) الناتج مسودة للمراجعة البشرية دائماً — التحقق اليدوي بنداً-بنداً هو
 *    معيار نجاح العطلة الأولى، لا ترفاً.
 */
public class RequirementExtractor {

    private static final String SYSTEM_PROMPT = """
        أنت محلل متخصص في كراسات الشروط والمواصفات للمنافسات الحكومية السعودية على منصة اعتماد.
        مهمتك استخراج بنود المتطلبات من مقطع الكراسة المعطى، بدقة متناهية وبلا أي اجتهاد.

        أعد JSON فقط — بلا مقدمات ولا أسوار markdown — بهذا الشكل:
        {"requirements":[{"category":"فني|إداري|مالي|محتوى محلي","description":"...","sourceQuote":"اقتباس حرفي من النص","sourcePage":0,"mandatory":true}]}

        قواعد صارمة:
        - sourceQuote اقتباس حرفي من النص المعطى. إن لم تجد نصاً حرفياً يثبت البند فلا تُخرج البند إطلاقاً.
        - sourcePage من ترويسات [صفحة N] المحيطة بالاقتباس.
        - mandatory=true فقط إذا كان النص يفيد الإلزام أو الاستبعاد عند عدم التلبية.
        - لا تستنتج بنوداً "منطقية" غير مكتوبة. الأمانة للنص أهم من الاكتمال.
        - إن خلا المقطع من متطلبات أعد: {"requirements":[]}
        """;

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public RequirementExtractor(LlmClient llm) {
        this.llm = llm;
    }

    public List<Requirement> extract(List<Chunk> chunks) throws Exception {
        List<Requirement> all = new ArrayList<>();
        for (Chunk chunk : chunks) {
            System.out.printf("  معالجة المقطع %d/%d (صفحات %d-%d)...%n",
                    chunk.index() + 1, chunks.size(), chunk.firstPage(), chunk.lastPage());
            String raw = llm.complete(SYSTEM_PROMPT, "مقطع الكراسة:\n\n" + chunk.text());
            all.addAll(parse(raw));
        }
        return dedupeAndNumber(all);
    }

    private List<Requirement> parse(String raw) {
        List<Requirement> out = new ArrayList<>();
        try {
            String json = raw.strip();
            // النموذج قد يغلف الرد بأسوار رغم التعليمات — نظّف بدفاعية:
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "");
            }
            JsonNode root = mapper.readTree(json);
            for (JsonNode r : root.path("requirements")) {
                String quote = r.path("sourceQuote").asText("");
                if (quote.isBlank()) continue;  // قاعدة الأمان 1: بلا اقتباس = مرفوض
                out.add(new Requirement(
                        "TMP",
                        r.path("category").asText("غير مصنف"),
                        r.path("description").asText(""),
                        quote,
                        r.path("sourcePage").asInt(0),
                        r.path("mandatory").asBoolean(false)));
            }
        } catch (Exception e) {
            // مقطع فشل تحليله لا يُسقط الكراسة كلها — لكنه لا يمر بصمت أيضاً:
            System.err.println("  تحذير: تعذر تحليل رد النموذج لهذا المقطع — راجعه يدوياً. " + e.getMessage());
        }
        return out;
    }

    /** إزالة التكرار الناتج عن تداخل المقاطع (مفتاح بسيط: الاقتباس مطبّعاً) ثم ترقيم. */
    private List<Requirement> dedupeAndNumber(List<Requirement> raw) {
        Map<String, Requirement> unique = new LinkedHashMap<>();
        for (Requirement r : raw) {
            String key = r.sourceQuote().replaceAll("\\s+", " ").strip();
            unique.putIfAbsent(key, r);
        }
        List<Requirement> numbered = new ArrayList<>();
        int i = 1;
        for (Requirement r : unique.values()) {
            numbered.add(new Requirement(String.format("REQ-%03d", i++),
                    r.category(), r.description(), r.sourceQuote(), r.sourcePage(), r.mandatory()));
        }
        return numbered;
    }
}
