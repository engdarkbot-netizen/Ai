package sa.bidengine.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sa.bidengine.llm.LlmClient;
import sa.bidengine.model.EvaluationCriterion;
import sa.bidengine.model.MandatoryDocument;
import sa.bidengine.pipeline.TextChunker.Chunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * استخراج ما عدا بنود المتطلبات: البيانات التعريفية للمنافسة + معايير التقييم
 * + المستندات الإلزامية — بنفس نمط RequirementExtractor وقواعده:
 * 1) أي معيار أو مستند بلا اقتباس حرفي (sourceQuote) يُرمى — لا استثناء.
 * 2) الناتج مسودة للمراجعة البشرية دائماً.
 */
public class TenderInfoExtractor {

    /** ناتج وسيط — يُدمج مع بنود المتطلبات في TenderSpec داخل Main. */
    public record TenderInfo(
            String tenderName,
            String governmentEntity,
            String tenderNumber,
            String submissionDeadline,
            List<EvaluationCriterion> evaluationCriteria,
            List<MandatoryDocument> mandatoryDocuments
    ) {}

    private static final String SYSTEM_PROMPT = """
        أنت محلل متخصص في كراسات الشروط والمواصفات للمنافسات الحكومية السعودية على منصة اعتماد.
        مهمتك استخراج البيانات التعريفية للمنافسة، ومعايير التقييم، والمستندات الإلزامية
        من مقطع الكراسة المعطى، بدقة متناهية وبلا أي اجتهاد.

        أعد JSON فقط — بلا مقدمات ولا أسوار markdown — بهذا الشكل:
        {"tenderName":"","governmentEntity":"","tenderNumber":"","submissionDeadline":"",
         "evaluationCriteria":[{"name":"...","weight":"كما ورد نصاً","notes":"...","sourceQuote":"اقتباس حرفي","sourcePage":0}],
         "mandatoryDocuments":[{"name":"...","sourceQuote":"اقتباس حرفي","sourcePage":0}]}

        قواعد صارمة:
        - البيانات التعريفية (الاسم، الجهة، الرقم، موعد الإقفال) انسخها كما وردت حرفياً؛
          إن لم ترد في هذا المقطع فأعدها سلسلة فارغة "". لا تخمّن أبداً.
        - sourceQuote اقتباس حرفي من النص المعطى. إن لم تجد نصاً حرفياً يثبت المعيار
          أو المستند فلا تُخرجه إطلاقاً.
        - sourcePage من ترويسات [صفحة N] المحيطة بالاقتباس.
        - weight كما ورد نصاً ("30%" أو "30 درجة") — لا تحسب ولا تحوّل.
        - لا تستنتج عناصر "منطقية" غير مكتوبة. الأمانة للنص أهم من الاكتمال.
        - إن خلا المقطع من معايير أو مستندات أعد القائمتين فارغتين.
        """;

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public TenderInfoExtractor(LlmClient llm) {
        this.llm = llm;
    }

    public TenderInfo extract(List<Chunk> chunks) throws Exception {
        String tenderName = "", governmentEntity = "", tenderNumber = "", submissionDeadline = "";
        List<EvaluationCriterion> criteria = new ArrayList<>();
        List<MandatoryDocument> documents = new ArrayList<>();

        for (Chunk chunk : chunks) {
            System.out.printf("  معالجة المقطع %d/%d (صفحات %d-%d)...%n",
                    chunk.index() + 1, chunks.size(), chunk.firstPage(), chunk.lastPage());
            String raw = llm.complete(SYSTEM_PROMPT, "مقطع الكراسة:\n\n" + chunk.text());
            JsonNode root = parse(raw);
            if (root == null) continue;

            // البيانات التعريفية: أول قيمة غير فارغة تفوز (تظهر عادة في صفحات الغلاف).
            if (tenderName.isBlank()) tenderName = root.path("tenderName").asText("");
            if (governmentEntity.isBlank()) governmentEntity = root.path("governmentEntity").asText("");
            if (tenderNumber.isBlank()) tenderNumber = root.path("tenderNumber").asText("");
            if (submissionDeadline.isBlank()) submissionDeadline = root.path("submissionDeadline").asText("");

            for (JsonNode c : root.path("evaluationCriteria")) {
                String quote = c.path("sourceQuote").asText("");
                if (quote.isBlank()) continue;  // قاعدة الأمان 1: بلا اقتباس = مرفوض
                criteria.add(new EvaluationCriterion(
                        c.path("name").asText(""),
                        c.path("weight").asText(""),
                        c.path("notes").asText(""),
                        quote,
                        c.path("sourcePage").asInt(0)));
            }
            for (JsonNode d : root.path("mandatoryDocuments")) {
                String quote = d.path("sourceQuote").asText("");
                if (quote.isBlank()) continue;  // قاعدة الأمان 1: بلا اقتباس = مرفوض
                documents.add(new MandatoryDocument(
                        d.path("name").asText(""),
                        quote,
                        d.path("sourcePage").asInt(0)));
            }
        }

        return new TenderInfo(tenderName, governmentEntity, tenderNumber, submissionDeadline,
                dedupeCriteria(criteria), dedupeDocuments(documents));
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
            // مقطع فشل تحليله لا يُسقط الكراسة كلها — لكنه لا يمر بصمت أيضاً:
            System.err.println("  تحذير: تعذر تحليل رد النموذج لهذا المقطع — راجعه يدوياً. " + e.getMessage());
            return null;
        }
    }

    /** إزالة التكرار الناتج عن تداخل المقاطع — مفتاح المعيار: اسمه مطبّعاً. */
    private List<EvaluationCriterion> dedupeCriteria(List<EvaluationCriterion> raw) {
        Map<String, EvaluationCriterion> unique = new LinkedHashMap<>();
        for (EvaluationCriterion c : raw) {
            unique.putIfAbsent(normalize(c.name()), c);
        }
        return new ArrayList<>(unique.values());
    }

    /** إزالة التكرار — مفتاح المستند: اسمه مطبّعاً. */
    private List<MandatoryDocument> dedupeDocuments(List<MandatoryDocument> raw) {
        Map<String, MandatoryDocument> unique = new LinkedHashMap<>();
        for (MandatoryDocument d : raw) {
            unique.putIfAbsent(normalize(d.name()), d);
        }
        return new ArrayList<>(unique.values());
    }

    private String normalize(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
