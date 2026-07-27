package sa.bidengine.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import sa.bidengine.pdf.PdfTextExtractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * قاعدة معرفة العميل — العطلة الثالثة: فهرسة وثائق العميل (ملفات PDF وtxt وmd)
 * إلى مقاطع صغيرة قابلة للاقتباس الحرفي، ثم استرجاع بسيط بتقاطع الكلمات.
 *
 * شكل ملف الفهرس kb-index.json متفق عليه ولا يتغير — وحدات أخرى تُبنى عليه:
 *   {"chunks":[{"id":"KB-0001","source":"اسم الملف.pdf","page":3,"text":"..."}]}
 * (page = 0 لملفات txt/md لأنها بلا صفحات.)
 *
 * الاسترجاع عمداً بدائي: تطبيع عربي ثم عدّ الكلمات المشتركة — لا embeddings ولا
 * قواعد بيانات (القاعدة الصلبة 5: ممنوع الإفراط الهندسي). إن أثبتت وثائق عملاء
 * حقيقيين قصوره فحينها فقط يُحسّن.
 */
public class ClientKnowledgeBase {

    /** مقطع واحد من وثيقة عميل — نص المقطع هو المرجع الحرفي لأي اقتباس لاحق. */
    public record KbChunk(String id, String source, int page, String text) {}

    /** غلاف ملف الفهرس — يطابق الشكل المتفق عليه حرفياً. */
    private record KbIndex(List<KbChunk> chunks) {}

    /** مقاطع أصغر من مقاطع الكراسة: تُلصق في مطالبة الصياغة كمراجع اقتباس. */
    private static final int MAX_CHARS = 2_000;
    private static final int OVERLAP_CHARS = 200;

    /** كلمات شائعة تُستبعد من التقاطع كي لا تتصدر مقاطع لا صلة لها (بعد التطبيع). */
    private static final Set<String> STOPWORDS = Set.of(
            "من", "في", "علي", "الي", "عن", "ان", "او", "مع", "ما", "لا",
            "هذا", "هذه", "ذلك", "التي", "الذي", "تم", "كل", "بين", "حسب", "وفق");

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final List<KbChunk> chunks;
    private final Map<String, KbChunk> byId = new HashMap<>();
    private final Map<String, Set<String>> tokensById = new HashMap<>();

    private ClientKnowledgeBase(List<KbChunk> chunks) {
        this.chunks = List.copyOf(chunks);
        for (KbChunk c : this.chunks) {
            byId.put(c.id(), c);
            tokensById.put(c.id(), tokenize(normalizeArabic(c.text())));
        }
    }

    /**
     * يفهرس وثائق العميل من المجلد المعطى (pdf/txt/md، بلا نزول للمجلدات الفرعية)
     * ويكتب kb-index.json ثم يعيد القاعدة جاهزة للاسترجاع.
     */
    public static ClientKnowledgeBase build(Path docsDir, Path outFile) throws Exception {
        if (!Files.isDirectory(docsDir)) {
            throw new IOException("مجلد وثائق العميل غير موجود: " + docsDir);
        }
        List<KbChunk> chunks = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(docsDir)) {
            files = s.filter(Files::isRegularFile)
                     .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                     .toList();
        }
        int seq = 1;
        for (Path file : files) {
            String name = file.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf")) {
                // إعادة استخدام مستخرج الكراسات نفسه — صفحة-صفحة حفاظاً على رقم الصفحة.
                var pages = new PdfTextExtractor().extract(file.toFile());
                for (var page : pages) {
                    for (String part : split(page.text())) {
                        chunks.add(new KbChunk(id(seq++), name, page.number(), part));
                    }
                }
            } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String part : split(text)) {
                    chunks.add(new KbChunk(id(seq++), name, 0, part));
                }
            } else {
                System.out.println("   تخطي ملف بامتداد غير مدعوم: " + name);
            }
        }
        if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, MAPPER.writeValueAsString(new KbIndex(chunks)),
                StandardCharsets.UTF_8);
        return new ClientKnowledgeBase(chunks);
    }

    /** يحمّل فهرساً سبق بناؤه من kb-index.json. */
    public static ClientKnowledgeBase load(Path kbFile) throws IOException {
        KbIndex index = MAPPER.readValue(Files.readString(kbFile, StandardCharsets.UTF_8),
                KbIndex.class);
        return new ClientKnowledgeBase(index.chunks() == null ? List.of() : index.chunks());
    }

    /**
     * استرجاع أفضل k مقاطع للاستعلام: النقاط = عدد كلمات الاستعلام (بعد التطبيع
     * واستبعاد الشائع) الموجودة في كلمات المقطع. مقطع بلا أي كلمة مشتركة لا يُعاد.
     */
    public List<KbChunk> search(String query, int k) {
        Set<String> queryTokens = tokenize(normalizeArabic(query));
        record Scored(KbChunk chunk, long score) {}
        List<Scored> scored = new ArrayList<>();
        for (KbChunk c : chunks) {
            Set<String> chunkTokens = tokensById.get(c.id());
            long score = queryTokens.stream().filter(chunkTokens::contains).count();
            if (score > 0) scored.add(new Scored(c, score));
        }
        scored.sort(Comparator.comparingLong((Scored s) -> s.score()).reversed());
        return scored.stream().limit(Math.max(0, k)).map(Scored::chunk).toList();
    }

    /** المقطع بمعرفه — يستخدمه التحقق الميكانيكي من الاقتباسات. */
    public KbChunk get(String chunkId) {
        return byId.get(chunkId);
    }

    public int size() {
        return chunks.size();
    }

    /**
     * تطبيع عربي بسيط للمطابقة: إزالة التشكيل والتطويل، توحيد الألف (أ/إ/آ/ٱ ← ا)
     * والياء (ى ← ي) والتاء المربوطة (ة ← ه)، وتوحيد المسافات.
     */
    public static String normalizeArabic(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u064B-\\u0652\\u0670\\u0640]", "")
                .replaceAll("[أإآٱ]", "ا")
                .replace('ى', 'ي')
                .replace('ة', 'ه')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static Set<String> tokenize(String normalized) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String t : normalized.split("[^\\p{L}\\p{Nd}]+")) {
            if (t.length() >= 2 && !STOPWORDS.contains(t)) tokens.add(t);
        }
        return new HashSet<>(tokens);
    }

    /** تقطيع بعدد الأحرف مع تداخل — نمط TextChunker نفسه لكن على نص واحد بلا ترويسات. */
    private static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) return parts;
        int start = 0;
        while (start < t.length()) {
            int end = Math.min(t.length(), start + MAX_CHARS);
            parts.add(t.substring(start, end));
            if (end == t.length()) break;
            start = end - OVERLAP_CHARS;
        }
        return parts;
    }

    private static String id(int seq) {
        return String.format("KB-%04d", seq);
    }
}
