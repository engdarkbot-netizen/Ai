package sa.bidengine.comply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * قارئ فهرس قاعدة معرفة العميل (kb-index.json) — الشكل الحرفي المتفق عليه
 * مع وحدة قاعدة المعرفة التي تُبنى بالتوازي:
 * {"chunks":[{"id":"KB-0001","source":"اسم الملف.pdf","page":3,"text":"..."}]}
 * القراءة بـ JsonNode دفاعياً: مقطع بلا معرف أو بلا نص لا ينفع دليلاً — يُستبعد بتحذير.
 */
public class KbIndexReader {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<KbChunk> read(Path kbIndex) throws IOException {
        JsonNode root = mapper.readTree(kbIndex.toFile());
        List<KbChunk> chunks = new ArrayList<>();
        for (JsonNode c : root.path("chunks")) {
            String id = c.path("id").asText("");
            String text = c.path("text").asText("");
            if (id.isBlank() || text.isBlank()) {
                System.err.println("  تحذير: مقطع في الفهرس بلا معرف أو بلا نص — استُبعد.");
                continue;
            }
            chunks.add(new KbChunk(id, c.path("source").asText(""),
                    c.path("page").asInt(0), text));
        }
        return chunks;
    }
}
