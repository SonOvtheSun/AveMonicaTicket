package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.ai.OllamaClient;
import com.avemonica.ticket.ai.QdrantClient;
import com.avemonica.ticket.dto.EventAiTagResult;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.EventAiProfile;
import com.avemonica.ticket.entity.EventSession;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.mapper.EventAiProfileMapper;
import com.avemonica.ticket.mapper.EventSessionMapper;
import com.avemonica.ticket.service.EventAiIndexService;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.TicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

@Slf4j
@Service
public class EventAiIndexServiceImpl implements EventAiIndexService {

    private static final int INDEX_STATUS_SUCCESS = 1;
    private static final int INDEX_STATUS_FAILED = 2;

    private final EventService eventService;
    private final EventSessionMapper eventSessionMapper;
    private final TicketService ticketService;
    private final ArtistMapper artistMapper;
    private final EventAiProfileMapper eventAiProfileMapper;
    private final OllamaClient ollamaClient;
    private final QdrantClient qdrantClient;
    private final ObjectMapper objectMapper;

    @Value("${ai.event-index.enabled:true}")
    private Boolean enabled;

    @Value("${ai.vector.dimension:4096}")
    private Integer vectorDimension;

    @Value("${ai.event-index.max-image-bytes:10485760}")
    private Long maxImageBytes;

    /**
     * 本地上传根目录，例如 D:/project/uploads。
     */
    @Value("${avemonica.upload.base-path:}")
    private String uploadBasePath;

    public EventAiIndexServiceImpl(EventService eventService,
                                   EventSessionMapper eventSessionMapper,
                                   TicketService ticketService,
                                   ArtistMapper artistMapper,
                                   EventAiProfileMapper eventAiProfileMapper,
                                   OllamaClient ollamaClient,
                                   QdrantClient qdrantClient,
                                   ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.eventSessionMapper = eventSessionMapper;
        this.ticketService = ticketService;
        this.artistMapper = artistMapper;
        this.eventAiProfileMapper = eventAiProfileMapper;
        this.ollamaClient = ollamaClient;
        this.qdrantClient = qdrantClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 第一版建议异步执行，避免管理员保存演出时被 AI 卡住。
     * 调试阶段如果想同步看报错，可以临时去掉 @Async。
     */
    @Override
    public void rebuildEventAiIndex(Long eventId) {
        rebuildEventAiIndex(eventId, false);
    }

    @Async("aiTaskExecutor")
    @Override
    public void rebuildEventAiIndex(Long eventId, boolean force) {
        if (!Boolean.TRUE.equals(enabled)) {
            log.info("AI 演出索引未启用，跳过，eventId={}", eventId);
            return;
        }

        if (eventId == null) {
            return;
        }

        Event event = eventService.getById(eventId);
        if (event == null) {
            log.warn("演出不存在，跳过 AI 索引，eventId={}", eventId);
            return;
        }

        List<EventSession> sessions = loadSessions(eventId);
        List<TicketCategory> tickets = loadTickets(eventId);
        List<Map<String, Object>> artists = loadArtists(eventId);

        String sourceHash = buildSourceHash(event, sessions, tickets, artists);

        EventAiProfile oldProfile = eventAiProfileMapper.selectById(eventId);

        if (!force
                && oldProfile != null
                && Objects.equals(oldProfile.getSourceHash(), sourceHash)
                && Objects.equals(oldProfile.getIndexStatus(), INDEX_STATUS_SUCCESS)) {
            log.info("演出 AI 索引未变化，跳过重建，eventId={}", eventId);
            return;
        }

        try {
            updateAiIndexProgress(eventId, 0, 5, "任务已开始", null);
            doRebuildEventAiIndex(event, sessions, tickets, artists, sourceHash, oldProfile);
        } catch (Exception e) {
            log.warn("演出 AI 索引重建失败，eventId={}", eventId, e);
            updateAiIndexProgress(eventId, 2, 100, "标注失败", e.getMessage());
            saveFailedProfile(event, sourceHash, e.getMessage());
        }
    }

    private void updateAiIndexProgress(Long eventId,
                                       Integer status,
                                       Integer progress,
                                       String step,
                                       String errorMsg) {
        if (eventId == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        EventAiProfile exists = eventAiProfileMapper.selectById(eventId);

        EventAiProfile profile = new EventAiProfile();
        profile.setEventId(eventId);
        profile.setIndexStatus(status);
        profile.setIndexProgress(progress);
        profile.setIndexStep(step);
        profile.setErrorMsg(errorMsg);
        profile.setUpdateTime(now);

        // 任务刚开始时记录开始时间
        if (progress != null && progress <= 5) {
            profile.setIndexStartTime(now);
        }

        // 成功或失败时记录结束时间
        if (status != null && (status == 1 || status == 2)) {
            profile.setIndexFinishTime(now);
        }

        if (exists == null) {
            profile.setCreateTime(now);
            eventAiProfileMapper.insert(profile);
        } else {
            eventAiProfileMapper.updateById(profile);
        }
    }

    @Override
    public void deleteEventAiIndex(Long eventId) {
        if (eventId == null) {
            return;
        }

        try {
            qdrantClient.deleteEventVector(eventId);
        } catch (Exception e) {
            log.warn("删除 Qdrant 演出向量失败，eventId={}", eventId, e);
        }

        eventAiProfileMapper.deleteById(eventId);
    }

    private void doRebuildEventAiIndex(Event event,
                                       List<EventSession> sessions,
                                       List<TicketCategory> tickets,
                                       List<Map<String, Object>> artists,
                                       String sourceHash,
                                       EventAiProfile oldProfile) throws Exception {
        Long eventId = event.getId();

        String prompt = buildTagPrompt(event, sessions, tickets, artists);
        List<String> base64Images = buildBase64Images(event);

        EventAiTagResult aiResult;

        try {
            aiResult = ollamaClient.generateEventTags(prompt, base64Images);
        } catch (Exception e) {
            log.warn("AI标签生成失败，eventId={}", eventId, e);
            throw e;
        }

        normalizeAiResult(aiResult, event);

        String embeddingText = buildEmbeddingText(event, sessions, tickets, artists, aiResult);
        List<Double> vector = ollamaClient.embed(embeddingText);
        validateVector(vector);

        Map<String, Object> payload = buildVectorPayload(event, sessions, tickets, artists, aiResult);

        // Qdrant point id 统一使用 eventId。
        qdrantClient.upsertEventVector(eventId, vector, payload);

        saveSuccessProfile(event, aiResult, embeddingText, sourceHash, oldProfile);
        log.info("演出 AI 索引重建成功，eventId={}", eventId);
    }

    private List<EventSession> loadSessions(Long eventId) {
        return eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>()
                        .eq(EventSession::getEventId, eventId)
                        .orderByAsc(EventSession::getSortOrder)
                        .orderByAsc(EventSession::getShowTime)
        );
    }

    private List<TicketCategory> loadTickets(Long eventId) {
        return ticketService.list(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
        );
    }

    private List<Map<String, Object>> loadArtists(Long eventId) {
        try {
            List<Map<String, Object>> artists = artistMapper.selectArtistMapsByEventId(eventId);
            return artists == null ? Collections.emptyList() : artists;
        } catch (Exception e) {
            log.warn("AI索引加载艺人信息失败，eventId={}", eventId, e);
            return Collections.emptyList();
        }
    }

    private String buildTagPrompt(Event event,
                                  List<EventSession> sessions,
                                  List<TicketCategory> tickets,
                                  List<Map<String, Object>> artists) {
        String sessionText = sessions == null ? "" : sessions.stream()
                                                     .map(s -> "场次：" + safe(s.getSessionName())
                                                               + "，演出时间：" + safe(s.getShowTime())
                                                               + "，开票时间：" + safe(s.getSaleTime())
                                                               + "，状态：" + safe(s.getStatus()))
                                                     .collect(Collectors.joining("\n"));

        String ticketText = tickets == null ? "" : tickets.stream()
                                                   .map(t -> "票档：" + safe(t.getName())
                                                             + "，价格：" + safe(t.getPrice())
                                                             + "，库存：" + safe(t.getRemainingStock()))
                                                   .collect(Collectors.joining("\n"));

        String artistText = artists == null ? "" : artists.stream()
                                                   .map(a -> "艺人：" + safe(a.get("name"))
                                                             + "，风格：" + safe(a.get("style")))
                                                   .collect(Collectors.joining("\n"));

        return """
                你是一个演出票务平台的内容理解助手。
                输入内容可能包含中文、日文、英文、韩文或混合语言。
                无论输入是什么语言，你都必须使用【简体中文】输出。
                
                你需要根据演出基础信息、艺人信息、场次票档信息，以及可能提供的海报/详情图，输出严格 JSON。
                不要输出 Markdown，不要输出解释文字，不要输出多余字段。
                
                必须输出如下 JSON 结构：
                {
                  "style": ["中文风格1", "中文风格2"],
                  "city": "中文城市名",
                  "eventType": "中文演出类型，例如 演唱会/话剧/漫展/音乐节/Livehouse/音乐剧/脱口秀/展览/见面会/其他",
                  "tags": ["中文自由标签"],
                  "mood": ["中文氛围标签"],
                  "audience": ["中文适合人群"],
                  "sellingPoints": ["中文卖点"],
                  "summary": "100字以内的中文精简演出描述"
                }
                
                重要要求：
                1. 所有字段值必须使用简体中文。
                2. 日文内容必须理解后翻译成中文，不允许直接输出日文假名。
                3. 演出标题、艺人名称如果是专有名词，可以保留原名；但 summary、tags、mood、audience、sellingPoints 必须是中文表达。
                4. city 必须优先使用原始城市字段，并输出中文城市名。
                5. eventType 必须明确归类，不能留空。
                6. tags 不超过 12 个。
                7. summary 必须适合直接展示给用户，不能超过 100 字。
                8. 不确定的信息不要编造，使用已有字段兜底。
                
                演出ID：%s
                演出标题：%s
                城市：%s
                场馆：%s
                地址：%s
                原始风格：%s
                演出状态：%s
                运行时长：%s 分钟
                海报URL：%s
                详情图URL：%s
                
                艺人信息：
                %s
                
                场次信息：
                %s
                
                票档信息：
                %s
                """.formatted(
                safe(event.getId()),
                safe(event.getTitle()),
                safe(event.getCity()),
                safe(event.getVenue()),
                safe(event.getAddress()),
                safe(event.getStyle()),
                safe(event.getStatus()),
                safe(event.getRunningTime()),
                safe(event.getPosterUrl()),
                safe(event.getDetailsUrl()),
                artistText,
                sessionText,
                ticketText
        );
    }

    private String buildEmbeddingText(Event event,
                                      List<EventSession> sessions,
                                      List<TicketCategory> tickets,
                                      List<Map<String, Object>> artists,
                                      EventAiTagResult aiResult) {
        String styles = join(aiResult.getStyle());
        String tags = join(aiResult.getTags());
        String mood = join(aiResult.getMood());
        String audience = join(aiResult.getAudience());
        String sellingPoints = join(aiResult.getSellingPoints());

        String artistText = artists == null ? "" : artists.stream()
                                                   .map(a -> safe(a.get("name")) + " " + safe(a.get("style")))
                                                   .collect(Collectors.joining(" "));

        String sessionText = sessions == null ? "" : sessions.stream()
                                                     .map(s -> safe(s.getSessionName()) + " " + safe(s.getShowTime()) + " " + safe(s.getSaleTime()))
                                                     .collect(Collectors.joining(" "));

        String ticketText = tickets == null ? "" : tickets.stream()
                                                   .map(t -> safe(t.getName()) + " " + safe(t.getPrice()))
                                                   .collect(Collectors.joining(" "));

        return """
                演出标题：%s
                城市：%s
                场馆：%s
                地址：%s
                原始风格：%s
                AI风格：%s
                演出类型：%s
                标签：%s
                氛围：%s
                适合人群：%s
                卖点：%s
                精简描述：%s
                艺人：%s
                场次：%s
                票档：%s
                """.formatted(
                safe(event.getTitle()),
                safe(aiResult.getCity()),
                safe(event.getVenue()),
                safe(event.getAddress()),
                safe(event.getStyle()),
                styles,
                safe(aiResult.getEventType()),
                tags,
                mood,
                audience,
                sellingPoints,
                safe(aiResult.getSummary()),
                artistText,
                sessionText,
                ticketText
        );
    }

    private Map<String, Object> buildVectorPayload(Event event,
                                                   List<EventSession> sessions,
                                                   List<TicketCategory> tickets,
                                                   List<Map<String, Object>> artists,
                                                   EventAiTagResult aiResult) {
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("eventId", event.getId());
        payload.put("title", event.getTitle());
        payload.put("city", aiResult.getCity());
        payload.put("eventType", aiResult.getEventType());
        payload.put("style", aiResult.getStyle());
        payload.put("tags", aiResult.getTags());
        payload.put("mood", aiResult.getMood());
        payload.put("audience", aiResult.getAudience());
        payload.put("sellingPoints", aiResult.getSellingPoints());
        payload.put("summary", aiResult.getSummary());

        payload.put("status", event.getStatus());
        payload.put("posterUrl", event.getPosterUrl());
        payload.put("detailsUrl", event.getDetailsUrl());
        payload.put("venue", event.getVenue());
        payload.put("address", event.getAddress());

        payload.put("showTime", resolveFirstShowTime(sessions));
        payload.put("saleTime", resolveFirstSaleTime(sessions));
        payload.put("minPrice", resolveMinPrice(tickets));

        List<String> artistNames = artists == null ? Collections.emptyList() : artists.stream()
                                                                               .map(a -> safe(a.get("name")))
                                                                               .filter(StringUtils::hasText)
                                                                               .collect(Collectors.toList());

        payload.put("artists", artistNames);

        return payload;
    }

    private void normalizeAiResult(EventAiTagResult result, Event event) {
        if (result.getStyle() == null || result.getStyle().isEmpty()) {
            if (StringUtils.hasText(event.getStyle())) {
                result.setStyle(Arrays.stream(event.getStyle().split("/"))
                        .map(String::trim)
                        .filter(StringUtils::hasText)
                        .distinct()
                        .limit(6)
                        .collect(Collectors.toList()));
            } else {
                result.setStyle(Collections.singletonList("其他"));
            }
        }

        result.setStyle(limitList(result.getStyle(), 8));
        result.setTags(limitList(result.getTags(), 12));
        result.setMood(limitList(result.getMood(), 8));
        result.setAudience(limitList(result.getAudience(), 8));
        result.setSellingPoints(limitList(result.getSellingPoints(), 8));

        if (!StringUtils.hasText(result.getCity())) {
            result.setCity(event.getCity());
        }

        if (!StringUtils.hasText(result.getEventType())) {
            result.setEventType("演出");
        }

        if (!StringUtils.hasText(result.getSummary())) {
            result.setSummary(buildFallbackSummary(event));
        }

        if (result.getSummary() != null && result.getSummary().length() > 300) {
            result.setSummary(result.getSummary().substring(0, 300));
        }
    }

    private String buildFallbackSummary(Event event) {
        return String.format("%s 将在 %s 的 %s 举办，适合关注相关现场演出的用户。",
                safe(event.getTitle()),
                safe(event.getCity()),
                safe(event.getVenue()));
    }

    private List<String> buildBase64Images(Event event) {
        List<String> result = new ArrayList<>();

        addBase64Image(result, event.getPosterUrl());
        addBase64Image(result, event.getDetailsUrl());

        return result;
    }

    private void addBase64Image(List<String> result, String url) {
        if (!StringUtils.hasText(url)) {
            return;
        }

        try {
            File file = resolveUploadFile(url);
            if (file == null || !file.exists() || !file.isFile()) {
                log.warn("AI索引图片不存在，url={}", url);
                return;
            }

            byte[] normalizedBytes = readImageAsJpegBytes(file);

            if (normalizedBytes.length > maxImageBytes) {
                log.warn("AI索引图片转换后仍过大，跳过，url={}, size={}", url, normalizedBytes.length);
                return;
            }

            String base64 = Base64.getEncoder().encodeToString(normalizedBytes);
            result.add(base64);

            log.info("AI索引图片已转换为JPEG并加入Ollama输入，url={}, sourcePath={}, outputBytes={}",
                    url, file.getAbsolutePath(), normalizedBytes.length);
        } catch (Exception e) {
            log.warn("读取演出图片并转 base64 失败，url={}", url, e);
        }
    }

    private byte[] readImageAsJpegBytes(File file) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            throw new IOException("图片文件不存在");
        }

        BufferedImage source = ImageIO.read(file);
        if (source == null) {
            throw new IOException("ImageIO无法读取图片，可能缺少WebP插件或图片格式不受支持：" + file.getAbsolutePath());
        }

        BufferedImage normalized = normalizeToRgb(source);

        // 避免详情长图过大导致 Ollama 解码失败或请求过大。
        BufferedImage resized = resizeIfTooLarge(normalized, 1024);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        boolean written = ImageIO.write(resized, "jpg", outputStream);

        if (!written) {
            throw new IOException("ImageIO写出JPEG失败：" + file.getAbsolutePath());
        }

        return outputStream.toByteArray();
    }

    private BufferedImage normalizeToRgb(BufferedImage source) {
        BufferedImage rgbImage = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );

        Graphics2D graphics = rgbImage.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();

        return rgbImage;
    }

    private BufferedImage resizeIfTooLarge(BufferedImage source, int maxSide) {
        int width = source.getWidth();
        int height = source.getHeight();

        int currentMaxSide = Math.max(width, height);
        if (currentMaxSide <= maxSide) {
            return source;
        }

        double ratio = maxSide * 1.0 / currentMaxSide;
        int newWidth = Math.max(1, (int) Math.round(width * ratio));
        int newHeight = Math.max(1, (int) Math.round(height * ratio));

        Image scaledImage = source.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, newWidth, newHeight);
        graphics.drawImage(scaledImage, 0, 0, null);
        graphics.dispose();

        return resized;
    }

    private File resolveUploadFile(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }

        /*
         * 数据库中保存的是：
         * /uploads/poster/xxx.webp
         * /uploads/avatar/xxx.webp
         * /uploads/scrollbar/xxx.webp
         *
         * WebConfig 中映射的是：
         * /uploads/** -> file:${avemonica.upload.base-path}/
         *
         * 所以真实路径应为：
         * ${avemonica.upload.base-path}/poster/xxx.webp
         */
        int index = url.indexOf("/uploads/");
        if (index < 0) {
            log.warn("AI索引图片不是本地上传路径，url={}", url);
            return null;
        }

        String relative = url.substring(index + "/uploads/".length())
                .replace("/", File.separator)
                .replace("\\", File.separator);

        List<File> candidates = new ArrayList<>();

        if (StringUtils.hasText(uploadBasePath)) {
            // 正确路径：${user.dir}/src/images/poster/xxx.webp
            candidates.add(new File(uploadBasePath, relative));
        }

        // 兜底 1：项目根目录/src/images/poster/xxx.webp
        candidates.add(new File(
                System.getProperty("user.dir"),
                "src" + File.separator + "images" + File.separator + relative
        ));

        // 兜底 2：兼容旧逻辑，项目根目录/uploads/poster/xxx.webp
        candidates.add(new File(
                System.getProperty("user.dir"),
                "uploads" + File.separator + relative
        ));

        for (File file : candidates) {
            if (file.exists() && file.isFile()) {
                log.info("AI索引图片命中本地文件，url={}, path={}", url, file.getAbsolutePath());
                return file;
            }
        }

        log.warn(
                "AI索引图片不存在，url={}，尝试路径={}",
                url,
                candidates.stream()
                        .map(File::getAbsolutePath)
                        .collect(Collectors.joining(" | "))
        );

        return null;
    }

    private void validateVector(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new RuntimeException("embedding 向量为空");
        }

        if (vectorDimension != null && vectorDimension > 0 && vector.size() != vectorDimension) {
            throw new RuntimeException("embedding 向量维度不匹配，expected="
                    + vectorDimension + ", actual=" + vector.size());
        }
    }

    private void saveSuccessProfile(Event event,
                                    EventAiTagResult aiResult,
                                    String embeddingText,
                                    String sourceHash,
                                    EventAiProfile oldProfile) throws Exception {
        LocalDateTime now = LocalDateTime.now();

        EventAiProfile profile = new EventAiProfile();
        profile.setEventId(event.getId());
        profile.setTagJson(objectMapper.writeValueAsString(aiResult));
        profile.setStyleTags(join(aiResult.getStyle()));
        profile.setCity(aiResult.getCity());
        profile.setEventType(aiResult.getEventType());
        profile.setAiSummary(aiResult.getSummary());
        profile.setEmbeddingText(embeddingText);
        profile.setPosterUrl(event.getPosterUrl());
        profile.setDetailsUrl(event.getDetailsUrl());
        profile.setVectorCollection(qdrantClient.getCollection());
        profile.setVectorPointId(String.valueOf(event.getId()));
        profile.setLlmModel(ollamaClient.getVisionModel());
        profile.setEmbeddingModel(ollamaClient.getEmbeddingModel());
        profile.setSourceHash(sourceHash);
        profile.setIndexStatus(INDEX_STATUS_SUCCESS);
        profile.setErrorMsg(null);
        profile.setUpdateTime(now);

        EventAiProfile exists = eventAiProfileMapper.selectById(event.getId());

        if (exists == null) {
            profile.setCreateTime(now);
            eventAiProfileMapper.insert(profile);
        } else {
            eventAiProfileMapper.updateById(profile);
        }
    }

    private void saveFailedProfile(Event event, String sourceHash, String errorMsg) {
        if (event == null || event.getId() == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        EventAiProfile oldProfile = eventAiProfileMapper.selectById(event.getId());

        EventAiProfile profile = new EventAiProfile();
        profile.setEventId(event.getId());
        profile.setPosterUrl(event.getPosterUrl());
        profile.setDetailsUrl(event.getDetailsUrl());
        profile.setSourceHash(sourceHash);
        profile.setIndexStatus(INDEX_STATUS_FAILED);
        profile.setErrorMsg(shortText(errorMsg, 900));
        profile.setUpdateTime(now);

        EventAiProfile exists = eventAiProfileMapper.selectById(event.getId());

        if (exists == null) {
            profile.setCreateTime(now);
            eventAiProfileMapper.insert(profile);
        } else {
            eventAiProfileMapper.updateById(profile);
        }
    }

    private String buildSourceHash(Event event,
                                   List<EventSession> sessions,
                                   List<TicketCategory> tickets,
                                   List<Map<String, Object>> artists) {
        try {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("title", event.getTitle());
            source.put("city", event.getCity());
            source.put("venue", event.getVenue());
            source.put("address", event.getAddress());
            source.put("style", event.getStyle());
            source.put("posterUrl", event.getPosterUrl());
            source.put("detailsUrl", event.getDetailsUrl());
            source.put("status", event.getStatus());
            source.put("runningTime", event.getRunningTime());
            source.put("sessions", sessions);
            source.put("tickets", tickets);
            source.put("artists", artists);

            String raw = objectMapper.writeValueAsString(source);
            return DigestUtils.md5DigestAsHex(raw.getBytes());
        } catch (Exception e) {
            String raw = String.join("|",
                    safe(event.getTitle()),
                    safe(event.getCity()),
                    safe(event.getVenue()),
                    safe(event.getAddress()),
                    safe(event.getStyle()),
                    safe(event.getPosterUrl()),
                    safe(event.getDetailsUrl()),
                    safe(event.getStatus())
            );
            return DigestUtils.md5DigestAsHex(raw.getBytes());
        }
    }

    private String resolveFirstShowTime(List<EventSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }

        return sessions.stream()
                .map(EventSession::getShowTime)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(String::valueOf)
                .orElse(null);
    }

    private String resolveFirstSaleTime(List<EventSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }

        return sessions.stream()
                .map(EventSession::getSaleTime)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(String::valueOf)
                .orElse(null);
    }

    private String resolveMinPrice(List<TicketCategory> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return null;
        }

        return tickets.stream()
                .map(TicketCategory::getPrice)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(String::valueOf)
                .orElse(null);
    }

    private List<String> limitList(List<String> list, int limit) {
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }

        return list.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .limit(limit)
                .collect(Collectors.toList());
    }

    private String join(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }

        return list.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(","));
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String shortText(String text, int maxLength) {
        if (text == null) {
            return "未知错误";
        }

        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}