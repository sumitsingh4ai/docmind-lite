package com.sumitdocmind;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OpenAiService {
    @Value("${app.openai-api-key:ollama}")
    String key;

    @Value("${app.chat-model:phi3:latest}")
    String chatModel;

    @Value("${app.embedding-model:nomic-embed-text}")
    String embedModel;

    @Value("${app.openai-base-url:http://localhost:11434/v1}")
    String baseUrl;

    @Value("${app.provider:openai}")
    String provider;

    final RestClient client;

    public OpenAiService(@Value("${app.openai-base-url:http://localhost:11434/v1}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    private boolean isHuggingFaceProvider() {
        return "huggingface".equalsIgnoreCase(provider) || baseUrl.contains("huggingface");
    }

    private void requireKey() {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Set OPENAI_API_KEY or HUGGINGFACE_API_KEY in env/config");
        }
    }

    private String authorizationHeader() {
        return "Bearer " + key;
    }

    public float[] embed(String text) {
        requireKey();

        if (isHuggingFaceProvider()) {
            return embedHuggingFace(text);
        }

        Map<String, Object> r = client.post()
            .uri("/embeddings")
            .header("Authorization", authorizationHeader())
            .body(Map.of("model", embedModel, "input", text))
            .retrieve()
            .body(Map.class);

        Map<String, Object> d = (Map<String, Object>) ((List<?>) r.get("data")).get(0);
        return flattenEmbedding(d.get("embedding"));
    }

    private float[] embedHuggingFace(String text) {
        Object body = Map.of("inputs", text);

        Object response = client.post()
            .uri("/pipeline/feature-extraction/" + embedModel)
            .header("Authorization", authorizationHeader())
            .body(body)
            .retrieve()
            .body(Object.class);

        return flattenEmbedding(response);
    }

    private float[] flattenEmbedding(Object value) {
        List<Float> values = new ArrayList<>();
        collectNumbers(value, values);
        if (values.isEmpty()) {
            throw new IllegalStateException("Embedding response was empty");
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private void collectNumbers(Object value, List<Float> out) {
        if (value instanceof Number n) {
            out.add(n.floatValue());
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectNumbers(item, out);
            }
        }
    }

    public String answer(String system, String user) {
        requireKey();
        Map<String, Object> body = Map.of(
            "model", chatModel,
            "messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
            ),
            "temperature", 0.2
        );

        Map<String, Object> r = client.post()
            .uri("/chat/completions")
            .header("Authorization", authorizationHeader())
            .body(body)
            .retrieve()
            .body(Map.class);

        return (String) ((Map<String, Object>) ((Map<String, Object>) ((List<?>) r.get("choices")).get(0)).get("message")).get("content");
    }
}
