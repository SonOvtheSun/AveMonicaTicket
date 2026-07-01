package com.avemonica.ticket.ai;

import com.avemonica.ticket.dto.EventAiTagResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.*;

@Slf4j
@Component
public class OllamaClient {

    @Value("${ai.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ai.ollama.vision-model:qwen3-vl:8b}")
    private String visionModel;

    @Value("${ai.ollama.embedding-model:qwen3-embedding}")
    private String embeddingModel;

    private final RestTemplate restTemplate = new RestTemplate();

    private final ObjectMapper objectMapper;

    public OllamaClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 qwen3-vl:8b，根据演出文本 + 图片生成标签 JSON。
     */
    public EventAiTagResult generateEventTags(String prompt, List<String> base64Images) {
        List<String> images = base64Images == null ? Collections.emptyList() : base64Images;

        String finalPrompt = prompt;

        if (!images.isEmpty()) {
            try {
                String imageDescription = describeImages(prompt, images);

                if (StringUtils.hasText(imageDescription)) {
                    finalPrompt = prompt + """

                        
                        以下是海报/详情图的视觉识别结果，请结合它生成标签。
                        注意：视觉识别结果只是辅助信息，最终 JSON 必须使用简体中文。

                        【图片视觉信息】
                        %s
                        """.formatted(imageDescription);
                }
            } catch (Exception e) {
                log.warn("Ollama 图片描述失败，将退化为纯文本生成标签", e);
            }
        }

        Exception lastException = null;

        try {
            return doGenerateEventTags(finalPrompt, true);
        } catch (Exception e) {
            lastException = e;
            log.warn("Ollama format=json 生成标签失败，准备不带 format 重试", e);
        }

        try {
            return doGenerateEventTags(finalPrompt + """

                
                你刚才没有输出可解析 JSON。
                请重新输出严格 JSON。
                不要输出 Markdown。
                不要输出解释文字。
                不要输出 ```json 代码块。
                只输出一个 JSON 对象。
                """, false);
        } catch (Exception e) {
            lastException = e;
            log.warn("Ollama 不带 format 生成标签仍失败", e);
        }

        throw new RuntimeException(
                "调用 Ollama 生成演出标签失败：" +
                        (lastException == null ? "未知错误" : lastException.getMessage()),
                lastException
        );
    }

    private String describeImages(String eventPrompt, List<String> base64Images) throws Exception {
        if (base64Images == null || base64Images.isEmpty()) {
            return "";
        }

        String prompt = """
            /no_think
            你是演出票务平台的图片理解助手。
            请直接输出图片描述，不要思考过程。
            用简体中文概括图片中的关键信息，控制在150字以内。
            重点提取：演出类型、风格、艺人、城市、场馆、氛围、卖点。
            不要输出 JSON，不要输出 Markdown，不确定不要编造。

            演出文本信息：
            %s
            """.formatted(shortText(eventPrompt, 500));

        String content = doGenerateImageDescription(prompt, base64Images);

        if (!StringUtils.hasText(content)) {
            throw new RuntimeException("图片描述模型输出为空");
        }

        return content.trim();
    }

    private String doGenerateImageDescription(String prompt, List<String> base64Images) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", visionModel);
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("think", false);

        if (base64Images != null && !base64Images.isEmpty()) {
            body.put("images", base64Images);
        }

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", 0.0);
        options.put("num_ctx", 4096);

        // 图片描述只需要短文本，不要给太长，否则 qwen3-vl 更容易一直 thinking。
        options.put("num_predict", 6400);

        body.put("options", options);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + "/api/generate",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            Map<String, Object> resBody = response.getBody();
            if (resBody == null) {
                throw new RuntimeException("Ollama 图片描述响应体为空");
            }

            Object responseObj = resBody.get("response");
            String content = responseObj == null ? "" : String.valueOf(responseObj).trim();

            if (StringUtils.hasText(content)) {
                return content;
            }

            Object thinkingObj = resBody.get("thinking");
            String thinking = thinkingObj == null ? "" : String.valueOf(thinkingObj).trim();

            if (StringUtils.hasText(thinking)) {
                String extracted = extractVisionTextFromThinking(thinking);
                if (StringUtils.hasText(extracted)) {
                    log.warn("Ollama 图片描述 content 为空，已从 thinking 中提取视觉描述");
                    return extracted;
                }

                throw new RuntimeException("图片描述模型只输出 thinking，没有输出 response");
            }

            throw new RuntimeException("图片描述模型输出为空");
        } catch (HttpStatusCodeException e) {
            log.warn("Ollama 图片描述 HTTP 调用失败，status={}, body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw e;
        }
    }

    private String extractVisionTextFromThinking(String thinking) {
        if (!StringUtils.hasText(thinking)) {
            return "";
        }

        String text = thinking
                .replace("<think>", "")
                .replace("</think>", "")
                .replace("\r", "\n")
                .trim();

        // 尽量从真正描述图片的部分开始截取，避免把“用户要求我...”这种分析塞进图片描述。
        List<String> markers = Arrays.asList(
                "第一张图",
                "第二张图",
                "图片中",
                "图中",
                "海报",
                "详情图"
        );

        int start = -1;
        for (String marker : markers) {
            int idx = text.indexOf(marker);
            if (idx >= 0 && (start < 0 || idx < start)) {
                start = idx;
            }
        }

        if (start > 0) {
            text = text.substring(start);
        }

        text = text.replaceAll("\\s+", " ").trim();

        if (text.length() > 500) {
            text = text.substring(0, 500);
        }

        return text;
    }

    private EventAiTagResult doGenerateEventTags(String prompt, boolean jsonFormat) throws Exception {
        String content = doChat(prompt, Collections.emptyList(), jsonFormat);

        if (!StringUtils.hasText(content)) {
            throw new RuntimeException("模型输出为空");
        }

        return parseTagResult(content);
    }

    private String doChat(String prompt, List<String> base64Images, boolean jsonFormat) throws Exception {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");

        // qwen3 系模型兜底关闭思考；即使 think=false 未生效，也尽量通过 prompt 控制。
        message.put("content", "/no_think\n" + prompt);

        if (base64Images != null && !base64Images.isEmpty()) {
            message.put("images", base64Images);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", visionModel);
        body.put("stream", false);
        body.put("messages", Collections.singletonList(message));

        // 关键：关闭 thinking 输出。
        // 注意：这是 body 根字段，不是 options 里的字段。
        body.put("think", false);

        if (jsonFormat) {
            body.put("format", "json");
        }

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", 0.0);
        options.put("num_ctx", 8192);

        // JSON 标签生成给更大输出空间，避免被截断。
        options.put("num_predict", jsonFormat ? 8192 : 1200);

        body.put("options", options);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + "/api/chat",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            Map<String, Object> resBody = response.getBody();
            if (resBody == null) {
                throw new RuntimeException("Ollama 响应体为空");
            }

            Object messageObj = resBody.get("message");
            if (!(messageObj instanceof Map<?, ?> resMessage)) {
                log.warn("Ollama 原始响应缺少 message：{}", objectMapper.writeValueAsString(resBody));
                throw new RuntimeException("Ollama 标签生成响应缺少 message");
            }

            Object contentObj = resMessage.get("content");
            String content = contentObj == null ? "" : String.valueOf(contentObj).trim();

            if (StringUtils.hasText(content)) {
                return content;
            }

            Object thinkingObj = resMessage.get("thinking");
            String thinking = thinkingObj == null ? "" : String.valueOf(thinkingObj).trim();
            Object doneReason = resBody.get("done_reason");

            log.warn(
                    "Ollama 模型 content 为空，doneReason={}，thinkingLength={}，原始响应={}",
                    doneReason,
                    thinking.length(),
                    objectMapper.writeValueAsString(resBody)
            );

            // 兜底：如果模型把 JSON 写进 thinking 里，临时尝试提取。
            // 这个不是理想方案，但可以避免 qwen3-vl 某些情况下只写 thinking 导致整条索引失败。
            if (jsonFormat) {
                String jsonFromThinking = tryExtractJsonFromThinking(thinking);
                if (StringUtils.hasText(jsonFromThinking)) {
                    log.warn("Ollama content 为空，已临时从 thinking 中提取 JSON。建议确认 think=false 是否生效。");
                    return jsonFromThinking;
                }
            }

            if (StringUtils.hasText(thinking)) {
                throw new RuntimeException("模型只输出了 thinking，没有输出 content，请确认请求体已设置 think=false，或增大 num_predict");
            }

            throw new RuntimeException("模型输出为空");
        } catch (HttpStatusCodeException e) {
            log.warn("Ollama HTTP调用失败，status={}, body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw e;
        }
    }

    private String tryExtractJsonFromThinking(String thinking) {
        if (!StringUtils.hasText(thinking)) {
            return null;
        }

        int start = thinking.indexOf("{");
        int end = thinking.lastIndexOf("}");

        if (start < 0 || end <= start) {
            return null;
        }

        String candidate = thinking.substring(start, end + 1);

        try {
            JsonNode root = objectMapper.readTree(candidate);

            boolean looksLikeTagJson =
                    root.has("style")
                            && root.has("city")
                            && root.has("eventType")
                            && root.has("tags")
                            && root.has("summary");

            return looksLikeTagJson ? candidate : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String shortText(String text, int maxLength) {
        if (text == null) {
            return "";
        }

        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private boolean containsJapaneseKana(EventAiTagResult result) {
        if (result == null) {
            return false;
        }

        String text = String.join(" ",
                joinList(result.getStyle()),
                safe(result.getCity()),
                safe(result.getEventType()),
                joinList(result.getTags()),
                joinList(result.getMood()),
                joinList(result.getAudience()),
                joinList(result.getSellingPoints()),
                safe(result.getSummary())
        );

        // 平假名 + 片假名检测。汉字无法区分中日，所以这里只检测假名。
        return text.matches(".*[\\u3040-\\u30ff].*");
    }

    private String joinList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return String.join(" ", list);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }


    /**
     * 调用 qwen3-embedding，生成 4096 维向量。
     */
    public List<Double> embed(String text) {
        try {
            if (!StringUtils.hasText(text)) {
                throw new RuntimeException("embedding 文本不能为空");
            }

            Map<String, Object> body = new HashMap<>();
            body.put("model", embeddingModel);
            body.put("input", text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<Map> response = restTemplate.exchange(
                    baseUrl + "/api/embed",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            Map<String, Object> resBody = response.getBody();
            if (resBody == null || resBody.get("embeddings") == null) {
                throw new RuntimeException("Ollama embedding 响应为空");
            }

            List<List<Number>> embeddings = (List<List<Number>>) resBody.get("embeddings");
            if (embeddings == null || embeddings.isEmpty()) {
                throw new RuntimeException("Ollama embedding 结果为空");
            }

            List<Double> vector = new ArrayList<>();
            for (Number number : embeddings.get(0)) {
                vector.add(number.doubleValue());
            }

            return vector;
        } catch (Exception e) {
            throw new RuntimeException("调用 Ollama 生成向量失败：" + e.getMessage(), e);
        }
    }

    private EventAiTagResult parseTagResult(String content) throws Exception {
        String json = extractJson(content);
        JsonNode root = objectMapper.readTree(json);

        EventAiTagResult result = new EventAiTagResult();
        result.setStyle(readStringList(root.get("style")));
        result.setCity(readString(root.get("city")));
        result.setEventType(readString(root.get("eventType")));
        result.setTags(readStringList(root.get("tags")));
        result.setMood(readStringList(root.get("mood")));
        result.setAudience(readStringList(root.get("audience")));
        result.setSellingPoints(readStringList(root.get("sellingPoints")));
        result.setSummary(readString(root.get("summary")));

        return result;
    }

    private String extractJson(String content) {
        if (!StringUtils.hasText(content)) {
            throw new RuntimeException("模型输出为空");
        }

        String text = content.trim();

        if (text.startsWith("```")) {
            text = text.replace("```json", "")
                    .replace("```JSON", "")
                    .replace("```", "")
                    .trim();
        }

        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");

        if (start < 0 || end <= start) {
            throw new RuntimeException("模型输出不是 JSON：" + text);
        }

        return text.substring(start, end + 1);
    }

    private String readString(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isTextual()) {
            return node.asText();
        }

        return String.valueOf(node);
    }

    private List<String> readStringList(JsonNode node) {
        List<String> result = new ArrayList<>();

        if (node == null || node.isNull()) {
            return result;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = readString(item);
                if (StringUtils.hasText(value)) {
                    result.add(value.trim());
                }
            }
            return result;
        }

        String value = readString(node);
        if (StringUtils.hasText(value)) {
            result.add(value.trim());
        }

        return result;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }
}