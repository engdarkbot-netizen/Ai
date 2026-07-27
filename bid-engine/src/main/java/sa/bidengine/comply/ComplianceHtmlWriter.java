package sa.bidengine.comply;

import sa.bidengine.model.Requirement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * تصدير مصفوفة الامتثال كصفحة HTML عربية بسيطة للمراجعة اليدوية بنداً-بنداً —
 * بنفس أسلوب report.HtmlReportWriter: ملف واحد بلا اعتماديات ولا JavaScript.
 * ألوان الحالات: مُغطى أخضر، ناقص أحمر، يحتاج مدخلاً أصفر.
 * عمود "تحقق يدوي" فارغ عمداً: يعبّئه المراجع أثناء المقارنة مع وثائق العميل.
 */
public class ComplianceHtmlWriter {

    public void write(Path out, List<Requirement> requirements, List<ComplianceItem> items)
            throws IOException {
        Map<String, Requirement> byId = new LinkedHashMap<>();
        for (Requirement r : requirements) byId.put(r.id(), r);

        long covered = items.stream().filter(i -> ComplianceItem.COVERED.equals(i.status())).count();
        long missing = items.stream().filter(i -> ComplianceItem.MISSING.equals(i.status())).count();
        long needsInput = items.stream().filter(i -> ComplianceItem.NEEDS_INPUT.equals(i.status())).count();

        StringBuilder h = new StringBuilder();
        h.append("""
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
            <meta charset="UTF-8">
            <title>مصفوفة الامتثال — مسودة للمراجعة</title>
            <style>
              body { font-family: "Segoe UI", Tahoma, Arial, sans-serif; margin: 2rem; color: #1a1a1a; }
              h1 { font-size: 1.4rem; } h2 { font-size: 1.15rem; margin-top: 2rem; }
              table { border-collapse: collapse; width: 100%; margin-top: .5rem; }
              th, td { border: 1px solid #999; padding: .45rem .6rem; text-align: right; vertical-align: top; }
              th { background: #f0f0f0; }
              td.quote { color: #333; font-size: .9rem; }
              td.check { min-width: 6rem; }
              td.covered { background: #e3f3e3; color: #1e6b1e; font-weight: bold; }
              td.missing { background: #fbe3e3; color: #a40000; font-weight: bold; }
              td.needsinput { background: #fff6d9; color: #7a5c00; font-weight: bold; }
              .summary td { font-size: 1.05rem; font-weight: bold; text-align: center; width: 33%; }
              .draftnote { background: #fff6d9; border: 1px solid #d9b23d; padding: .7rem 1rem; margin: 1rem 0; }
              @media print { body { margin: 0; } .draftnote { border-width: 2px; } }
            </style>
            </head>
            <body>
            <h1>مصفوفة الامتثال — مسودة للمراجعة البشرية</h1>
            <div class="draftnote">هذه مسودة أحكام آلية. كل حكم أدناه يجب التحقق منه يدوياً
            مقابل وثائق العميل قبل اعتماده في العرض — عمود «تحقق يدوي» مخصص لذلك.
            حكم «مُغطى» لا يظهر هنا إلا بدليل حرفي تحقق منه الكود في نص المقطع المشار إليه.</div>
            """);

        h.append("<h2>الملخص (").append(items.size()).append(" بنداً)</h2>\n")
         .append("<table class=\"summary\">\n<tr>")
         .append("<td class=\"covered\">مُغطى: ").append(covered).append("</td>")
         .append("<td class=\"missing\">ناقص: ").append(missing).append("</td>")
         .append("<td class=\"needsinput\">يحتاج مدخلاً: ").append(needsInput).append("</td>")
         .append("</tr>\n</table>\n");

        h.append("<h2>بنود الامتثال</h2>\n");
        h.append("""
            <table>
            <tr><th>المعرف</th><th>وصف البند</th><th>الحالة</th><th>الدليل الحرفي من وثائق العميل</th>
            <th>المصدر</th><th>الصفحة</th><th>سؤال للعميل</th><th>ملاحظات</th><th>تحقق يدوي</th></tr>
            """);
        for (ComplianceItem i : items) {
            Requirement r = byId.get(i.requirementId());
            h.append("<tr>")
             .append("<td>").append(esc(i.requirementId())).append("</td>")
             .append("<td>").append(esc(r == null ? "" : r.description())).append("</td>")
             .append(statusCell(i.status()))
             .append("<td class=\"quote\">").append(esc(i.evidenceQuote())).append("</td>")
             .append("<td>").append(esc(i.evidenceSource())).append("</td>")
             .append("<td>").append(i.evidencePage() > 0 ? String.valueOf(i.evidencePage()) : "—").append("</td>")
             .append("<td>").append(esc(i.clientQuestion())).append("</td>")
             .append("<td>").append(esc(i.notes())).append("</td>")
             .append("<td class=\"check\"></td>")
             .append("</tr>\n");
        }
        h.append("</table>\n</body>\n</html>\n");

        Files.writeString(out, h.toString(), StandardCharsets.UTF_8);
    }

    private String statusCell(String status) {
        return switch (status) {
            case ComplianceItem.COVERED -> "<td class=\"covered\">مُغطى</td>";
            case ComplianceItem.MISSING -> "<td class=\"missing\">ناقص</td>";
            case ComplianceItem.NEEDS_INPUT -> "<td class=\"needsinput\">يحتاج مدخلاً</td>";
            default -> "<td>" + esc(status) + "</td>";
        };
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
