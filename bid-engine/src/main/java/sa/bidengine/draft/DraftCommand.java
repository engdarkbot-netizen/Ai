package sa.bidengine.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sa.bidengine.kb.ClientKnowledgeBase;
import sa.bidengine.llm.AnthropicLlmClient;
import sa.bidengine.llm.LlmClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * أمر العطلة الثالثة — قاعدة معرفة العميل + توليد قسمين فقط من العرض:
 *   java -cp "target/classes:lib/*" sa.bidengine.draft.DraftCommand \\
 *        <tender-spec.json> <مجلد وثائق العميل> <مجلد الإخراج>
 *
 * المخرجات (في مجلد الإخراج):
 *   kb-index.json           فهرس مقاطع وثائق العميل — الشكل متفق عليه ولا يتغير
 *   proposal-sections.json  قسما «المنهجية» و«سابقة الأعمال» — الشكل متفق عليه ولا يتغير
 *   sections-review.html    صفحة المراجعة اليدوية — الفجوات بلون تحذيري مع سؤال العميل
 *
 * معيار نجاح العطلة: قسم عربي رسمي كل ادعاء فيه مسنود — والفجوات معلنة لا مردومة.
 */
public class DraftCommand {

    /** كم بند متطلبات يتحول إلى استعلامات استرجاع لقسم المنهجية. */
    private static final int MAX_METHODOLOGY_QUERIES = 8;

    /** استعلامات قسم سابقة الأعمال — من وثائق العميل نفسها لا من الكراسة. */
    private static final List<String> PAST_WORKS_QUERIES = List.of(
            "المشاريع السابقة المنفذة",
            "عقود وأعمال منجزة لجهات حكومية",
            "خبرات الشركة وسابقة الأعمال",
            "شهادات إنجاز ونطاق أعمال مسلمة");

    public static void main(String[] args) throws Exception {
        run(args);
    }

    public static void run(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("الاستخدام: java -cp \"target/classes:lib/*\" sa.bidengine.draft.DraftCommand"
                    + " <tender-spec.json> <مجلد وثائق العميل> <مجلد الإخراج>");
            return;
        }
        run(args, new AnthropicLlmClient());
    }

    /** نفس التشغيلة مع حقن مزوّد النموذج — يستخدمه اختبار الدخان بعميل وهمي. */
    public static void run(String[] args, LlmClient llm) throws Exception {
        Path specFile = Path.of(args[0]);
        Path docsDir = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        if (!Files.isRegularFile(specFile)) {
            System.err.println("ملف عقد المنافسة غير موجود: " + specFile.toAbsolutePath());
            return;
        }
        Files.createDirectories(outDir);

        System.out.println("1) بناء قاعدة معرفة العميل من: " + docsDir);
        ClientKnowledgeBase kb = ClientKnowledgeBase.build(docsDir, outDir.resolve("kb-index.json"));
        System.out.println("   " + kb.size() + " مقطعاً في kb-index.json");
        if (kb.size() == 0) {
            System.err.println("   تحذير: لا وثائق مدعومة (pdf/txt/md) في المجلد — كل الأقسام ستكون فجوات معلنة.");
        }

        System.out.println("2) قراءة المتطلبات الفنية من: " + specFile.getFileName());
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode spec = mapper.readTree(Files.readString(specFile, StandardCharsets.UTF_8));
        List<String> methodologyQueries = methodologyQueries(spec);
        System.out.println("   " + methodologyQueries.size() + " استعلاماً لقسم المنهجية");

        SectionWriter writer = new SectionWriter(llm, kb);

        System.out.println("3) توليد قسم «المنهجية»...");
        ProposalSection methodology = writer.write("methodology", "المنهجية",
                methodologyQueries, methodologyContext(spec));

        System.out.println("4) توليد قسم «سابقة الأعمال»...");
        ProposalSection pastWorks = writer.write("past-works", "سابقة الأعمال",
                PAST_WORKS_QUERIES,
                "اكتب سابقة أعمال العميل اعتماداً على ما ورد في وثائقه فقط — "
                        + "مشاريع وجهات وأرقام لم ترد حرفياً لا تُذكر.");

        List<ProposalSection> sections = List.of(methodology, pastWorks);

        // الشكل المتفق عليه حرفياً: {"sections":[...]} — وحدات أخرى تُبنى عليه.
        ObjectNode root = mapper.createObjectNode();
        root.set("sections", mapper.valueToTree(sections));
        Path jsonOut = outDir.resolve("proposal-sections.json");
        Files.writeString(jsonOut, mapper.writeValueAsString(root), StandardCharsets.UTF_8);

        Path htmlOut = outDir.resolve("sections-review.html");
        new SectionsReviewWriter().write(htmlOut, sections);

        long paragraphs = sections.stream().mapToLong(s -> s.paragraphs().size()).sum();
        long gaps = sections.stream()
                .flatMap(s -> s.paragraphs().stream())
                .filter(Paragraph::needsInput).count();
        System.out.println("---------------------------------------------");
        System.out.printf("النتيجة: قسمان | %d فقرة (%d منها فجوة تحتاج العميل)%n", paragraphs, gaps);
        System.out.println("   " + outDir.resolve("kb-index.json"));
        System.out.println("   " + jsonOut);
        System.out.println("   " + htmlOut);
        System.out.println("الخطوة التالية (يدوية وإلزامية): افتح sections-review.html، راجع كل فقرة");
        System.out.println("مقابل اقتباساتها، وخذ أسئلة الفجوات إلى العميل — الفجوات تُسد معه لا بالاختراع.");
    }

    /** استعلامات المنهجية: أوصاف بنود المتطلبات الفنية من عقد المنافسة (ثم البقية إن قلّت). */
    private static List<String> methodologyQueries(JsonNode spec) {
        List<String> technical = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (JsonNode r : spec.path("requirements")) {
            String text = r.path("description").asText("");
            if (text.isBlank()) text = r.path("sourceQuote").asText("");
            if (text.isBlank()) continue;
            if (r.path("category").asText("").contains("فني")) technical.add(text);
            else others.add(text);
        }
        List<String> queries = new ArrayList<>(technical);
        for (String q : others) {
            if (queries.size() >= MAX_METHODOLOGY_QUERIES) break;
            queries.add(q);
        }
        if (queries.isEmpty()) queries.add("منهجية تنفيذ نطاق العمل وخطة المشروع");
        return queries.size() <= MAX_METHODOLOGY_QUERIES
                ? queries : queries.subList(0, MAX_METHODOLOGY_QUERIES);
    }

    /** سياق مطالبة المنهجية: بنود المتطلبات الفنية كما وردت في عقد المنافسة. */
    private static String methodologyContext(JsonNode spec) {
        StringBuilder sb = new StringBuilder(
                "بنود المتطلبات الفنية من كراسة الشروط (صِغ منهجية تلبيها، "
                        + "وأسند كل قدرة تدّعيها للعميل باقتباس من مقاطعه):\n");
        int n = 0;
        for (JsonNode r : spec.path("requirements")) {
            if (!r.path("category").asText("").contains("فني")) continue;
            String text = r.path("description").asText("");
            if (text.isBlank()) text = r.path("sourceQuote").asText("");
            if (text.isBlank()) continue;
            sb.append("- ").append(text).append("\n");
            if (++n >= MAX_METHODOLOGY_QUERIES) break;
        }
        return n == 0 ? "" : sb.toString();
    }
}
