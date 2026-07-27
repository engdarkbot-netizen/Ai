package sa.bidengine.comply;

import sa.bidengine.llm.AnthropicLlmClient;
import sa.bidengine.model.Requirement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * نقطة تشغيل طبقة التحقق — العطلة الرابعة (الجزء الأول):
 *   java -cp "target/classes:lib/*" sa.bidengine.comply.ComplyCommand \
 *       tender-spec.json kb-index.json مجلد-الإخراج
 *
 * المدخلات:
 *   tender-spec.json  ناتج خط الاستخراج (بنود المتطلبات)
 *   kb-index.json     فهرس قاعدة معرفة العميل (تنتجه وحدة قاعدة المعرفة)
 *
 * المخرجات (في مجلد الإخراج):
 *   compliance.json         مصفوفة الامتثال المهيكلة — تقرؤها وحدة التصدير النهائي
 *   compliance-review.html  مصفوفة المراجعة اليدوية — افتحها وتحقق حكماً-حكماً
 *
 * الناتج مسودة للمراجعة البشرية دائماً — خصوصاً بنود «يحتاج مدخلاً»:
 * أسئلة العميل فيها هي مادة مكالمة سد الفجوات، لا تُرسل للعميل بلا مراجعة.
 */
public class ComplyCommand {

    public static void main(String[] args) throws Exception {
        run(args);
    }

    public static void run(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("الاستخدام: java -cp \"target/classes:lib/*\" sa.bidengine.comply.ComplyCommand"
                    + " <tender-spec.json> <kb-index.json> <مجلد الإخراج>");
            return;
        }
        Path specPath = Path.of(args[0]);
        Path kbPath = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        if (!Files.exists(specPath)) {
            System.err.println("الملف غير موجود: " + specPath.toAbsolutePath());
            return;
        }
        if (!Files.exists(kbPath)) {
            System.err.println("الملف غير موجود: " + kbPath.toAbsolutePath());
            return;
        }
        Files.createDirectories(outDir);

        System.out.println("1) قراءة بنود المتطلبات: " + specPath);
        List<Requirement> requirements = new TenderSpecReader().readRequirements(specPath);
        System.out.println("   " + requirements.size() + " بنداً");
        if (requirements.isEmpty()) {
            System.err.println("لا بنود في tender-spec.json — شغّل خط الاستخراج أولاً.");
            return;
        }

        System.out.println("2) قراءة فهرس قاعدة معرفة العميل: " + kbPath);
        List<KbChunk> chunks = new KbIndexReader().read(kbPath);
        System.out.println("   " + chunks.size() + " مقطعاً");
        if (chunks.isEmpty()) {
            System.err.println("تحذير: الفهرس فارغ — كل البنود ستظهر ناقصة أو تحتاج مدخلاً.");
        }

        System.out.println("3) فحص الامتثال عبر النموذج اللغوي (دفعات من "
                + ComplianceChecker.BATCH_SIZE + " بنود)...");
        List<ComplianceItem> items =
                new ComplianceChecker(new AnthropicLlmClient()).check(requirements, chunks);

        Path jsonOut = outDir.resolve("compliance.json");
        new ComplianceJsonWriter().write(jsonOut, items);

        Path htmlOut = outDir.resolve("compliance-review.html");
        new ComplianceHtmlWriter().write(htmlOut, requirements, items);

        long covered = items.stream().filter(i -> ComplianceItem.COVERED.equals(i.status())).count();
        long missing = items.stream().filter(i -> ComplianceItem.MISSING.equals(i.status())).count();
        long needsInput = items.stream().filter(i -> ComplianceItem.NEEDS_INPUT.equals(i.status())).count();
        System.out.println("---------------------------------------------");
        System.out.printf("النتيجة: %d مُغطى | %d ناقص | %d يحتاج مدخلاً (من أصل %d بنداً)%n",
                covered, missing, needsInput, items.size());
        System.out.println("   " + jsonOut);
        System.out.println("   " + htmlOut);
        System.out.println("الخطوة التالية (يدوية وإلزامية): افتح compliance-review.html وتحقق");
        System.out.println("حكماً-حكماً، وجهّز أسئلة «يحتاج مدخلاً» لمكالمة سد الفجوات مع العميل.");
    }
}
