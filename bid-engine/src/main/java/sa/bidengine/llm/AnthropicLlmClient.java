package sa.bidengine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * تنفيذ عبر Anthropic Messages API (مثال جاهز — بدّله أو أضف غيره من نفس الواجهة).
 * يقرأ المفتاح من متغير البيئة ANTHROPIC_API_KEY — لا تضع مفاتيح في الكود أبداً.
 *
 * إعادة المحاولة: الأخطاء العابرة (429 تجاوز الحد، 529 مزوّد مثقل، 5xx، انقطاع شبكة)
 * تعاد تلقائياً حتى 4 محاولات بتراجع أسّي (2ث، 4ث، 8ث). أخطاء الطلب نفسه
 * (4xx عدا 429 — مفتاح خاطئ، طلب غير صالح) تفشل فوراً لأن تكرارها بلا فائدة.
 */
public class AnthropicLlmClient implements LlmClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-6";
    private static final int MAX_ATTEMPTS = 4;
    private static final long FIRST_BACKOFF_MS = 2_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    public AnthropicLlmClient() {
        this.apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ضع مفتاح المزود في متغير البيئة ANTHROPIC_API_KEY");
        }
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", 8000);
        body.put("system", systemPrompt);
        body.putArray("messages").addObject()
                .put("role", "user")
                .put("content", userPrompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofMinutes(3))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = sendWithRetry(request);
        JsonNode root = mapper.readTree(response.body());
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString();
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws Exception {
        long backoffMs = FIRST_BACKOFF_MS;
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) return response;
                if (!isRetryable(status) || attempt == MAX_ATTEMPTS) {
                    throw new RuntimeException("LLM API error " + status + ": " + response.body());
                }
                System.err.printf("  تحذير: رد المزود %d — إعادة المحاولة %d/%d بعد %d ثانية...%n",
                        status, attempt, MAX_ATTEMPTS - 1, backoffMs / 1000);
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) throw e;
                System.err.printf("  تحذير: خطأ شبكة (%s) — إعادة المحاولة %d/%d بعد %d ثانية...%n",
                        e.getMessage(), attempt, MAX_ATTEMPTS - 1, backoffMs / 1000);
            }
            Thread.sleep(backoffMs);
            backoffMs *= 2;
        }
    }

    /** 429 تجاوز حد الاستدعاءات، 529 المزود مثقل، 5xx خلل مؤقت في الخادم. */
    private boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }
}
