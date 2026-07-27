package sa.bidengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import sa.bidengine.llm.AnthropicLlmClient;
import sa.bidengine.model.Requirement;
import sa.bidengine.model.TenderSpec;
import sa.bidengine.pdf.PdfTextExtractor;
import sa.bidengine.pipeline.RequirementExtractor;
import sa.bidengine.pipeline.TenderInfoExtractor;
import sa.bidengine.pipeline.TextChunker;
import sa.bidengine.report.HtmlReportWriter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * نقطة التشغيل — العطلة الأولى:
 *   java -jar bid-engine.jar path/to/كراسة.pdf
 *
 * المخرجات (بجانب ملف الكراسة):
 *   requirements.json  مصفوفة المتطلبات وحدها
 *   tender-spec.json   عقد النظام كاملاً (بيانات المنافسة + المتطلبات + معايير التقييم + المستندات)
 *   review.html        مصفوفة المراجعة اليدوية — افتحها بجانب الكراسة وتحقق بنداً-بنداً
 *
 * معيار نجاح العطلة: دقة تفوق 90% على 3 كراسات حقيقية — تتحقق منها يدوياً بنداً بنداً.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("الاستخدام: java -jar bid-engine.jar <ملف الكراسة.pdf>");
            return;
        }
        File pdf = new File(args[0]);
        if (!pdf.exists()) {
            System.err.println("الملف غير موجود: " + pdf.getAbsolutePath());
            return;
        }

        System.out.println("1) استخراج نص الكراسة: " + pdf.getName());
        PdfTextExtractor extractor = new PdfTextExtractor();
        var pages = extractor.extract(pdf);
        double emptyRatio = extractor.emptyPageRatio(pages);
        System.out.printf("   %d صفحة | نسبة الصفحات الفارغة: %.0f%%%n", pages.size(), emptyRatio * 100);
        if (emptyRatio > 0.3) {
            System.err.println("   تحذير: الكراسة تبدو ممسوحة ضوئياً — النتائج ستكون ناقصة حتى تضيف OCR.");
        }

        System.out.println("2) تقطيع النص...");
        var chunks = new TextChunker(24_000, 1_500).chunk(pages);
        System.out.println("   " + chunks.size() + " مقطعاً");

        var llm = new AnthropicLlmClient();

        System.out.println("3) استخراج المتطلبات عبر النموذج اللغوي...");
        List<Requirement> requirements = new RequirementExtractor(llm).extract(chunks);

        System.out.println("4) استخراج بيانات المنافسة ومعايير التقييم والمستندات الإلزامية...");
        var info = new TenderInfoExtractor(llm).extract(chunks);

        TenderSpec spec = new TenderSpec(
                info.tenderName(), info.governmentEntity(), info.tenderNumber(),
                info.submissionDeadline(), requirements,
                info.evaluationCriteria(), info.mandatoryDocuments());

        String dir = pdf.getParent() == null ? "." : pdf.getParent();
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        Path reqOut = Path.of(dir, "requirements.json");
        Files.writeString(reqOut, mapper.writeValueAsString(requirements), StandardCharsets.UTF_8);

        Path specOut = Path.of(dir, "tender-spec.json");
        Files.writeString(specOut, mapper.writeValueAsString(spec), StandardCharsets.UTF_8);

        Path htmlOut = Path.of(dir, "review.html");
        new HtmlReportWriter().write(htmlOut, spec);

        long mandatory = requirements.stream().filter(Requirement::mandatory).count();
        System.out.println("---------------------------------------------");
        System.out.printf("النتيجة: %d بنداً (%d منها إلزامي) | %d معيار تقييم | %d مستنداً إلزامياً%n",
                requirements.size(), mandatory,
                spec.evaluationCriteria().size(), spec.mandatoryDocuments().size());
        System.out.println("   " + reqOut);
        System.out.println("   " + specOut);
        System.out.println("   " + htmlOut);
        System.out.println("الخطوة التالية (يدوية وإلزامية): افتح الكراسة وreview.html جنباً إلى جنب");
        System.out.println("وتحقق بنداً بنداً — هذا التحقق هو معيار نجاح العطلة الأولى، لا الكود.");
    }
}
