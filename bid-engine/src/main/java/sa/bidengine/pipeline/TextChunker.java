package sa.bidengine.pipeline;

import sa.bidengine.pdf.PdfTextExtractor.Page;

import java.util.ArrayList;
import java.util.List;

/**
 * تقطيع نص الكراسة إلى مقاطع تناسب نافذة سياق النموذج، مع ترويسة تحفظ أرقام الصفحات.
 * البداية البسيطة الصحيحة: تقطيع بعدد الأحرف مع تداخل — لا تبنِ تقطيعاً دلالياً
 * قبل أن تثبت الكراسات الحقيقية أنك تحتاجه.
 */
public class TextChunker {

    private final int maxChars;
    private final int overlapChars;

    public TextChunker(int maxChars, int overlapChars) {
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public record Chunk(int index, int firstPage, int lastPage, String text) {}

    public List<Chunk> chunk(List<Page> pages) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int firstPage = pages.isEmpty() ? 0 : pages.get(0).number();
        int lastPage = firstPage;

        for (Page p : pages) {
            String pageText = "\n[صفحة " + p.number() + "]\n" + p.text();
            if (buf.length() + pageText.length() > maxChars && buf.length() > 0) {
                chunks.add(new Chunk(chunks.size(), firstPage, lastPage, buf.toString()));
                String tail = buf.substring(Math.max(0, buf.length() - overlapChars));
                buf = new StringBuilder(tail);
                firstPage = p.number();
            }
            buf.append(pageText);
            lastPage = p.number();
        }
        if (buf.length() > 0) {
            chunks.add(new Chunk(chunks.size(), firstPage, lastPage, buf.toString()));
        }
        return chunks;
    }
}
