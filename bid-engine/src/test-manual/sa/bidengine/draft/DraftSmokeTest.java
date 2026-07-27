package sa.bidengine.draft;

// اختبار دخان يدوي للعطلة الثالثة — خارج src/main عمداً حتى لا يدخل الـ jar.
//
// التشغيل من مجلد bid-engine:
//   javac -encoding UTF-8 -cp "lib/*" -d target/classes $(find src -name '*.java')
//   java -cp "target/classes:lib/*" sa.bidengine.draft.DraftSmokeTest
//
// ماذا يثبت (بعميل LLM وهمي بلا شبكة ولا مفتاح):
//   1) الاقتباس الصحيح (موجود حرفياً في المقطع) يمر ويُملأ مصدره من المقطع نفسه.
//   2) الاقتباس المزوّر (غير موجود في المقطع) يُحذف، وفقرته تتحول إلى needsInput=true
//      مع سؤال واضح للعميل — حائط الهلوسة ميكانيكي لا بالمطالبة فقط.
//   3) الفقرة بلا اقتباسات إطلاقاً تتحول إلى needsInput=true كذلك.
//   4) kb-index.json وproposal-sections.json يخرجان سليمين بالشكل المتفق عليه،
//      وsections-review.html يبرز الفجوات.

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sa.bidengine.llm.LlmClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DraftSmokeTest {

    /** الاقتباس الصحيح — جملة واردة حرفياً في وثيقة العميل أدناه. */
    private static final String VALID_QUOTE =
            "نفذت الشركة مشروع تطوير البوابة الإلكترونية لوزارة التعليم";

    /** الاقتباس المزوّر — لا يرد في أي وثيقة، ويجب أن يُحذف ميكانيكياً. */
    private static final String FORGED_QUOTE =
            "حصلت الشركة على شهادة الآيزو 27001 عام 2022";

    /** عميل وهمي: يعيد دائماً ثلاث فقرات — صحيحة، مزوّرة الاقتباس، بلا اقتباسات. */
    private static class StubLlmClient implements LlmClient {
        int calls = 0;

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            calls++;
            return """
                {"paragraphs":[
                  {"text":"نفذت الشركة مشروع تطوير البوابة الإلكترونية لوزارة التعليم وسلمته في موعده.",
                   "needsInput":false,"clientQuestion":"",
                   "citations":[{"chunkId":"KB-0001","quote":"%s"}]},
                  {"text":"الشركة حاصلة على شهادة الآيزو في أمن المعلومات.",
                   "needsInput":false,"clientQuestion":"",
                   "citations":[{"chunkId":"KB-0001","quote":"%s"}]},
                  {"text":"تمتلك الشركة فريقاً من مئة مهندس معتمد.",
                   "needsInput":false,"clientQuestion":"","citations":[]}
                ]}
                """.formatted(VALID_QUOTE, FORGED_QUOTE);
        }
    }

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path base = Path.of("target", "smoke-test");
        deleteRecursively(base);
        Path docsDir = Files.createDirectories(base.resolve("client-docs"));
        Path outDir = base.resolve("out");

        // وثيقتا عميل صغيرتان (txt وmd) — الاقتباس الصحيح وارد حرفياً في الأولى.
        Files.writeString(docsDir.resolve("01-سابقة-الاعمال.txt"), """
                ومن أبرز المشاريع السابقة المنفذة لدى الشركة:
                نفذت الشركة مشروع تطوير البوابة الإلكترونية لوزارة التعليم وسلمته
                في الموعد المحدد بتقييم نهائي مرتفع من الجهة المالكة.
                """, StandardCharsets.UTF_8);
        Files.writeString(docsDir.resolve("02-المنهجية.md"), """
                منهجية تنفيذ المشروع لدينا تعتمد على خطة عمل مرحلية تبدأ بتحليل
                المتطلبات ثم التصميم فالتنفيذ فالاختبار فالتسليم مع ضبط الجودة.
                """, StandardCharsets.UTF_8);

        // عقد منافسة مصغر ببند فني واحد — مصدر استعلامات قسم المنهجية.
        Path specFile = base.resolve("tender-spec-fixture.json");
        Files.writeString(specFile, """
                {"tenderName":"منافسة تجريبية","governmentEntity":"جهة تجريبية",
                 "tenderNumber":"0000","submissionDeadline":"",
                 "requirements":[{"id":"REQ-001","category":"فني",
                   "description":"تقديم منهجية تنفيذ المشروع وخطة العمل التفصيلية",
                   "sourceQuote":"يلتزم المتنافس بتقديم منهجية تنفيذ المشروع",
                   "sourcePage":1,"mandatory":true}],
                 "evaluationCriteria":[],"mandatoryDocuments":[]}
                """, StandardCharsets.UTF_8);

        StubLlmClient stub = new StubLlmClient();
        DraftCommand.run(new String[]{specFile.toString(), docsDir.toString(), outDir.toString()}, stub);

        // ---- التحقق ----
        check(stub.calls == 2, "استدعاء النموذج مرتين (قسم لكل استدعاء)، فعلياً: " + stub.calls);

        ObjectMapper mapper = new ObjectMapper();
        Path kbFile = outDir.resolve("kb-index.json");
        check(Files.exists(kbFile), "وجود kb-index.json");
        JsonNode kb = mapper.readTree(Files.readString(kbFile, StandardCharsets.UTF_8));
        check(kb.path("chunks").isArray() && kb.path("chunks").size() == 2,
                "الفهرس يحوي مقطعين، فعلياً: " + kb.path("chunks").size());
        JsonNode first = kb.path("chunks").path(0);
        check("KB-0001".equals(first.path("id").asText()), "أول معرف KB-0001");
        check(first.has("source") && first.has("page") && first.has("text"),
                "حقول المقطع (source/page/text) موجودة");
        check(first.path("page").asInt(-1) == 0, "page=0 لملف txt");

        Path sectionsFile = outDir.resolve("proposal-sections.json");
        check(Files.exists(sectionsFile), "وجود proposal-sections.json");
        JsonNode root = mapper.readTree(Files.readString(sectionsFile, StandardCharsets.UTF_8));
        JsonNode sections = root.path("sections");
        check(sections.isArray() && sections.size() == 2, "قسمان في الملف");
        check("methodology".equals(sections.path(0).path("id").asText()), "القسم الأول methodology");
        check("past-works".equals(sections.path(1).path("id").asText()), "القسم الثاني past-works");
        check("المنهجية".equals(sections.path(0).path("title").asText()), "عنوان «المنهجية»");
        check("سابقة الأعمال".equals(sections.path(1).path("title").asText()), "عنوان «سابقة الأعمال»");

        for (JsonNode section : sections) {
            String sid = section.path("id").asText();
            JsonNode ps = section.path("paragraphs");
            check(ps.size() == 3, sid + ": ثلاث فقرات، فعلياً: " + ps.size());

            JsonNode p1 = ps.path(0);
            check(!p1.path("needsInput").asBoolean(true), sid + ": الفقرة المسنودة تمر (needsInput=false)");
            check(p1.path("citations").size() == 1, sid + ": اقتباس واحد صالح في الفقرة الأولى");
            JsonNode c1 = p1.path("citations").path(0);
            check(VALID_QUOTE.equals(c1.path("quote").asText()), sid + ": نص الاقتباس الصحيح كما هو");
            check("KB-0001".equals(c1.path("chunkId").asText()), sid + ": chunkId صحيح");
            check("01-سابقة-الاعمال.txt".equals(c1.path("source").asText()),
                    sid + ": المصدر مُلئ من المقطع نفسه");

            JsonNode p2 = ps.path(1);
            check(p2.path("needsInput").asBoolean(false),
                    sid + ": فقرة الاقتباس المزوّر تحولت إلى needsInput=true");
            check(p2.path("citations").size() == 0, sid + ": الاقتباس المزوّر حُذف");
            check(!p2.path("clientQuestion").asText("").isBlank(), sid + ": سؤال عميل واضح للفقرة المزوّرة");
            check(p2.path("text").asText("x").isBlank(), sid + ": النص الادعائي المزوّر لم يمر");

            JsonNode p3 = ps.path(2);
            check(p3.path("needsInput").asBoolean(false),
                    sid + ": الفقرة بلا اقتباسات تحولت إلى needsInput=true");
            check(!p3.path("clientQuestion").asText("").isBlank(), sid + ": سؤال عميل للفقرة بلا اقتباسات");
        }

        Path htmlFile = outDir.resolve("sections-review.html");
        check(Files.exists(htmlFile), "وجود sections-review.html");
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        check(html.contains("needsinput"), "صفحة المراجعة تبرز فجوات needsInput");
        check(html.contains(VALID_QUOTE), "صفحة المراجعة تعرض الاقتباس الصحيح");
        check(!html.contains(FORGED_QUOTE), "الاقتباس المزوّر غائب عن صفحة المراجعة");
        check(html.contains("فجوة — تحتاج مدخلاً من العميل"), "وسم الفجوة التحذيري ظاهر");

        System.out.println("=============================================");
        if (failures.isEmpty()) {
            System.out.println("اختبار الدخان: نجحت كل الفحوص.");
        } else {
            System.out.println("اختبار الدخان: فشل " + failures.size() + " فحصاً:");
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }

    private static void check(boolean condition, String name) {
        if (condition) System.out.println("  نجاح: " + name);
        else failures.add(name);
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(x -> x.toFile().delete());
        }
    }
}
