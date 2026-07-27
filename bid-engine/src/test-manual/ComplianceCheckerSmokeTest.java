import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sa.bidengine.comply.ComplianceChecker;
import sa.bidengine.comply.ComplianceHtmlWriter;
import sa.bidengine.comply.ComplianceItem;
import sa.bidengine.comply.ComplianceJsonWriter;
import sa.bidengine.comply.KbChunk;
import sa.bidengine.comply.KbIndexReader;
import sa.bidengine.comply.TenderSpecReader;
import sa.bidengine.llm.LlmClient;
import sa.bidengine.model.Requirement;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * اختبار دخان يدوي لطبقة التحقق (مصفوفة الامتثال) — بعميل نموذج وهمي، بلا شبكة ولا مفتاح.
 *
 * هذا الملف خارج src/main عمداً حتى لا يدخل الـ jar — Maven لا يرى src/test-manual.
 *
 * التشغيل من مجلد bid-engine:
 *   javac -encoding UTF-8 -cp "lib/*" -d target/classes $(find src -name '*.java')
 *   java -cp "target/classes:lib/*" ComplianceCheckerSmokeTest
 *
 * السيناريو: ثلاثة بنود وعميل وهمي يعيد:
 *   REQ-001  COVERED بدليل حرفي صحيح موجود في المقطع KB-0001 (بمسافات مختلفة عمداً
 *            لإثبات أن التحقق يطبّع المسافات قبل المطابقة)
 *   REQ-002  COVERED بدليل مزوّر غير موجود في المقطع KB-0002
 *   REQ-003  لا رد إطلاقاً
 *
 * النجاح المطلوب (حائط الهلوسة ميكانيكياً):
 *   REQ-001 يمر COVERED | REQ-002 ينقلب NEEDS_INPUT بسؤال للعميل | REQ-003 يصير MISSING
 *   وcompliance.json وcompliance-review.html سليمان والأعداد في summary صحيحة (1/1/1).
 */
public class ComplianceCheckerSmokeTest {

    /** عميل وهمي: يتجاهل المطالبة ويعيد رداً مُعداً سلفاً بالسيناريو أعلاه. */
    static class StubLlmClient implements LlmClient {
        @Override
        public String complete(String systemPrompt, String userPrompt) {
            return """
                {"items":[
                  {"requirementId":"REQ-001","status":"COVERED","chunkId":"KB-0001",
                   "evidenceQuote":"حاصلة على شهادة الأيزو 9001 سارية المفعول حتى نهاية عام 2027",
                   "clientQuestion":"","notes":""},
                  {"requirementId":"REQ-002","status":"COVERED","chunkId":"KB-0002",
                   "evidenceQuote":"نفّذت الشركة عشرين مشروعاً حكومياً مماثلاً خلال الخمس سنوات الماضية",
                   "clientQuestion":"","notes":""}
                ]}
                """;
        }
    }

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("target", "smoke-comply");
        Files.createDirectories(dir);

        // 1) تجهيز المدخلات بالشكلين الحرفيين المتفق عليهما — وقراءتهما بقارئي الحزمة نفسيهما.
        Path specPath = dir.resolve("tender-spec.json");
        Files.writeString(specPath, """
            {"tenderName":"منافسة تجريبية","governmentEntity":"جهة تجريبية",
             "tenderNumber":"T-001","submissionDeadline":"",
             "requirements":[
               {"id":"REQ-001","category":"إداري","description":"تقديم شهادة الأيزو 9001 سارية المفعول",
                "sourceQuote":"يجب على المتنافس تقديم شهادة الأيزو 9001 سارية المفعول","sourcePage":4,"mandatory":true},
               {"id":"REQ-002","category":"فني","description":"إثبات خبرة سابقة في مشاريع حكومية مماثلة",
                "sourceQuote":"يشترط إثبات خبرة سابقة في مشاريع حكومية مماثلة","sourcePage":6,"mandatory":true},
               {"id":"REQ-003","category":"مالي","description":"تقديم ضمان ابتدائي بنسبة واحد بالمئة",
                "sourceQuote":"يقدم المتنافس ضماناً ابتدائياً بنسبة واحد بالمئة","sourcePage":9,"mandatory":true}],
             "evaluationCriteria":[],"mandatoryDocuments":[]}
            """, StandardCharsets.UTF_8);

        // مقطع KB-0001 يحوي دليل REQ-001 الحقيقي لكن بمسافة مزدوجة وسطر جديد —
        // لإثبات أن حائط الهلوسة يطبّع المسافات قبل المطابقة الحرفية.
        Path kbPath = dir.resolve("kb-index.json");
        Files.writeString(kbPath, """
            {"chunks":[
              {"id":"KB-0001","source":"ملف-الجودة.pdf","page":3,
               "text":"الشركة حاصلة على شهادة  الأيزو 9001 سارية المفعول\\nحتى نهاية عام 2027 من جهة مانحة معتمدة."},
              {"id":"KB-0002","source":"سابقة-الأعمال.pdf","page":7,
               "text":"نفّذت الشركة ثلاثة مشاريع في القطاع الخاص خلال العامين الماضيين في مجال الصيانة."}]}
            """, StandardCharsets.UTF_8);

        List<Requirement> requirements = new TenderSpecReader().readRequirements(specPath);
        List<KbChunk> chunks = new KbIndexReader().read(kbPath);
        check(requirements.size() == 3, "قراءة 3 بنود من tender-spec.json");
        check(chunks.size() == 2, "قراءة مقطعين من kb-index.json");

        // 2) الفحص بالعميل الوهمي.
        List<ComplianceItem> items = new ComplianceChecker(new StubLlmClient()).check(requirements, chunks);
        check(items.size() == 3, "حكم واحد لكل بند — لا بند بلا حكم");

        ComplianceItem i1 = items.get(0), i2 = items.get(1), i3 = items.get(2);

        check(i1.requirementId().equals("REQ-001") && i1.status().equals(ComplianceItem.COVERED),
                "REQ-001 يمر COVERED (الدليل الحقيقي موجود في المقطع بعد تطبيع المسافات)");
        check("ملف-الجودة.pdf".equals(i1.evidenceSource()) && i1.evidencePage() == 3,
                "دليل REQ-001 يحمل مصدر المقطع وصفحته");
        check(!i1.evidenceQuote().isBlank(), "دليل REQ-001 غير فارغ");

        check(i2.requirementId().equals("REQ-002") && i2.status().equals(ComplianceItem.NEEDS_INPUT),
                "REQ-002 ينقلب NEEDS_INPUT (الدليل مزوّر — غير موجود في KB-0002)");
        check(i2.evidenceQuote().isBlank(), "الدليل المزوّر لا يتسرب إلى الناتج");
        check(!i2.clientQuestion().isBlank(), "REQ-002 معه سؤال واضح للعميل");
        check(i2.notes().contains("غير موجود"), "ملاحظة REQ-002 توثق أن النموذج ادعى تغطية بلا دليل");

        check(i3.requirementId().equals("REQ-003") && i3.status().equals(ComplianceItem.MISSING),
                "REQ-003 (تجاهله النموذج) يصير MISSING افتراضاً");

        // 3) الملفان الناتجان: سلامة الشكل وصحة الأعداد.
        Path jsonOut = dir.resolve("compliance.json");
        new ComplianceJsonWriter().write(jsonOut, items);
        Path htmlOut = dir.resolve("compliance-review.html");
        new ComplianceHtmlWriter().write(htmlOut, requirements, items);

        JsonNode root = new ObjectMapper().readTree(jsonOut.toFile());
        check(root.path("summary").path("covered").asInt(-1) == 1, "summary.covered == 1");
        check(root.path("summary").path("missing").asInt(-1) == 1, "summary.missing == 1");
        check(root.path("summary").path("needsInput").asInt(-1) == 1, "summary.needsInput == 1");
        check(root.path("items").size() == 3, "compliance.json يحوي 3 عناصر");
        JsonNode first = root.path("items").get(0);
        check(first.has("requirementId") && first.has("status") && first.has("evidenceQuote")
                        && first.has("evidenceSource") && first.has("evidencePage")
                        && first.has("notes") && first.has("clientQuestion"),
                "عنصر compliance.json بكل الحقول المتفق عليها حرفياً");

        String html = Files.readString(htmlOut, StandardCharsets.UTF_8);
        check(html.contains("dir=\"rtl\"") && html.contains("مُغطى: 1")
                        && html.contains("ناقص: 1") && html.contains("يحتاج مدخلاً: 1")
                        && html.contains("تحقق يدوي"),
                "compliance-review.html صفحة RTL بملخص الأعداد وعمود التحقق اليدوي");

        // النتيجة.
        if (failures.isEmpty()) {
            System.out.println("نجح اختبار الدخان: كل الفحوص (" + checks + ") سليمة.");
            System.out.println("الملفات الناتجة للمعاينة اليدوية: " + jsonOut + " و " + htmlOut);
        } else {
            System.err.println("فشل اختبار الدخان — الفحوص الساقطة:");
            failures.forEach(f -> System.err.println("  - " + f));
            System.exit(1);
        }
    }

    private static int checks = 0;

    private static void check(boolean ok, String label) {
        checks++;
        System.out.println((ok ? "  [نجح] " : "  [فشل] ") + label);
        if (!ok) failures.add(label);
    }
}
