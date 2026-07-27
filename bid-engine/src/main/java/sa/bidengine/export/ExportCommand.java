package sa.bidengine.export;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * أمر التصدير — العطلة الرابعة (الجزء الثاني): تجميع مسودة العرض النهائية docx.
 *
 * التشغيل (لا يستدعي النموذج اللغوي إطلاقاً — لا يحتاج ANTHROPIC_API_KEY):
 *   java -cp "lib/*:target/classes" sa.bidengine.export.ExportCommand <مجلد JSON> <مسار output.docx>
 *
 * المجلد يحوي مخرجات المراحل السابقة: tender-spec.json وproposal-sections.json
 * وcompliance.json — أي ملف غائب يُتخطى قسمه مع ملاحظة في الوثيقة.
 */
public class ExportCommand {

    public static void main(String[] args) throws Exception {
        run(args);
    }

    public static void run(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("الاستخدام: java -cp \"lib/*:target/classes\" "
                    + "sa.bidengine.export.ExportCommand <مجلد ملفات JSON> <مسار output.docx>");
            return;
        }
        Path dir = Path.of(args[0]);
        if (!Files.isDirectory(dir)) {
            System.err.println("المجلد غير موجود: " + dir.toAbsolutePath());
            return;
        }
        Path out = Path.of(args[1]);

        System.out.println("تجميع مسودة العرض من: " + dir.toAbsolutePath());
        new ProposalAssembler().assemble(dir, out);

        System.out.println("---------------------------------------------");
        System.out.println("كُتبت المسودة: " + out.toAbsolutePath());
        System.out.println("الخطوة التالية (يدوية وإلزامية): افتح المسودة وراجعها بنداً-بنداً");
        System.out.println("مقابل الكراسة ووثائق العميل — لا تُقدَّم قبل المراجعة البشرية.");
    }
}
