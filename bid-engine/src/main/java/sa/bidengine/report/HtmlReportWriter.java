package sa.bidengine.report;

import sa.bidengine.model.EvaluationCriterion;
import sa.bidengine.model.MandatoryDocument;
import sa.bidengine.model.Requirement;
import sa.bidengine.model.TenderSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * تصدير مصفوفة المتطلبات كصفحة HTML عربية بسيطة للمراجعة اليدوية بنداً-بنداً.
 * ملف واحد بلا اعتماديات ولا JavaScript — يُفتح في أي متصفح ويُطبع مباشرة.
 * عمود "تحقق يدوي" فارغ عمداً: يعبّئه المراجع أثناء المقارنة مع الكراسة الأصلية.
 */
public class HtmlReportWriter {

    public void write(Path out, TenderSpec spec) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("""
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
            <meta charset="UTF-8">
            <title>مصفوفة المتطلبات — مسودة للمراجعة</title>
            <style>
              body { font-family: "Segoe UI", Tahoma, Arial, sans-serif; margin: 2rem; color: #1a1a1a; }
              h1 { font-size: 1.4rem; } h2 { font-size: 1.15rem; margin-top: 2rem; }
              table { border-collapse: collapse; width: 100%; margin-top: .5rem; }
              th, td { border: 1px solid #999; padding: .45rem .6rem; text-align: right; vertical-align: top; }
              th { background: #f0f0f0; }
              td.quote { color: #333; font-size: .9rem; }
              td.check { min-width: 6rem; }
              .mandatory { color: #a40000; font-weight: bold; }
              .meta td:first-child { background: #f7f7f7; font-weight: bold; width: 12rem; }
              .draftnote { background: #fff6d9; border: 1px solid #d9b23d; padding: .7rem 1rem; margin: 1rem 0; }
              @media print { body { margin: 0; } .draftnote { border-width: 2px; } }
            </style>
            </head>
            <body>
            <h1>مصفوفة المتطلبات — مسودة للمراجعة البشرية</h1>
            <div class="draftnote">هذه مسودة مستخرجة آلياً. كل بند أدناه يجب التحقق منه يدوياً
            مقابل الكراسة الأصلية قبل أي استخدام — عمود «تحقق يدوي» مخصص لذلك.</div>
            """);

        h.append("<h2>بيانات المنافسة</h2>\n<table class=\"meta\">\n");
        metaRow(h, "اسم المنافسة", spec.tenderName());
        metaRow(h, "الجهة الحكومية", spec.governmentEntity());
        metaRow(h, "رقم المنافسة", spec.tenderNumber());
        metaRow(h, "موعد إقفال التقديم", spec.submissionDeadline());
        h.append("</table>\n");

        h.append("<h2>بنود المتطلبات (").append(spec.requirements().size()).append(")</h2>\n");
        h.append("""
            <table>
            <tr><th>المعرف</th><th>التصنيف</th><th>الوصف</th><th>الاقتباس الحرفي من الكراسة</th>
            <th>الصفحة</th><th>إلزامي؟</th><th>تحقق يدوي</th></tr>
            """);
        for (Requirement r : spec.requirements()) {
            h.append("<tr>")
             .append("<td>").append(esc(r.id())).append("</td>")
             .append("<td>").append(esc(r.category())).append("</td>")
             .append("<td>").append(esc(r.description())).append("</td>")
             .append("<td class=\"quote\">").append(esc(r.sourceQuote())).append("</td>")
             .append("<td>").append(r.sourcePage()).append("</td>")
             .append(r.mandatory() ? "<td class=\"mandatory\">إلزامي</td>" : "<td>—</td>")
             .append("<td class=\"check\"></td>")
             .append("</tr>\n");
        }
        h.append("</table>\n");

        h.append("<h2>معايير التقييم (").append(spec.evaluationCriteria().size()).append(")</h2>\n");
        h.append("""
            <table>
            <tr><th>المعيار</th><th>الوزن كما ورد</th><th>ملاحظات</th>
            <th>الاقتباس الحرفي</th><th>الصفحة</th><th>تحقق يدوي</th></tr>
            """);
        for (EvaluationCriterion c : spec.evaluationCriteria()) {
            h.append("<tr>")
             .append("<td>").append(esc(c.name())).append("</td>")
             .append("<td>").append(esc(c.weight())).append("</td>")
             .append("<td>").append(esc(c.notes())).append("</td>")
             .append("<td class=\"quote\">").append(esc(c.sourceQuote())).append("</td>")
             .append("<td>").append(c.sourcePage()).append("</td>")
             .append("<td class=\"check\"></td>")
             .append("</tr>\n");
        }
        h.append("</table>\n");

        h.append("<h2>المستندات الإلزامية (").append(spec.mandatoryDocuments().size()).append(")</h2>\n");
        h.append("""
            <table>
            <tr><th>المستند</th><th>الاقتباس الحرفي</th><th>الصفحة</th><th>تحقق يدوي</th></tr>
            """);
        for (MandatoryDocument d : spec.mandatoryDocuments()) {
            h.append("<tr>")
             .append("<td>").append(esc(d.name())).append("</td>")
             .append("<td class=\"quote\">").append(esc(d.sourceQuote())).append("</td>")
             .append("<td>").append(d.sourcePage()).append("</td>")
             .append("<td class=\"check\"></td>")
             .append("</tr>\n");
        }
        h.append("</table>\n</body>\n</html>\n");

        Files.writeString(out, h.toString(), StandardCharsets.UTF_8);
    }

    private void metaRow(StringBuilder h, String label, String value) {
        h.append("<tr><td>").append(esc(label)).append("</td><td>")
         .append(value == null || value.isBlank() ? "<em>لم يُستخرج — أكمله يدوياً</em>" : esc(value))
         .append("</td></tr>\n");
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
