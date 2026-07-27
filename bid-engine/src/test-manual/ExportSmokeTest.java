import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.w3c.dom.Document;
import sa.bidengine.export.ExportCommand;
import sa.bidengine.model.EvaluationCriterion;
import sa.bidengine.model.MandatoryDocument;
import sa.bidengine.model.Requirement;
import sa.bidengine.model.TenderSpec;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * اختبار دخان يدوي لوحدة التصدير — خارج src/main عمداً حتى لا يدخل الـ jar.
 *
 * التشغيل من مجلد bid-engine:
 *   javac -encoding UTF-8 -cp "lib/*" -d target/classes $(find src/main -name '*.java')
 *   javac -encoding UTF-8 -cp "lib/*:target/classes" -d target/test-classes src/test-manual/ExportSmokeTest.java
 *   java -cp "lib/*:target/classes:target/test-classes" ExportSmokeTest
 *
 * ما يتحقق منه:
 *   1) ExportCommand ينتج ملف docx من ملفات JSON العينة الثلاثة.
 *   2) الأرشيف يُفتح بـ java.util.zip وكل مدخلاته الخمسة موجودة.
 *   3) word/document.xml يمر عبر DocumentBuilder بلا أخطاء (XML سليم البنية).
 *   4) النصوص العربية المتوقعة موجودة (الغلاف، التنبيه، الامتثال، needsInput، المصدر، الملحق).
 *   5) التسامح: غياب compliance.json لا يُسقط التصدير ويترك ملاحظة في الوثيقة.
 *
 * تنبيه مهم: هذا الاختبار لا يثبت أن Microsoft Word أو LibreOffice يفتح الملف —
 * ذلك تحقق يدوي إلزامي على جهاز المؤسس قبل اعتماد المخرج.
 */
public class ExportSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("target", "smoke-export");
        Files.createDirectories(dir);
        writeSampleInputs(dir);

        // 1) التشغيل الكامل بالملفات الثلاثة
        Path out = dir.resolve("output.docx");
        ExportCommand.run(new String[]{dir.toString(), out.toString()});
        check("ملف output.docx أُنشئ", Files.exists(out));

        String documentXml = readAndVerifyZip(out);
        Document dom = parseXml(documentXml);
        check("word/document.xml سليم البنية (DocumentBuilder)", dom != null);

        for (String expected : List.of(
                "توريد وتركيب أنظمة المراقبة الأمنية",                       // اسم المنافسة (الغلاف)
                "وزارة الشؤون البلدية والقروية والإسكان",                     // الجهة
                "241039010023",                                              // رقم المنافسة
                "مسودة آلية — لا تُقدَّم قبل المراجعة البشرية بنداً-بنداً",  // التنبيه البارز
                "مصفوفة الامتثال",                                           // عنوان القسم
                "الملخص: مُغطى 1 | ناقص 1 | يحتاج مدخلاً 1",                  // ملخص الأعداد
                "مُغطى", "ناقص", "يحتاج مدخلاً",                              // الحالات الثلاث
                "المنهجية",                                                  // قسم من proposal-sections
                "يحتاج مدخلاً من العميل: كم عدد الكوادر السعودية",            // فقرة needsInput
                "[المصدر: ملف-الشركة-التعريفي.pdf، ص 4]",                    // سطر الإسناد
                "ملحق: مصفوفة المتطلبات",                                    // الملحق
                "REQ-001", "REQ-002", "REQ-003",                             // معرفات البنود
                "إلزامي")) {                                                 // عمود إلزامي؟
            check("النص موجود: " + expected, documentXml.contains(expected));
        }
        check("تهريب المحارف: علامة & في الوصف هُرّبت إلى &amp;",
                documentXml.contains("الصيانة &amp; الدعم الفني"));

        // 2) التسامح: مجلد بلا compliance.json — القسم يُتخطى مع ملاحظة، والتصدير لا يفشل
        Path dir2 = Path.of("target", "smoke-export-missing");
        Files.createDirectories(dir2);
        Files.copy(dir.resolve("tender-spec.json"), dir2.resolve("tender-spec.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(dir.resolve("proposal-sections.json"), dir2.resolve("proposal-sections.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path out2 = dir2.resolve("output.docx");
        ExportCommand.run(new String[]{dir2.toString(), out2.toString()});
        String documentXml2 = readAndVerifyZip(out2);
        check("التسامح: التصدير نجح رغم غياب compliance.json", parseXml(documentXml2) != null);
        check("التسامح: ملاحظة الملف الغائب موجودة في الوثيقة",
                documentXml2.contains("compliance.json") && documentXml2.contains("غير موجود"));

        System.out.println("=============================================");
        System.out.printf("نتيجة اختبار الدخان: نجح %d | فشل %d%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    /** يفتح الأرشيف بـ java.util.zip، يتحقق من وجود المدخلات الخمسة، ويعيد نص word/document.xml. */
    private static String readAndVerifyZip(Path docx) throws Exception {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            for (String name : List.of("[Content_Types].xml", "_rels/.rels",
                    "word/_rels/document.xml.rels", "word/document.xml", "word/styles.xml")) {
                check("مدخل الأرشيف موجود: " + name + " (" + docx.getFileName() + ")",
                        zip.getEntry(name) != null);
            }
            ZipEntry entry = zip.getEntry("word/document.xml");
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    /** يمرر النص عبر DocumentBuilder — يعيد null إن كان XML غير سليم البنية. */
    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            return f.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            System.err.println("  خطأ بنية XML: " + e.getMessage());
            return null;
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  [نجح] " + name); }
        else    { failed++; System.err.println("  [فشل] " + name); }
    }

    /** ملفات JSON العينة الثلاثة ببيانات عربية حقيقية الشكل — بنفس أشكال العقود المتفق عليها. */
    private static void writeSampleInputs(Path dir) throws Exception {
        // tender-spec.json: أصناف sa.bidengine.model مسلسلة بـ Jackson (نفس ما ينتجه Main)
        TenderSpec spec = new TenderSpec(
                "توريد وتركيب أنظمة المراقبة الأمنية لمباني الأمانة",
                "وزارة الشؤون البلدية والقروية والإسكان",
                "241039010023",
                "1447/05/12هـ الموافق 2025/11/03م",
                List.of(
                        new Requirement("REQ-001", "فني",
                                "توريد كاميرات مراقبة بدقة لا تقل عن 4K مع رؤية ليلية",
                                "يجب أن تكون الكاميرات بدقة لا تقل عن 4K مزودة بخاصية الرؤية الليلية",
                                12, true),
                        new Requirement("REQ-002", "إداري",
                                "تقديم خطة عمل تفصيلية لأعمال الصيانة & الدعم الفني",
                                "على المتنافس تقديم خطة تفصيلية لأعمال الصيانة والدعم الفني",
                                17, true),
                        new Requirement("REQ-003", "محتوى محلي",
                                "نسبة المحتوى المحلي لا تقل عن 40% وفق شهادة هيئة المحتوى المحلي",
                                "ألا تقل نسبة المحتوى المحلي عن 40%",
                                21, false)),
                List.of(new EvaluationCriterion("الخبرات السابقة", "30%",
                        "مشاريع مماثلة خلال خمس سنوات",
                        "تُمنح 30% من الدرجة الفنية للخبرات السابقة", 25)),
                List.of(new MandatoryDocument("شهادة تصنيف سارية",
                        "يجب إرفاق شهادة تصنيف سارية المفعول", 8)));
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(dir.resolve("tender-spec.json"),
                mapper.writeValueAsString(spec), StandardCharsets.UTF_8);

        // proposal-sections.json: الشكل الحرفي المتفق عليه، مع فقرة needsInput
        Files.writeString(dir.resolve("proposal-sections.json"), """
            {"sections":[
              {"id":"methodology","title":"المنهجية","paragraphs":[
                {"text":"تعتمد منهجية التنفيذ على تقسيم المشروع إلى ثلاث مراحل: المسح الميداني لمواقع التركيب، ثم التوريد والتركيب على دفعات، ثم الاختبار والتسليم الابتدائي.",
                 "needsInput":false,"clientQuestion":"",
                 "citations":[{"chunkId":"KB-0001","source":"ملف-الشركة-التعريفي.pdf","page":4,"quote":"نعتمد منهجية التنفيذ المرحلي في مشاريع الأنظمة الأمنية"}]},
                {"text":"","needsInput":true,
                 "clientQuestion":"كم عدد الكوادر السعودية المعتمدة لديكم لأعمال التركيب والصيانة؟",
                 "citations":[]}
              ]},
              {"id":"past-projects","title":"سابقة الأعمال","paragraphs":[
                {"text":"نفّذت الشركة مشروع أنظمة المراقبة لأمانة المنطقة الشرقية بقيمة 2.4 مليون ريال وسُلّم في موعده.",
                 "needsInput":false,"clientQuestion":"",
                 "citations":[{"chunkId":"KB-0007","source":"سابقة-الأعمال.pdf","page":2,"quote":"مشروع أنظمة المراقبة لأمانة المنطقة الشرقية"}]}
              ]}
            ]}
            """, StandardCharsets.UTF_8);

        // compliance.json: الشكل الحرفي المتفق عليه — بنود بالحالات الثلاث
        Files.writeString(dir.resolve("compliance.json"), """
            {"summary":{"covered":1,"missing":1,"needsInput":1},
             "items":[
              {"requirementId":"REQ-001","status":"COVERED",
               "evidenceQuote":"كاميرات بدقة 4K مع رؤية ليلية مثبتة في ثلاثة مشاريع سابقة",
               "evidenceSource":"ملف-الشركة-التعريفي.pdf","evidencePage":6,
               "notes":"","clientQuestion":""},
              {"requirementId":"REQ-002","status":"MISSING",
               "evidenceQuote":"","evidenceSource":"","evidencePage":0,
               "notes":"لا توجد خطة صيانة موثقة في وثائق العميل","clientQuestion":""},
              {"requirementId":"REQ-003","status":"NEEDS_INPUT",
               "evidenceQuote":"","evidenceSource":"","evidencePage":0,
               "notes":"","clientQuestion":"ما نسبة المحتوى المحلي الموثقة في شهادتكم الحالية؟"}
             ]}
            """, StandardCharsets.UTF_8);
    }
}
