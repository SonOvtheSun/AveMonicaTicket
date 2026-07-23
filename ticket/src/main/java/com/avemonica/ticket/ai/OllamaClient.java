package com.avemonica.ticket.ai;

import com.avemonica.ticket.dto.AiQueryIntent;
import com.avemonica.ticket.dto.EventAiTagResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ollama 本地模型客户端。
 *
 * <p>模型分工：</p>
 * <ul>
 *     <li>visionModel：离线演出图片/文本标注。</li>
 *     <li>embeddingModel：演出索引文本和用户问题向量化。</li>
 *     <li>chatModel：首页 AI 助手回答和用户查询意图解析。</li>
 * </ul>
 */
@Slf4j
@Component
public class OllamaClient {

    private static final String NO_THINK_PREFIX = "/no_think\n";
    private static final String KEEP_ALIVE = "30m";

    private static final int CHAT_NUM_CTX = 8192;
    private static final int JSON_NUM_CTX = 4096;
    private static final int VISION_NUM_CTX = 4096;

    private static final int CHAT_NUM_PREDICT = 1200;
    private static final int INTENT_NUM_PREDICT = 1600;
    private static final int EVENT_TAG_JSON_NUM_PREDICT = 8192;
    private static final int EVENT_TAG_RETRY_NUM_PREDICT = 2400;
    private static final int IMAGE_DESCRIPTION_NUM_PREDICT_SINGLE = 2400;
    private static final int IMAGE_DESCRIPTION_NUM_PREDICT_MULTI = 3200;

    @Value("${ai.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ai.ollama.vision-model:qwen3-vl:8b}")
    private String visionModel;

    @Value("${ai.ollama.embedding-model:qwen3-embedding}")
    private String embeddingModel;

    @Value("${ai.ollama.chat-model:qwen3:8b}")
    private String chatModel;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    public OllamaClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 调用视觉模型，根据演出文本 + 图片生成标签 JSON。
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

    /**
     * 调用 embedding 模型生成向量。
     */
    @SuppressWarnings("unchecked")
    public List<Double> embed(String text) {
        try {
            if (!StringUtils.hasText(text)) {
                throw new RuntimeException("embedding 文本不能为空");
            }

            Map<String, Object> body = new HashMap<>();
            body.put("model", embeddingModel);
            body.put("input", text);
            body.put("keep_alive", KEEP_ALIVE);

            ResponseEntity<Map> response = post("/api/embed", body);
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

    /**
     * 调用纯语言模型生成最终回答。
     */
    public String chat(String prompt) {
        try {
            if (!StringUtils.hasText(prompt)) {
                throw new RuntimeException("chat prompt 不能为空");
            }

            return invokeChat(
                    chatModel,
                    prompt,
                    Collections.emptyList(),
                    false,
                    0.2,
                    CHAT_NUM_CTX,
                    CHAT_NUM_PREDICT
            );
        } catch (Exception e) {
            throw new RuntimeException("调用 Ollama chat 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 使用纯语言模型解析用户查询意图。
     *
     * <p>注意：这里仍然输出项目当前 AiQueryIntent DTO 支持的字段，
     * 通用约束、通用重排在 AiAssistantServiceImpl 内部完成。</p>
     */
    public AiQueryIntent parseQueryIntent(String question, String city) {
        String prompt = """
                你是票务网站的用户查询意图解析器。
                你的任务是从用户输入中提取“真正有区分度的需求词”，供后端检索和排序使用。

                只输出 JSON，不要解释、不要 Markdown、不要代码块。

                字段含义：
                1. coreIntent：一句话归纳用户核心需求。
                2. hardKeywords：只能放“用户原文明确说出的强约束词”，例如用户直接说出的艺人名、演出名、城市、风格、预算、时间、人群、氛围。
                   不要把你根据常识推断出来的内容放入 hardKeywords。
                   例如“我要看回春丹演出”只能提取“回春丹”，不能推断 Livehouse、乐队现场、摇滚。
                3. positiveKeywords：相关同义词、扩展词、加分词，weight 范围 0.1 到 1.0。
                4. weakKeywords：无区分度词，例如“我要看、想看、推荐、演出、活动、有没有”。
                5. negativeKeywords：用户明确不想要或应该降权的方向。
                6. eventType：只有当用户原文明确说出演出类型时才输出，例如用户明确说“Livehouse、演唱会、音乐节、音乐剧、话剧、脱口秀、漫展、展览”。
                   如果用户只是说“演出、现场、门票、想看某艺人”，eventType 必须输出 null。
                   不要根据艺人、乐队、标题常识推断 eventType。
                7. city：如果用户输入里明确指定城市，输出城市，否则 null。不要因为“当前城市”自动填充。
                8. budgetMax：如果用户说 500以内/不超过500，输出数字；否则 null。
                9. timePreference：今天/明天/周末/下周/最近/近期 等，否则 null。
                10. strictMode：只有用户原文明确指定风格、类型、城市、预算、时间、艺人、演出名等约束时才输出 true。
                    根据常识推断出来的类型或风格不能让 strictMode 变成 true。
                
                常见强需求示例：
                - 二次元/动漫/ACG/虚拟偶像/声优/游戏音乐
                - 摇滚/民谣/爵士/古典/电子/说唱/流行/国风
                - 演唱会/音乐节/Livehouse/音乐剧/话剧/脱口秀/漫展/展览
                - 情侣/亲子/朋友/粉丝/学生
                - 热血/治愈/浪漫/安静/蹦迪/搞笑
                - 周末/今天/明天/下周/预算500以内
                反例：
                - 用户输入“我要看回春丹演出”
                  正确：hardKeywords=["回春丹"], eventType=null, strictMode=true
                  错误：eventType="Livehouse"
                  错误：hardKeywords=["回春丹","Livehouse"]
    
                - 用户输入“我要看回春丹 Livehouse”
                  正确：hardKeywords=["回春丹","Livehouse"], eventType="Livehouse", strictMode=true
    
                - 用户输入“我要看二次元演出”
                  正确：hardKeywords=["二次元"], eventType=null, strictMode=true
                  错误：eventType="Livehouse"
    
                - 用户输入“我想看演出”
                  正确：hardKeywords=[], eventType=null, strictMode=false

                用户当前城市：%s
                用户输入：%s

                输出 JSON 格式：
                {
                  "coreIntent": "",
                  "hardKeywords": [],
                  "positiveKeywords": [
                    {"keyword": "", "weight": 0.0}
                  ],
                  "weakKeywords": [],
                  "negativeKeywords": [],
                  "eventType": null,
                  "city": null,
                  "budgetMax": null,
                  "timePreference": null,
                  "strictMode": false
                }
                """.formatted(city, question);

        String json = chatJson(prompt);

        try {
            AiQueryIntent intent = objectMapper.readValue(json, AiQueryIntent.class);
            normalizeIntent(intent);
            return intent;
        } catch (Exception e) {
            throw new RuntimeException("解析用户查询意图失败：" + e.getMessage(), e);
        }
    }

    /**
     * 调用纯语言模型，要求输出 JSON 对象。
     */
    public String chatJson(String prompt) {
        try {
            String content = invokeChat(
                    chatModel,
                    prompt,
                    Collections.emptyList(),
                    true,
                    0.0,
                    JSON_NUM_CTX,
                    INTENT_NUM_PREDICT
            );

            String json = extractJsonObject(content);
            if (!StringUtils.hasText(json)) {
                throw new RuntimeException("Ollama JSON content 为空");
            }
            return json;
        } catch (Exception e) {
            throw new RuntimeException("调用 Ollama JSON chat 失败：" + e.getMessage(), e);
        }
    }

    public String getChatModel() {
        return chatModel;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    private String describeImages(String eventPrompt, List<String> base64Images) throws Exception {
        if (base64Images == null || base64Images.isEmpty()) {
            return "";
        }

        String prompt = """
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
        body.put("prompt", NO_THINK_PREFIX + prompt);
        body.put("stream", false);
        body.put("think", false);
        body.put("keep_alive", KEEP_ALIVE);

        if (base64Images != null && !base64Images.isEmpty()) {
            body.put("images", base64Images);
        }

        int imageCount = base64Images == null ? 0 : base64Images.size();
        body.put("options", buildOptions(
                0.0,
                VISION_NUM_CTX,
                imageCount >= 2 ? IMAGE_DESCRIPTION_NUM_PREDICT_MULTI : IMAGE_DESCRIPTION_NUM_PREDICT_SINGLE
        ));

        try {
            ResponseEntity<Map> response = post("/api/generate", body);
            Map<String, Object> resBody = response.getBody();
            if (resBody == null) {
                throw new RuntimeException("Ollama 图片描述响应体为空");
            }

            String content = stringify(resBody.get("response")).trim();
            if (StringUtils.hasText(content)) {
                return content;
            }

            String thinking = stringify(resBody.get("thinking")).trim();
            if (StringUtils.hasText(thinking)) {
                String extracted = extractVisionTextFromThinking(thinking);
                if (StringUtils.hasText(extracted)) {
                    log.info("Ollama 图片描述 response 为空，已从 thinking 中提取视觉描述");
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

    private EventAiTagResult doGenerateEventTags(String prompt, boolean jsonFormat) throws Exception {
        String content = invokeChat(
                visionModel,
                prompt,
                Collections.emptyList(),
                jsonFormat,
                0.0,
                CHAT_NUM_CTX,
                jsonFormat ? EVENT_TAG_JSON_NUM_PREDICT : EVENT_TAG_RETRY_NUM_PREDICT
        );

        if (!StringUtils.hasText(content)) {
            throw new RuntimeException("模型输出为空");
        }

        return parseTagResult(content);
    }

    private String invokeChat(String model,
                              String prompt,
                              List<String> base64Images,
                              boolean jsonFormat,
                              double temperature,
                              int numCtx,
                              int numPredict) throws Exception {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", NO_THINK_PREFIX + prompt);

        if (base64Images != null && !base64Images.isEmpty()) {
            message.put("images", base64Images);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("think", false);
        body.put("keep_alive", KEEP_ALIVE);
        body.put("messages", Collections.singletonList(message));
        body.put("options", buildOptions(temperature, numCtx, numPredict));

        if (jsonFormat) {
            body.put("format", "json");
        }

        try {
            ResponseEntity<Map> response = post("/api/chat", body);
            Map<String, Object> resBody = response.getBody();
            if (resBody == null) {
                throw new RuntimeException("Ollama 响应体为空");
            }

            Object messageObj = resBody.get("message");
            if (!(messageObj instanceof Map<?, ?> resMessage)) {
                log.warn("Ollama 原始响应缺少 message：{}", objectMapper.writeValueAsString(resBody));
                throw new RuntimeException("Ollama chat 响应缺少 message");
            }

            String content = stringify(resMessage.get("content")).trim();
            if (StringUtils.hasText(content)) {
                return content;
            }

            String thinking = stringify(resMessage.get("thinking")).trim();
            Object doneReason = resBody.get("done_reason");
            log.warn("Ollama 模型 content 为空，model={}，jsonFormat={}，doneReason={}，thinkingLength={}",
                    model,
                    jsonFormat,
                    doneReason,
                    thinking.length());

            if (jsonFormat) {
                String jsonFromThinking = extractJsonObject(thinking);
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
            log.warn("Ollama HTTP 调用失败，status={}, body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString());
            throw e;
        }
    }

    private ResponseEntity<Map> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                baseUrl + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
    }

    private Map<String, Object> buildOptions(double temperature, int numCtx, int numPredict) {
        Map<String, Object> options = new HashMap<>();
        options.put("temperature", temperature);
        options.put("num_ctx", numCtx);
        options.put("num_predict", numPredict);
        return options;
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
        String json = extractJsonObject(content);
        if (!StringUtils.hasText(json)) {
            throw new RuntimeException("模型输出不是 JSON：" + content);
        }
        return json;
    }

    private String extractJsonObject(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }

        String normalized = text.trim()
                .replace("```json", "")
                .replace("```JSON", "")
                .replace("```", "")
                .trim();

        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return normalized.substring(start, end + 1);
        }

        return "";
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

        List<String> markers = List.of("第一张图", "第二张图", "图片中", "图中", "海报", "详情图");
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
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private void normalizeIntent(AiQueryIntent intent) {
        if (intent == null) {
            return;
        }

        if (intent.getHardKeywords() == null) {
            intent.setHardKeywords(new ArrayList<>());
        }
        if (intent.getPositiveKeywords() == null) {
            intent.setPositiveKeywords(new ArrayList<>());
        }
        if (intent.getWeakKeywords() == null) {
            intent.setWeakKeywords(new ArrayList<>());
        }
        if (intent.getNegativeKeywords() == null) {
            intent.setNegativeKeywords(new ArrayList<>());
        }
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

    private String shortText(String text, int maxLength) {
        if (text == null) {
            return "";
        }

        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
