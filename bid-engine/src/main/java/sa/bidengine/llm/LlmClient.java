package sa.bidengine.llm;

/**
 * واجهة مجردة لأي مزود نماذج لغوية.
 * قرار اختيار المزود معيار واحد يحكمه: جودة العربية الرسمية على كراسة حقيقية.
 * جرّب أكثر من مزود على نفس الكراسة قبل الالتزام (خطة البناء، القسم 2).
 */
public interface LlmClient {
    /** يرسل مطالبة ويعيد نص الرد الخام. المطالبة تطلب JSON فقط — والتحقق مسؤولية المستدعي. */
    String complete(String systemPrompt, String userPrompt) throws Exception;
}
