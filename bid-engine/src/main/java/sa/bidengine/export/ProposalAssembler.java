package sa.bidengine.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * تجميع مسودة العرض النهائية من ملفات JSON الجاهزة إلى وثيقة docx واحدة.
 *
 * هذه الوحدة لا تستدعي النموذج اللغوي إطلاقاً — تجميع صرف لمخرجات المراحل السابقة:
 *   tender-spec.json        عقد النظام (بيانات المنافسة + المتطلبات)
 *   proposal-sections.json  أقسام العرض المصاغة مع إسناد كل فقرة لمصدرها
 *   compliance.json         مصفوفة الامتثال (مُغطى/ناقص/يحتاج مدخلاً)
 *
 * التسامح مبدأ التصميم: أي ملف غائب لا يُسقط التصدير — يُتخطى قسمه مع ملاحظة
 * واضحة في الوثيقة ليكمله المؤسس يدوياً. والناتج كله مسودة معلَّمة بوضوح —
 * الإنسان في الحلقة دائماً، لا تُقدَّم قبل المراجعة البشرية بنداً-بنداً.
 */
public class ProposalAssembler {

    private static final String MISSING_VALUE = "لم يُستخرج — أكمله يدوياً";

    private final ObjectMapper mapper = new ObjectMapper();

    public void assemble(Path inputDir, Path out) throws IOException {
        JsonNode spec = readIfExists(inputDir.resolve("tender-spec.json"));
        JsonNode sections = readIfExists(inputDir.resolve("proposal-sections.json"));
        JsonNode compliance = readIfExists(inputDir.resolve("compliance.json"));

        DocxWriter doc = new DocxWriter();
        writeCover(doc, spec);
        writeCompliance(doc, compliance);
        writeSections(doc, sections);
        writeRequirementsAppendix(doc, spec);
        doc.save(out);
    }

    /** قراءة متسامحة: الملف الغائب يعيد null والقسم المعني يتكفل بملاحظة الغياب في الوثيقة. */
    private JsonNode readIfExists(Path file) throws IOException {
        if (!Files.exists(file)) {
            System.err.println("  تنبيه: الملف غير موجود وسيُتخطى قسمه مع ملاحظة في الوثيقة: " + file);
            return null;
        }
        return mapper.readTree(Files.readString(file));
    }

    private void missingFileNote(DocxWriter doc, String fileName) {
        doc.note("ملاحظة: الملف «" + fileName + "» غير موجود في مجلد المدخلات — "
                + "تُخُطّيَ هذا القسم؛ أكمله يدوياً قبل المراجعة.");
    }

    // ---------- صفحة الغلاف ----------

    private void writeCover(DocxWriter doc, JsonNode spec) {
        String name = spec == null ? "" : spec.path("tenderName").asText("");
        doc.heading1(name.isBlank() ? "عرض فني — " + MISSING_VALUE : "عرض فني — " + name);
        if (spec == null) {
            missingFileNote(doc, "tender-spec.json");
        } else {
            doc.paragraph("الجهة الحكومية: " + textOrMissing(spec, "governmentEntity"));
            doc.paragraph("رقم المنافسة: " + textOrMissing(spec, "tenderNumber"));
            doc.paragraph("موعد إقفال التقديم: " + textOrMissing(spec, "submissionDeadline"));
        }
        doc.alert("مسودة آلية — لا تُقدَّم قبل المراجعة البشرية بنداً-بنداً");
        doc.pageBreak();
    }

    private String textOrMissing(JsonNode node, String field) {
        String v = node.path(field).asText("");
        return v.isBlank() ? MISSING_VALUE : v;
    }

    // ---------- مصفوفة الامتثال ----------

    private void writeCompliance(DocxWriter doc, JsonNode compliance) {
        doc.heading1("مصفوفة الامتثال");
        if (compliance == null) {
            missingFileNote(doc, "compliance.json");
            return;
        }
        JsonNode summary = compliance.path("summary");
        doc.paragraph("الملخص: مُغطى %d | ناقص %d | يحتاج مدخلاً %d".formatted(
                summary.path("covered").asInt(0),
                summary.path("missing").asInt(0),
                summary.path("needsInput").asInt(0)));

        List<List<String>> rows = new ArrayList<>();
        for (JsonNode item : compliance.path("items")) {
            rows.add(List.of(
                    item.path("requirementId").asText(""),
                    statusArabic(item.path("status").asText("")),
                    item.path("evidenceQuote").asText(""),
                    item.path("evidenceSource").asText(""),
                    pageText(item.path("evidencePage").asInt(0)),
                    item.path("notes").asText(""),
                    item.path("clientQuestion").asText("")));
        }
        doc.table(List.of("المعرف", "الحالة", "اقتباس الدليل", "المصدر", "الصفحة",
                "ملاحظات", "سؤال للعميل"), rows);
    }

    private String statusArabic(String status) {
        return switch (status) {
            case "COVERED" -> "مُغطى";
            case "MISSING" -> "ناقص";
            case "NEEDS_INPUT" -> "يحتاج مدخلاً";
            default -> status;  // تسامح: حالة غير معروفة تُعرض كما وردت للمراجعة اليدوية
        };
    }

    private String pageText(int page) {
        return page > 0 ? "ص " + page : "—";
    }

    // ---------- أقسام العرض ----------

    private void writeSections(DocxWriter doc, JsonNode sections) {
        if (sections == null) {
            doc.heading1("أقسام العرض");
            missingFileNote(doc, "proposal-sections.json");
            return;
        }
        for (JsonNode section : sections.path("sections")) {
            doc.heading1(section.path("title").asText(""));
            for (JsonNode p : section.path("paragraphs")) {
                String text = p.path("text").asText("");
                if (!text.isBlank()) {
                    doc.paragraph(text);
                }
                // فقرة تحتاج مدخلاً من العميل: تظهر بارزة حتى لا تمر في المراجعة.
                if (p.path("needsInput").asBoolean(false)) {
                    doc.alert("يحتاج مدخلاً من العميل: " + p.path("clientQuestion").asText(""));
                }
                // كل فقرة يتبعها مصدرها — امتداد حائط الهلوسة إلى مرحلة الصياغة.
                for (JsonNode c : p.path("citations")) {
                    doc.note("[المصدر: " + c.path("source").asText("")
                            + "، ص " + c.path("page").asInt(0) + "]");
                }
            }
        }
    }

    // ---------- ملحق مصفوفة المتطلبات ----------

    private void writeRequirementsAppendix(DocxWriter doc, JsonNode spec) {
        doc.heading1("ملحق: مصفوفة المتطلبات");
        if (spec == null) {
            missingFileNote(doc, "tender-spec.json");
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode r : spec.path("requirements")) {
            rows.add(List.of(
                    r.path("id").asText(""),
                    r.path("category").asText(""),
                    r.path("description").asText(""),
                    pageText(r.path("sourcePage").asInt(0)),
                    r.path("mandatory").asBoolean(false) ? "إلزامي" : "—"));
        }
        doc.table(List.of("المعرف", "التصنيف", "الوصف", "الصفحة", "إلزامي؟"), rows);
    }
}
