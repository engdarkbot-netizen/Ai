package sa.bidengine.draft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * تصدير أقسام العرض كصفحة HTML عربية بسيطة للمراجعة اليدوية — نفس أسلوب
 * HtmlReportWriter: ملف واحد بلا اعتماديات ولا JavaScript، يُفتح في أي متصفح ويُطبع.
 * فقرات needsInput تُبرز بلون تحذيري مع سؤال العميل — سد الفجوات مع العميل
 * هو عمل المؤسس اليدوي، والصفحة هذه أداته.
 */
public class SectionsReviewWriter {

    public void write(Path out, List<ProposalSection> sections) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("""
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
            <meta charset="UTF-8">
            <title>أقسام العرض — مسودة للمراجعة</title>
            <style>
              body { font-family: "Segoe UI", Tahoma, Arial, sans-serif; margin: 2rem; color: #1a1a1a; }
              h1 { font-size: 1.4rem; } h2 { font-size: 1.15rem; margin-top: 2rem; }
              .paragraph { border: 1px solid #999; padding: .7rem 1rem; margin: .8rem 0; }
              .paragraph p { margin: 0 0 .5rem 0; }
              .citations { margin: .3rem 0 0 0; padding: 0; list-style: none; }
              .citations li { background: #f7f7f7; border: 1px solid #ccc; padding: .35rem .6rem;
                              margin-top: .35rem; font-size: .9rem; color: #333; }
              .citations .src { color: #555; font-size: .82rem; }
              .needsinput { background: #fdecea; border: 1px solid #a40000; }
              .needsinput .label { color: #a40000; font-weight: bold; }
              .question { margin-top: .3rem; }
              .draftnote { background: #fff6d9; border: 1px solid #d9b23d; padding: .7rem 1rem; margin: 1rem 0; }
              @media print { body { margin: 0; } .draftnote { border-width: 2px; } }
            </style>
            </head>
            <body>
            <h1>أقسام العرض الفني — مسودة للمراجعة البشرية</h1>
            <div class="draftnote">هذه مسودة مولدة آلياً: كل فقرة مسنودة باقتباسات متحقق منها
            برمجياً من وثائق العميل، وكل فجوة معلومات معلنة بلون تحذيري مع سؤال جاهز للعميل.
            لا يُعتمد أي نص قبل مراجعتك اليدوية وسد الفجوات مع العميل.</div>
            """);

        for (ProposalSection section : sections) {
            long gaps = section.paragraphs().stream().filter(Paragraph::needsInput).count();
            h.append("<h2>").append(esc(section.title()))
             .append(" (").append(section.paragraphs().size()).append(" فقرة")
             .append(gaps > 0 ? "، منها " + gaps + " فجوة تحتاج العميل" : "")
             .append(")</h2>\n");

            if (section.paragraphs().isEmpty()) {
                h.append("<div class=\"paragraph needsinput\"><span class=\"label\">")
                 .append("لم يُولد لهذا القسم أي محتوى — راجع وثائق العميل والاستعلامات.")
                 .append("</span></div>\n");
                continue;
            }
            for (Paragraph p : section.paragraphs()) {
                if (p.needsInput()) {
                    h.append("<div class=\"paragraph needsinput\">\n")
                     .append("<span class=\"label\">فجوة — تحتاج مدخلاً من العميل</span>\n")
                     .append("<p class=\"question\">").append(esc(p.clientQuestion())).append("</p>\n")
                     .append("</div>\n");
                    continue;
                }
                h.append("<div class=\"paragraph\">\n<p>").append(esc(p.text())).append("</p>\n")
                 .append("<ul class=\"citations\">\n");
                for (Citation c : p.citations()) {
                    h.append("<li>«").append(esc(c.quote())).append("»<br><span class=\"src\">")
                     .append(esc(c.chunkId())).append(" — ").append(esc(c.source()));
                    if (c.page() > 0) h.append("، صفحة ").append(c.page());
                    h.append("</span></li>\n");
                }
                h.append("</ul>\n</div>\n");
            }
        }

        h.append("</body>\n</html>\n");
        Files.writeString(out, h.toString(), StandardCharsets.UTF_8);
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
