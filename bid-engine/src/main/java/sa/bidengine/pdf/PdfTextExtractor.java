package sa.bidengine.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * استخراج نص الكراسة صفحة-صفحة (نحتفظ برقم الصفحة لأنه يدخل في sourcePage لكل بند).
 *
 * تنبيه العطلة الأولى: بعض الكراسات ممسوحة ضوئياً (صور لا نص). علامتها: صفحات
 * تُرجع نصاً فارغاً أو شبه فارغ. عالجها لاحقاً بـ OCR (Tesseract) — الآن يكفي
 * أن يكتشفها النظام ويبلّغك بدل أن يفشل بصمت.
 */
public class PdfTextExtractor {

    public record Page(int number, String text) {}

    public List<Page> extract(File pdfFile) throws IOException {
        List<Page> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int total = doc.getNumberOfPages();
            for (int i = 1; i <= total; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(new Page(i, stripper.getText(doc).strip()));
            }
        }
        return pages;
    }

    /** نسبة الصفحات الفارغة — إن تجاوزت 30% فالكراسة غالباً ممسوحة وتحتاج OCR. */
    public double emptyPageRatio(List<Page> pages) {
        if (pages.isEmpty()) return 1.0;
        long empty = pages.stream().filter(p -> p.text().length() < 40).count();
        return (double) empty / pages.size();
    }
}
