package sa.bidengine.comply;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * كتابة compliance.json بالشكل الحرفي المتفق عليه — وحدة التصدير النهائي
 * تُبنى عليه بالتوازي، فلا تغيّر أسماء الحقول ولا بنيتها:
 * {"summary":{"covered":0,"missing":0,"needsInput":0},
 *  "items":[{"requirementId":"REQ-001","status":"COVERED","evidenceQuote":"",
 *            "evidenceSource":"","evidencePage":0,"notes":"","clientQuestion":""}]}
 */
public class ComplianceJsonWriter {

    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void write(Path out, List<ComplianceItem> items) throws IOException {
        long covered = items.stream().filter(i -> ComplianceItem.COVERED.equals(i.status())).count();
        long missing = items.stream().filter(i -> ComplianceItem.MISSING.equals(i.status())).count();
        long needsInput = items.stream().filter(i -> ComplianceItem.NEEDS_INPUT.equals(i.status())).count();

        ObjectNode root = mapper.createObjectNode();
        ObjectNode summary = root.putObject("summary");
        summary.put("covered", covered);
        summary.put("missing", missing);
        summary.put("needsInput", needsInput);

        ArrayNode arr = root.putArray("items");
        for (ComplianceItem i : items) {
            ObjectNode n = arr.addObject();
            n.put("requirementId", i.requirementId());
            n.put("status", i.status());
            n.put("evidenceQuote", i.evidenceQuote());
            n.put("evidenceSource", i.evidenceSource());
            n.put("evidencePage", i.evidencePage());
            n.put("notes", i.notes());
            n.put("clientQuestion", i.clientQuestion());
        }
        Files.writeString(out, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
    }
}
