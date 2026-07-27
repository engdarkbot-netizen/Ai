package sa.bidengine.comply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sa.bidengine.model.Requirement;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * قراءة بنود المتطلبات من tender-spec.json (ناتج خط الاستخراج).
 * قراءة دفاعية بـ JsonNode: بند بلا معرف لا يمكن الحكم عليه ولا تتبعه — يُستبعد بتحذير.
 */
public class TenderSpecReader {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<Requirement> readRequirements(Path tenderSpec) throws IOException {
        JsonNode root = mapper.readTree(tenderSpec.toFile());
        List<Requirement> out = new ArrayList<>();
        for (JsonNode r : root.path("requirements")) {
            String id = r.path("id").asText("");
            if (id.isBlank()) {
                System.err.println("  تحذير: بند في tender-spec.json بلا معرف — استُبعد من الفحص.");
                continue;
            }
            out.add(new Requirement(
                    id,
                    r.path("category").asText("غير مصنف"),
                    r.path("description").asText(""),
                    r.path("sourceQuote").asText(""),
                    r.path("sourcePage").asInt(0),
                    r.path("mandatory").asBoolean(false)));
        }
        return out;
    }
}
