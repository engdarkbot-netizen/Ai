package sa.bidengine.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * مولّد .docx مصغّر بلا أي اعتمادية خارجية — التزاماً بقاعدة "ممنوع الإفراط الهندسي":
 * ملف docx هو أرشيف zip يحوي بضعة ملفات XML، وjava.util.zip من المكتبة القياسية تكفي.
 *
 * المدعوم عمداً فقط ما تحتاجه مسودة العرض: عنوانان بمستويين، فقرات، جداول بسيطة بحدود،
 * واتجاه RTL كامل للعربية (w:bidi على مستوى الفقرة والقسم، w:rtl على مستوى النص،
 * وخط عربي افتراضي عبر w:rFonts مع w:cs). لا صور ولا ترويسات ولا أنماط زائدة —
 * إن احتجت أكثر من هذا فذلك مؤشر أن عميلاً حقيقياً يفرضه، لا قبل ذلك.
 */
public class DocxWriter {

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    /** جسد الوثيقة يُبنى نصياً فقرةً فقرة — كل نص يمر عبر esc() قبل دخوله. */
    private final StringBuilder body = new StringBuilder();

    // ---------- واجهة البناء ----------

    /** عنوان رئيسي (مستوى 1). */
    public void heading1(String text) {
        para("Heading1", text, false, null, 0, 0);
    }

    /** عنوان فرعي (مستوى 2). */
    public void heading2(String text) {
        para("Heading2", text, false, null, 0, 0);
    }

    /** فقرة نص عادية. */
    public void paragraph(String text) {
        para(null, text, false, null, 0, 0);
    }

    /** فقرة بارزة (عريضة بلون أحمر) — للتنبيهات وفقرات "يحتاج مدخلاً من العميل". */
    public void alert(String text) {
        para(null, text, true, "C00000", 0, 0);
    }

    /** ملاحظة ثانوية (رمادية أصغر) — لأسطر المصدر [المصدر: ...] وملاحظات الملفات الغائبة. */
    public void note(String text) {
        para(null, text, false, "595959", 18, 22);
    }

    /** فاصل صفحات — لعزل صفحة الغلاف عن متن الوثيقة. */
    public void pageBreak() {
        body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
    }

    /** جدول بسيط بحدود: صف ترويسة عريض بخلفية رمادية ثم صفوف البيانات، بترتيب أعمدة RTL. */
    public void table(List<String> header, List<List<String>> rows) {
        body.append("<w:tbl><w:tblPr><w:bidiVisual/><w:tblW w:w=\"0\" w:type=\"auto\"/>")
            .append("<w:tblBorders>");
        for (String side : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
            body.append("<w:").append(side)
                .append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"666666\"/>");
        }
        body.append("</w:tblBorders></w:tblPr>");
        tableRow(header, true);
        for (List<String> row : rows) {
            tableRow(row, false);
        }
        body.append("</w:tbl>");
        // فقرة فارغة بعد الجدول: يتطلبها Word للفصل بين جدولين متتاليين أو جدول ونهاية القسم.
        paragraph("");
    }

    /** يكتب الوثيقة كملف .docx (أرشيف zip بأربعة أجزاء XML + علاقاتها). */
    public void save(Path out) throws IOException {
        try (OutputStream os = Files.newOutputStream(out);
             ZipOutputStream zip = new ZipOutputStream(os)) {
            entry(zip, "[Content_Types].xml", contentTypesXml());
            entry(zip, "_rels/.rels", packageRelsXml());
            entry(zip, "word/_rels/document.xml.rels", documentRelsXml());
            entry(zip, "word/document.xml", documentXml());
            entry(zip, "word/styles.xml", stylesXml());
        }
    }

    // ---------- البناء الداخلي ----------

    /**
     * فقرة واحدة: w:bidi على مستوى الفقرة (اتجاه RTL) وw:rtl على مستوى النص.
     * sz/szCs بنصف النقطة (0 = حجم النمط الافتراضي).
     */
    private void para(String styleId, String text, boolean bold, String color, int sz, int szCs) {
        body.append("<w:p><w:pPr>");
        if (styleId != null) {
            body.append("<w:pStyle w:val=\"").append(styleId).append("\"/>");
        }
        body.append("<w:bidi/></w:pPr>");
        body.append(run(text, bold, color, sz, szCs));
        body.append("</w:p>");
    }

    private String run(String text, boolean bold, String color, int sz, int szCs) {
        StringBuilder r = new StringBuilder("<w:r><w:rPr>");
        if (bold) r.append("<w:b/><w:bCs/>");
        if (color != null) r.append("<w:color w:val=\"").append(color).append("\"/>");
        if (sz > 0) r.append("<w:sz w:val=\"").append(sz).append("\"/>");
        if (szCs > 0) r.append("<w:szCs w:val=\"").append(szCs).append("\"/>");
        r.append("<w:rtl/></w:rPr><w:t xml:space=\"preserve\">").append(esc(text))
         .append("</w:t></w:r>");
        return r.toString();
    }

    private void tableRow(List<String> cells, boolean header) {
        body.append("<w:tr>");
        for (String cell : cells) {
            body.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/>");
            if (header) {
                body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F0F0F0\"/>");
            }
            body.append("</w:tcPr>");
            body.append("<w:p><w:pPr><w:bidi/></w:pPr>")
                .append(run(cell, header, null, 0, 0))
                .append("</w:p>");
            body.append("</w:tc>");
        }
        body.append("</w:tr>");
    }

    private void entry(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** تهريب محارف XML — كل نص مستخدم يمر من هنا؛ محارف التحكم غير الصالحة تُستبدل بمسافة. */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> b.append(c < 0x20 && c != '\t' ? ' ' : c);
            }
        }
        return b.toString();
    }

    // ---------- أجزاء الأرشيف ----------

    private String documentXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="%s"><w:body>%s%s</w:body></w:document>
            """.formatted(W_NS, body,
                // w:bidi على مستوى القسم: اتجاه RTL للوثيقة كلها + مقاس A4 وهوامش قياسية.
                "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\""
                + " w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/><w:bidi/></w:sectPr>");
    }

    private String stylesXml() {
        // الافتراضي: Arial للاتيني وTraditional Arabic للعربية (المحارف المركّبة عبر w:cs).
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="%s">
              <w:docDefaults>
                <w:rPrDefault><w:rPr>
                  <w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Traditional Arabic"/>
                  <w:sz w:val="22"/><w:szCs w:val="28"/>
                </w:rPr></w:rPrDefault>
                <w:pPrDefault><w:pPr><w:bidi/><w:spacing w:after="120"/></w:pPr></w:pPrDefault>
              </w:docDefaults>
              <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
                <w:name w:val="Normal"/>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Heading1">
                <w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
                <w:pPr><w:bidi/><w:spacing w:before="360" w:after="160"/><w:outlineLvl w:val="0"/></w:pPr>
                <w:rPr><w:b/><w:bCs/><w:sz w:val="30"/><w:szCs w:val="36"/></w:rPr>
              </w:style>
              <w:style w:type="paragraph" w:styleId="Heading2">
                <w:name w:val="heading 2"/><w:basedOn w:val="Normal"/>
                <w:pPr><w:bidi/><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="1"/></w:pPr>
                <w:rPr><w:b/><w:bCs/><w:sz w:val="26"/><w:szCs w:val="32"/></w:rPr>
              </w:style>
            </w:styles>
            """.formatted(W_NS);
    }

    private String contentTypesXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
            </Types>
            """;
    }

    private String packageRelsXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
            """;
    }

    private String documentRelsXml() {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            </Relationships>
            """;
    }
}
