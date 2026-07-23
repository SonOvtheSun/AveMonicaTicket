package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.ai.OllamaClient;
import com.avemonica.ticket.ai.QdrantClient;
import com.avemonica.ticket.dto.AiAssistantChatResponse;
import com.avemonica.ticket.dto.AiQueryIntent;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.EventAiProfile;
import com.avemonica.ticket.entity.EventSession;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.mapper.EventAiProfileMapper;
import com.avemonica.ticket.mapper.EventMapper;
import com.avemonica.ticket.mapper.EventSessionMapper;
import com.avemonica.ticket.mapper.TicketCategoryMapper;
import com.avemonica.ticket.service.AiAssistantService;
import com.avemonica.ticket.vo.AiAssistantEventVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 首页 AI 找演出服务。
 *
 * <p>整体链路：</p>
 * <ol>
 *     <li>用户自然语言输入。</li>
 *     <li>LLM 解析意图 + 后端通用词典增强。</li>
 *     <li>生成加权检索文本并调用 embedding。</li>
 *     <li>Qdrant 多召回。</li>
 *     <li>MySQL 做权威业务过滤：状态、城市、未来场次、预算、时间。</li>
 *     <li>通用意图约束过滤和重排。</li>
 *     <li>chat 模型基于最终候选生成回答。</li>
 * </ol>
 */
@Slf4j
@Service
public class AiAssistantServiceImpl implements AiAssistantService {

    private static final int EVENT_STATUS_ONLINE = 1;
    private static final int SESSION_STATUS_HIDDEN = 4;

    private static final int DEFAULT_SIZE = 5;
    private static final int MAX_SIZE = 10;
    private static final int MAX_QUESTION_LENGTH = 500;
    private static final int MAX_QDRANT_LIMIT = 50;
    private static final int MIN_QDRANT_LIMIT = 30;

    /**
     * 没有任何明确约束时，Qdrant 分数太低的结果不进入最终候选。
     * 有明确约束时，优先依赖约束过滤和重排，不用固定阈值误杀。
     */
    private static final double MIN_SCORE_WITHOUT_REQUIRED_RULE = 0.32D;

    private static final Pattern PRICE_RANGE_PATTERN = Pattern.compile("(\\d{2,5})\\s*[-~到至]\\s*(\\d{2,5})");
    private static final Pattern PRICE_PATTERN = Pattern.compile("(\\d{2,5})\\s*(元|块|以内|以下|之内|左右|以上|以下|不超过|不低于)?");

    private static final List<String> COMMON_WEAK_TERMS = List.of(
            "我要看", "我想看", "想看", "帮我找", "推荐", "有没有", "有无", "演出", "活动", "票", "门票", "现场"
    );

    private static final List<String> KNOWN_CITIES = List.of(
            "全国", "北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "武汉", "南京", "苏州", "天津", "西安",
            "长沙", "郑州", "青岛", "厦门", "福州", "沈阳", "大连", "济南", "合肥", "昆明", "南宁", "哈尔滨",
            "香港", "澳门", "台北"
    );

    private final OllamaClient ollamaClient;
    private final QdrantClient qdrantClient;
    private final EventMapper eventMapper;
    private final EventSessionMapper eventSessionMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final EventAiProfileMapper eventAiProfileMapper;

    public AiAssistantServiceImpl(OllamaClient ollamaClient,
                                  QdrantClient qdrantClient,
                                  EventMapper eventMapper,
                                  EventSessionMapper eventSessionMapper,
                                  TicketCategoryMapper ticketCategoryMapper,
                                  EventAiProfileMapper eventAiProfileMapper) {
        this.ollamaClient = ollamaClient;
        this.qdrantClient = qdrantClient;
        this.eventMapper = eventMapper;
        this.eventSessionMapper = eventSessionMapper;
        this.ticketCategoryMapper = ticketCategoryMapper;
        this.eventAiProfileMapper = eventAiProfileMapper;
    }

    @Override
    public AiAssistantChatResponse chat(String question, String city, Integer size) {
        String safeQuestion = normalizeQuestion(question);
        String safeCity = normalizeCity(city);
        int safeSize = normalizeSize(size);

        IntentProfile profile = buildIntentProfile(safeQuestion, safeCity);
        String effectiveCity = resolveEffectiveCity(safeCity, profile);
        String weightedQueryText = buildWeightedQueryText(safeQuestion, effectiveCity, profile);

        List<Double> queryVector;
        try {
            queryVector = ollamaClient.embed(weightedQueryText);
        } catch (Exception e) {
            log.warn("AI助手 embedding 失败 question={}", safeQuestion, e);
            return response("AI 模型正在启动或暂时不可用，请稍后再试一次。", Collections.emptyList());
        }

        List<QdrantClient.SearchResult> searchResults;
        try {
            searchResults = qdrantClient.searchEventVectors(
                    queryVector,
                    effectiveCity,
                    resolveQdrantLimit(safeSize)
            );
        } catch (Exception e) {
            log.warn("AI助手向量检索失败 question={} city={}", safeQuestion, effectiveCity, e);
            return response("AI 向量检索服务暂时不可用，请稍后再试。", Collections.emptyList());
        }

        CandidateEvaluation evaluation = evaluateCandidates(searchResults, effectiveCity, safeSize, profile, false);
        List<AiAssistantEventVO> eventVOs = evaluation.getAccepted().stream()
                .map(this::buildEventVO)
                .collect(Collectors.toList());

        String answer = eventVOs.isEmpty()
                ? buildNoResultAnswer(safeQuestion, effectiveCity, profile)
                : generateAnswer(safeQuestion, effectiveCity, eventVOs);

        log.info("AI助手推荐完成 question={} city={} qdrantCount={} finalCount={} requiredRules={}",
                safeQuestion,
                effectiveCity,
                searchResults == null ? 0 : searchResults.size(),
                eventVOs.size(),
                profile.requiredRules.size());

        return response(answer, eventVOs);
    }

    @Override
    public Map<String, Object> debugVisibleEvents(String question, String city, Integer size) {
        String safeQuestion = normalizeQuestion(question);
        String safeCity = normalizeCity(city);
        int safeSize = normalizeSize(size);

        IntentProfile profile = buildIntentProfile(safeQuestion, safeCity);
        String effectiveCity = resolveEffectiveCity(safeCity, profile);
        String weightedQueryText = buildWeightedQueryText(safeQuestion, effectiveCity, profile);

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("question", safeQuestion);
        debug.put("currentCity", safeCity);
        debug.put("effectiveCity", effectiveCity);
        debug.put("size", safeSize);
        debug.put("intentProfile", profile.toDebugMap());
        debug.put("weightedQueryText", weightedQueryText);

        List<Double> queryVector = ollamaClient.embed(weightedQueryText);
        List<QdrantClient.SearchResult> searchResults = qdrantClient.searchEventVectors(
                queryVector,
                effectiveCity,
                resolveQdrantLimit(safeSize)
        );

        debug.put("qdrantRawResults", buildQdrantDebugRows(searchResults));
        debug.put("qdrantEventIds", extractEventIds(searchResults));

        CandidateEvaluation evaluation = evaluateCandidates(searchResults, effectiveCity, safeSize, profile, true);
        debug.put("candidateAnalysis", evaluation.getAnalysis());
        debug.put("finalVisibleEvents", evaluation.getAccepted().stream()
                .map(this::buildEventVO)
                .collect(Collectors.toList()));

        return debug;
    }

    /**
     * 构建通用意图画像：LLM 解析 + 规则词典增强 + 预算/时间/城市解析。
     */
    private IntentProfile buildIntentProfile(String question, String currentCity) {
        IntentProfile profile = new IntentProfile(question, currentCity);

        AiQueryIntent modelIntent = parseModelIntentSafely(question, currentCity);
        profile.modelIntent = modelIntent;
        mergeModelIntent(profile, modelIntent);

        enhanceByDomainDictionary(profile, question);
        parseCityPreference(profile, question);
        parseBudgetPreference(profile, question);
        parseTimePreference(profile, question);
        addWeakTerms(profile, question);

        profile.strictMode = profile.strictMode || !profile.requiredRules.isEmpty();
        return profile;
    }

    private AiQueryIntent parseModelIntentSafely(String question, String city) {
        try {
            return ollamaClient.parseQueryIntent(question, city);
        } catch (Exception e) {
            log.warn("AI助手意图解析失败，使用规则增强兜底 question={}", question, e);
            AiQueryIntent fallback = new AiQueryIntent();
            fallback.setCoreIntent(question);
            fallback.setCity(null);
            fallback.setStrictMode(false);
            return fallback;
        }
    }

    /**
     * 将 LLM 的通用关键词解析合并为内部 TermRule。
     * 这里不把“演出、推荐”等弱词当强约束。
     */
    private void mergeModelIntent(IntentProfile profile, AiQueryIntent intent) {
        if (intent == null) {
            return;
        }

        profile.coreIntent = StringUtils.hasText(intent.getCoreIntent())
                ? intent.getCoreIntent().trim()
                : profile.originalQuestion;

        if (StringUtils.hasText(intent.getCity()) && containsTerm(profile.originalQuestion, intent.getCity())) {
            profile.queryCity = intent.getCity().trim();
            addRequiredRule(profile, RuleField.CITY, List.of(stripCitySuffix(intent.getCity())), 1.0D, "用户输入城市约束");
        }

        if (StringUtils.hasText(intent.getEventType())) {
            String eventType = intent.getEventType().trim();
            List<String> terms = expandKnownTerms(eventType);

            if (isExplicitEventTypeInQuestion(profile.originalQuestion, eventType)) {
                addRequiredRule(profile, RuleField.EVENT_TYPE, terms, 0.9D, "用户明确演出类型：" + eventType);
            } else {
                addBoostRule(profile, RuleField.EVENT_TYPE, terms, 0.45D, "LLM推断演出类型，仅加分：" + eventType);
            }
        }

        if (intent.getBudgetMax() != null) {
            profile.budgetMax = intent.getBudgetMax();
            profile.budgetRequired = true;
        }

        if (StringUtils.hasText(intent.getTimePreference())) {
            TimeConstraint timeConstraint = buildTimeConstraint(intent.getTimePreference(), true);
            if (timeConstraint != null) {
                profile.timeConstraint = timeConstraint;
            }
        }

        for (String keyword : safeList(intent.getHardKeywords())) {
            if (isWeakTerm(keyword)) {
                profile.weakTerms.add(keyword);
                continue;
            }

            List<String> terms = expandKnownTerms(keyword);

            if (isExplicitHardKeyword(profile.originalQuestion, keyword)) {
                RuleField field = isKnownEventTypeKeyword(keyword) ? RuleField.EVENT_TYPE : RuleField.ALL;
                addRequiredRule(profile, field, terms, 0.85D, "用户明确强约束：" + keyword);
            } else {
                RuleField field = isKnownEventTypeKeyword(keyword) ? RuleField.EVENT_TYPE : RuleField.ALL;
                addBoostRule(profile, field, terms, 0.45D, "LLM推断强约束，降级为加分：" + keyword);
            }
        }

        for (AiQueryIntent.WeightedKeyword item : safeWeightedList(intent.getPositiveKeywords())) {
            if (item == null || !StringUtils.hasText(item.getKeyword()) || isWeakTerm(item.getKeyword())) {
                continue;
            }
            double weight = item.getWeight() == null ? 0.5D : clamp(item.getWeight(), 0.1D, 1.0D);
            addBoostRule(profile, RuleField.ALL, expandKnownTerms(item.getKeyword()), weight, "LLM解析加分词");
        }

        for (String keyword : safeList(intent.getNegativeKeywords())) {
            if (!StringUtils.hasText(keyword) || isWeakTerm(keyword)) {
                continue;
            }
            addPenaltyRule(profile, RuleField.ALL, expandKnownTerms(keyword), 0.6D, false, "LLM解析降权词");
        }

        profile.weakTerms.addAll(safeList(intent.getWeakKeywords()));
    }

    /**
     * 通用领域词典增强。这里不是只针对二次元，而是按风格/类型/人群/氛围/场地统一处理。
     */
    private void enhanceByDomainDictionary(IntentProfile profile, String question) {
        addStyleGroup(profile, question, "二次元/动漫/ACG", List.of(
                "二次元", "动漫", "ACG", "二偶", "虚拟偶像", "Vocaloid", "洛天依", "初音", "BanG Dream", "邦邦", "声优", "游戏音乐", "动画", "偶像企划"
        ));
        addStyleGroup(profile, question, "摇滚", List.of("摇滚", "Rock", "朋克", "金属", "硬摇", "独立摇滚"));
        addStyleGroup(profile, question, "民谣", List.of("民谣", "Folk", "木吉他", "独立民谣"));
        addStyleGroup(profile, question, "流行", List.of("流行", "Pop", "华语流行", "港台", "内地流行"));
        addStyleGroup(profile, question, "电子/电音", List.of("电子", "电音", "EDM", "DJ", "蹦迪", "派对"));
        addStyleGroup(profile, question, "爵士/蓝调", List.of("爵士", "Jazz", "蓝调", "Blues"));
        addStyleGroup(profile, question, "古典", List.of("古典", "交响", "管弦乐", "钢琴", "小提琴", "室内乐"));
        addStyleGroup(profile, question, "说唱/嘻哈", List.of("说唱", "嘻哈", "HipHop", "Rap", "rapper"));
        addStyleGroup(profile, question, "国风", List.of("国风", "古风", "民族乐", "传统", "国潮"));

        addEventTypeGroup(profile, question, "演唱会", List.of("演唱会", "巡演", "个人演唱会"));
        addEventTypeGroup(profile, question, "音乐节", List.of("音乐节", "Festival", "户外音乐节"));
        addEventTypeGroup(profile, question, "Livehouse/小型现场", List.of("Livehouse", "livehouse", "live house", "Live House", "乐队现场", "小现场", "小型现场", "小场地", "小场地现场"));
        addEventTypeGroup(profile, question, "音乐剧", List.of("音乐剧", "Musical"));
        addEventTypeGroup(profile, question, "话剧/舞台剧", List.of("话剧", "戏剧", "舞台剧", "剧场"));
        addEventTypeGroup(profile, question, "脱口秀", List.of("脱口秀", "单口喜剧", "喜剧", "stand-up"));
        addEventTypeGroup(profile, question, "漫展/同人展", List.of("漫展", "同人展", "Comic", "ACG展", "展会"));
        addEventTypeGroup(profile, question, "展览", List.of("展览", "艺术展", "美术馆", "摄影展"));
        addEventTypeGroup(profile, question, "舞蹈/芭蕾", List.of("舞蹈", "芭蕾", "现代舞", "舞剧"));
        addEventTypeGroup(profile, question, "戏曲", List.of("戏曲", "京剧", "昆曲", "越剧", "粤剧"));

        addAudienceGroup(profile, question, "情侣/约会", List.of("情侣", "约会", "对象", "女朋友", "男朋友", "恋人"));
        addAudienceGroup(profile, question, "亲子/家庭", List.of("亲子", "小孩", "孩子", "家庭", "儿童", "带娃"));
        addAudienceGroup(profile, question, "朋友/聚会", List.of("朋友", "同学", "聚会", "一起去"));
        addAudienceGroup(profile, question, "粉丝向", List.of("粉丝", "饭圈", "追星", "应援"));
        addAudienceGroup(profile, question, "学生", List.of("学生", "大学生", "校园"));

        addMoodGroup(profile, question, "热血/高能", List.of("热血", "燃", "高能", "炸", "激情", "嗨", "热烈"));
        addMoodGroup(profile, question, "治愈/放松", List.of("治愈", "温柔", "放松", "轻松", "舒服"));
        addMoodGroup(profile, question, "浪漫/氛围感", List.of("浪漫", "氛围感", "甜", "约会"));
        addMoodGroup(profile, question, "安静", List.of("安静", "不吵", "轻音乐", "静一点"));
        addMoodGroup(profile, question, "搞笑", List.of("搞笑", "好笑", "喜剧", "幽默"));
        addMoodGroup(profile, question, "文艺", List.of("文艺", "小众", "独立", "艺术"));

        addVenueBoost(profile, question, "户外", List.of("户外", "露天", "草坪"));
        addVenueBoost(profile, question, "体育馆", List.of("体育馆", "体育场", "场馆大"));
        addVenueBoost(profile, question, "剧场", List.of("剧场", "剧院", "大剧院"));

        parseNegativePreference(profile, question);
    }

    private void addStyleGroup(IntentProfile profile, String question, String label, List<String> aliases) {
        if (containsAny(question, aliases)) {
            addRequiredRule(profile, RuleField.STYLE, aliases, 1.0D, "风格约束：" + label);
            addBoostRule(profile, RuleField.TAG, aliases, 0.75D, "风格相关标签加分：" + label);
            profile.strictMode = true;
        }
    }

    private void addEventTypeGroup(IntentProfile profile, String question, String label, List<String> aliases) {
        if (containsAny(question, aliases)) {
            addRequiredRule(profile, RuleField.EVENT_TYPE, aliases, 0.9D, "演出类型约束：" + label);
            profile.strictMode = true;
        }
    }

    private void addAudienceGroup(IntentProfile profile, String question, String label, List<String> aliases) {
        if (containsAny(question, aliases)) {
            addBoostRule(profile, RuleField.AUDIENCE, aliases, 0.7D, "适合人群偏好：" + label);
        }
    }

    private void addMoodGroup(IntentProfile profile, String question, String label, List<String> aliases) {
        if (containsAny(question, aliases)) {
            addBoostRule(profile, RuleField.MOOD, aliases, 0.65D, "氛围偏好：" + label);
        }
    }

    private void addVenueBoost(IntentProfile profile, String question, String label, List<String> aliases) {
        if (containsAny(question, aliases)) {
            addBoostRule(profile, RuleField.VENUE, aliases, 0.45D, "场地偏好：" + label);
        }
    }

    private void parseNegativePreference(IntentProfile profile, String question) {
        if (!StringUtils.hasText(question)) {
            return;
        }

        if (containsAny(question, List.of("不要太吵", "不想太吵", "别太吵", "安静一点", "别吵"))) {
            addPenaltyRule(profile, RuleField.MOOD, List.of("摇滚", "金属", "电音", "EDM", "蹦迪", "高噪音"), 0.65D, false, "用户不希望太吵");
            addBoostRule(profile, RuleField.MOOD, List.of("安静", "治愈", "轻松", "温柔"), 0.65D, "安静偏好加分");
        }

        if (containsAny(question, List.of("不要户外", "不想户外", "别户外"))) {
            addPenaltyRule(profile, RuleField.VENUE, List.of("户外", "露天", "音乐节"), 0.8D, false, "用户不希望户外");
        }

        addExplicitNegative(profile, question, "摇滚", RuleField.STYLE, List.of("摇滚", "Rock", "金属", "朋克"));
        addExplicitNegative(profile, question, "民谣", RuleField.STYLE, List.of("民谣", "Folk"));
        addExplicitNegative(profile, question, "电音", RuleField.STYLE, List.of("电子", "电音", "EDM", "DJ", "蹦迪"));
        addExplicitNegative(profile, question, "普通乐队", RuleField.TAG, List.of("普通乐队", "独立乐队", "Livehouse", "乐队现场"));
    }

    private void addExplicitNegative(IntentProfile profile, String question, String keyword, RuleField field, List<String> terms) {
        if (question.contains("不要" + keyword) || question.contains("不想看" + keyword) || question.contains("别推荐" + keyword)) {
            addPenaltyRule(profile, field, terms, 0.9D, true, "用户明确排除：" + keyword);
        }
    }

    private void parseCityPreference(IntentProfile profile, String question) {
        for (String city : KNOWN_CITIES) {
            if ("全国".equals(city)) {
                continue;
            }
            if (question.contains(city)) {
                profile.queryCity = city;
                addRequiredRule(profile, RuleField.CITY, List.of(stripCitySuffix(city)), 1.0D, "用户输入城市约束");
                profile.strictMode = true;
                return;
            }
        }
    }

    private void parseBudgetPreference(IntentProfile profile, String question) {
        if (!StringUtils.hasText(question)) {
            return;
        }

        Matcher rangeMatcher = PRICE_RANGE_PATTERN.matcher(question);
        if (rangeMatcher.find()) {
            profile.budgetMin = toBigDecimal(rangeMatcher.group(1));
            profile.budgetMax = toBigDecimal(rangeMatcher.group(2));
            profile.budgetRequired = true;
            profile.strictMode = true;
            return;
        }

        Matcher matcher = PRICE_PATTERN.matcher(question);
        while (matcher.find()) {
            BigDecimal price = toBigDecimal(matcher.group(1));
            if (price == null) {
                continue;
            }

            String hit = matcher.group();
            boolean maxIntent = containsAny(question, List.of("以内", "以下", "之内", "不超过", "低于", "便宜", "预算"));
            boolean minIntent = containsAny(question, List.of("以上", "不低于", "高于"));
            boolean aroundIntent = hit.contains("左右") || question.contains("左右");

            if (aroundIntent) {
                profile.budgetMin = price.multiply(new BigDecimal("0.8"));
                profile.budgetMax = price.multiply(new BigDecimal("1.2"));
                profile.budgetRequired = false;
            } else if (minIntent) {
                profile.budgetMin = price;
                profile.budgetRequired = false;
            } else if (maxIntent) {
                profile.budgetMax = price;
                profile.budgetRequired = true;
            }

            if (profile.budgetMin != null || profile.budgetMax != null) {
                profile.strictMode = profile.budgetRequired || profile.strictMode;
                return;
            }
        }
    }

    private void parseTimePreference(IntentProfile profile, String question) {
        TimeConstraint fromQuestion = buildTimeConstraint(question, true);
        if (fromQuestion != null) {
            profile.timeConstraint = fromQuestion;
            profile.strictMode = profile.strictMode || fromQuestion.required;
            return;
        }

        if (profile.modelIntent != null && StringUtils.hasText(profile.modelIntent.getTimePreference())) {
            profile.timeConstraint = buildTimeConstraint(profile.modelIntent.getTimePreference(), true);
        }
    }

    private TimeConstraint buildTimeConstraint(String text, boolean required) {
        if (!StringUtils.hasText(text)) {
            return null;
        }

        LocalDate today = LocalDate.now();
        String normalized = text.trim();

        if (containsAny(normalized, List.of("今天", "今日"))) {
            return new TimeConstraint("TODAY", today.atStartOfDay(), today.plusDays(1).atStartOfDay(), required, "今天");
        }

        if (containsAny(normalized, List.of("明天", "明日"))) {
            LocalDate tomorrow = today.plusDays(1);
            return new TimeConstraint("TOMORROW", tomorrow.atStartOfDay(), tomorrow.plusDays(1).atStartOfDay(), required, "明天");
        }

        if (containsAny(normalized, List.of("周末", "星期六", "星期天", "双休日", "周六", "周日"))) {
            LocalDate start = startOfCurrentOrNextWeekend(today);
            return new TimeConstraint("WEEKEND", start.atStartOfDay(), start.plusDays(2).atStartOfDay(), required, "周末");
        }

        if (containsAny(normalized, List.of("本周", "这周"))) {
            LocalDate start = today;
            LocalDate end = today.plusDays(Math.max(1, 8 - today.getDayOfWeek().getValue()));
            return new TimeConstraint("THIS_WEEK", start.atStartOfDay(), end.atTime(LocalTime.MAX), false, "本周");
        }

        if (containsAny(normalized, List.of("下周"))) {
            int daysToNextMonday = 8 - today.getDayOfWeek().getValue();
            LocalDate start = today.plusDays(daysToNextMonday);
            return new TimeConstraint("NEXT_WEEK", start.atStartOfDay(), start.plusDays(7).atStartOfDay(), false, "下周");
        }

        if (containsAny(normalized, List.of("最近", "近期", "这几天"))) {
            return new TimeConstraint("RECENT", LocalDateTime.now(), LocalDateTime.now().plusDays(30), false, "近期");
        }

        return null;
    }

    private LocalDate startOfCurrentOrNextWeekend(LocalDate today) {
        DayOfWeek day = today.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return today;
        }
        int daysToSaturday = DayOfWeek.SATURDAY.getValue() - day.getValue();
        return today.plusDays(daysToSaturday);
    }

    private void addWeakTerms(IntentProfile profile, String question) {
        profile.weakTerms.addAll(COMMON_WEAK_TERMS);
        for (String term : COMMON_WEAK_TERMS) {
            if (question.contains(term)) {
                profile.weakTerms.add(term);
            }
        }
    }

    private String resolveEffectiveCity(String currentCity, IntentProfile profile) {
        if (profile != null && StringUtils.hasText(profile.queryCity)) {
            return profile.queryCity;
        }
        return currentCity;
    }

    private String buildWeightedQueryText(String question, String city, IntentProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户核心需求：").append(question).append("\n");

        if (profile != null && StringUtils.hasText(profile.coreIntent)) {
            sb.append("核心意图：").append(profile.coreIntent).append("\n");
        }

        if (profile != null) {
            appendWeightedRules(sb, "必须匹配", profile.requiredRules);
            appendWeightedRules(sb, "偏好加分", profile.boostRules);
            appendWeightedRules(sb, "不希望匹配", profile.penaltyRules);

            if (StringUtils.hasText(profile.queryCity)) {
                sb.append("城市要求：").append(profile.queryCity).append("\n");
            } else if (StringUtils.hasText(city) && !isAllCity(city)) {
                sb.append("当前城市：").append(city).append("\n");
            }

            if (profile.budgetMax != null) {
                sb.append("预算上限：").append(profile.budgetMax.stripTrailingZeros().toPlainString()).append("元\n");
            }
            if (profile.budgetMin != null) {
                sb.append("预算下限：").append(profile.budgetMin.stripTrailingZeros().toPlainString()).append("元\n");
            }
            if (profile.timeConstraint != null) {
                sb.append("时间偏好：").append(profile.timeConstraint.label).append("\n");
            }
        }

        return sb.toString();
    }

    private void appendWeightedRules(StringBuilder sb, String title, List<TermRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return;
        }

        sb.append(title).append("：");
        for (TermRule rule : rules) {
            int repeat = repeatByWeight(rule.weight);
            for (String term : rule.terms) {
                if (!StringUtils.hasText(term) || isWeakTerm(term)) {
                    continue;
                }
                for (int i = 0; i < repeat; i++) {
                    sb.append(term).append("、");
                }
            }
        }
        sb.append("\n");
    }

    private CandidateEvaluation evaluateCandidates(List<QdrantClient.SearchResult> searchResults,
                                                   String city,
                                                   int size,
                                                   IntentProfile profile,
                                                   boolean includeAnalysis) {
        CandidateEvaluation evaluation = new CandidateEvaluation();
        List<Long> eventIds = extractEventIds(searchResults);
        if (eventIds.isEmpty()) {
            return evaluation;
        }

        Map<Long, QdrantClient.SearchResult> searchMap = searchResults.stream()
                .filter(item -> item != null && item.getEventId() != null)
                .collect(Collectors.toMap(
                        QdrantClient.SearchResult::getEventId,
                        item -> item,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        List<Event> events = eventMapper.selectBatchIds(eventIds);
        Map<Long, Event> eventMap = events == null ? Collections.emptyMap() : events.stream()
                                                                              .filter(event -> event.getId() != null)
                                                                              .collect(Collectors.toMap(Event::getId, event -> event, (a, b) -> a));

        List<Candidate> accepted = new ArrayList<>();

        for (Long eventId : eventIds) {
            Event event = eventMap.get(eventId);
            QdrantClient.SearchResult searchResult = searchMap.get(eventId);
            List<String> reasons = new ArrayList<>();
            boolean passed = true;
            double finalScore = searchResult == null || searchResult.getScore() == null ? 0D : searchResult.getScore();

            List<EventSession> sessions = Collections.emptyList();
            List<TicketCategory> tickets = Collections.emptyList();
            EventAiProfile aiProfile = null;

            if (event == null) {
                passed = false;
                reasons.add("MySQL 主表不存在该演出");
            } else if (!Objects.equals(event.getStatus(), EVENT_STATUS_ONLINE)) {
                passed = false;
                reasons.add("event.status != 1，不是上架中");
            } else if (!cityMatches(city, event.getCity())) {
                passed = false;
                reasons.add("城市不匹配：当前城市=" + city + "，演出城市=" + event.getCity());
            } else {
                sessions = loadFutureSessions(eventId);
                if (sessions.isEmpty()) {
                    passed = false;
                    reasons.add("没有未来未隐藏场次");
                }
            }

            if (passed) {
                tickets = loadTickets(eventId);
                aiProfile = eventAiProfileMapper.selectById(eventId);

                MatchDecision decision = evaluateIntentConstraints(event, aiProfile, sessions, tickets, profile);
                reasons.addAll(decision.reasons);
                if (!decision.passed) {
                    passed = false;
                }

                finalScore = calculateFinalScore(searchResult, event, aiProfile, sessions, tickets, profile, decision);
                if (passed && !isFinalScoreAcceptable(finalScore, searchResult, profile)) {
                    passed = false;
                    reasons.add("最终相关度过低 finalScore=" + roundScore(finalScore));
                }
            }

            Candidate candidate = new Candidate(event, sessions, tickets, aiProfile, searchResult, finalScore, reasons, passed);
            if (includeAnalysis) {
                evaluation.analysis.add(buildCandidateDebugRow(candidate));
            }

            if (passed) {
                accepted.add(candidate);
            }
        }

        accepted.sort(Comparator.comparingDouble(Candidate::getFinalScore).reversed());
        evaluation.accepted = accepted.stream()
                .limit(size)
                .collect(Collectors.toList());
        return evaluation;
    }

    private MatchDecision evaluateIntentConstraints(Event event,
                                                    EventAiProfile aiProfile,
                                                    List<EventSession> sessions,
                                                    List<TicketCategory> tickets,
                                                    IntentProfile profile) {
        MatchDecision decision = new MatchDecision();

        if (profile == null) {
            return decision;
        }

        for (TermRule rule : profile.requiredRules) {
            if (!matchesRule(event, aiProfile, rule)) {
                decision.passed = false;
                decision.reasons.add("不满足强约束：" + rule.label + " terms=" + rule.terms);
            } else {
                decision.matchedRequiredRules.add(rule);
                decision.reasons.add("命中强约束：" + rule.label);
            }
        }

        for (TermRule rule : profile.boostRules) {
            if (matchesRule(event, aiProfile, rule)) {
                decision.matchedBoostRules.add(rule);
                decision.reasons.add("命中加分项：" + rule.label);
            }
        }

        for (TermRule rule : profile.penaltyRules) {
            if (matchesRule(event, aiProfile, rule)) {
                decision.matchedPenaltyRules.add(rule);
                if (rule.filterIfMatched) {
                    decision.passed = false;
                    decision.reasons.add("命中排除项：" + rule.label);
                } else {
                    decision.reasons.add("命中降权项：" + rule.label);
                }
            }
        }

        if (!matchesBudget(tickets, profile, decision.reasons)) {
            decision.passed = false;
        }

        if (!matchesTime(sessions, profile, decision.reasons)) {
            decision.passed = false;
        }

        return decision;
    }

    private boolean matchesRule(Event event, EventAiProfile aiProfile, TermRule rule) {
        if (rule == null || rule.terms == null || rule.terms.isEmpty()) {
            return true;
        }

        String fieldText = extractFieldText(event, aiProfile, rule.field);
        for (String term : rule.terms) {
            if (containsTerm(fieldText, term)) {
                return true;
            }
        }
        return false;
    }

    private String extractFieldText(Event event, EventAiProfile aiProfile, RuleField field) {
        if (field == null) {
            field = RuleField.ALL;
        }

        return switch (field) {
            case STYLE -> joinText(
                    safe(event.getStyle()),
                    aiProfile == null ? "" : safe(aiProfile.getStyleTags()),
                    aiProfile == null ? "" : safe(aiProfile.getTagJson()),
                    aiProfile == null ? "" : safe(aiProfile.getAiSummary()),
                    safe(event.getTitle())
            );
            case EVENT_TYPE -> joinText(
                    aiProfile == null ? "" : safe(aiProfile.getEventType()),
                    aiProfile == null ? "" : safe(aiProfile.getTagJson()),
                    aiProfile == null ? "" : safe(aiProfile.getAiSummary()),
                    safe(event.getTitle()),
                    safe(event.getVenue())
            );
            case CITY -> safe(event.getCity());
            case VENUE -> joinText(safe(event.getVenue()), aiProfile == null ? "" : safe(aiProfile.getTagJson()));
            case MOOD, AUDIENCE, TAG, ARTIST, ALL -> buildCandidateText(event, aiProfile);
        };
    }

    private String buildCandidateText(Event event, EventAiProfile aiProfile) {
        return joinText(
                safe(event.getTitle()),
                safe(event.getStyle()),
                safe(event.getCity()),
                safe(event.getVenue()),
                aiProfile == null ? "" : safe(aiProfile.getEventType()),
                aiProfile == null ? "" : safe(aiProfile.getStyleTags()),
                aiProfile == null ? "" : safe(aiProfile.getTagJson()),
                aiProfile == null ? "" : safe(aiProfile.getAiSummary())
        );
    }

    private boolean matchesBudget(List<TicketCategory> tickets, IntentProfile profile, List<String> reasons) {
        if (profile == null || (profile.budgetMin == null && profile.budgetMax == null)) {
            return true;
        }

        BigDecimal minPrice = resolveMinPriceValue(tickets);
        if (minPrice == null) {
            if (profile.budgetRequired) {
                reasons.add("用户有预算要求，但该演出暂无票价");
                return false;
            }
            return true;
        }

        if (profile.budgetMax != null && minPrice.compareTo(profile.budgetMax) > 0) {
            if (profile.budgetRequired) {
                reasons.add("票价超过预算上限：minPrice=" + minPrice + " budgetMax=" + profile.budgetMax);
                return false;
            }
            reasons.add("票价略高于预算，降权处理");
        } else if (profile.budgetMax != null) {
            reasons.add("满足预算上限：" + profile.budgetMax + "元以内");
        }

        if (profile.budgetMin != null && minPrice.compareTo(profile.budgetMin) < 0) {
            reasons.add("票价低于预算下限偏好");
        }

        return true;
    }

    private boolean matchesTime(List<EventSession> sessions, IntentProfile profile, List<String> reasons) {
        if (profile == null || profile.timeConstraint == null) {
            return true;
        }

        boolean matched = sessions != null && sessions.stream()
                .map(EventSession::getShowTime)
                .filter(Objects::nonNull)
                .anyMatch(time -> !time.isBefore(profile.timeConstraint.start) && time.isBefore(profile.timeConstraint.end));

        if (matched) {
            reasons.add("满足时间偏好：" + profile.timeConstraint.label);
            return true;
        }

        if (profile.timeConstraint.required) {
            reasons.add("不满足时间约束：" + profile.timeConstraint.label);
            return false;
        }

        reasons.add("未命中时间偏好：" + profile.timeConstraint.label + "，降权处理");
        return true;
    }

    private double calculateFinalScore(QdrantClient.SearchResult searchResult,
                                       Event event,
                                       EventAiProfile aiProfile,
                                       List<EventSession> sessions,
                                       List<TicketCategory> tickets,
                                       IntentProfile profile,
                                       MatchDecision decision) {
        double score = searchResult == null || searchResult.getScore() == null ? 0D : searchResult.getScore();

        if (profile == null) {
            return score;
        }

        for (TermRule rule : decision.matchedRequiredRules) {
            score += 0.30D * rule.weight;
        }

        for (TermRule rule : decision.matchedBoostRules) {
            score += 0.15D * rule.weight;
        }

        for (TermRule rule : decision.matchedPenaltyRules) {
            score -= rule.filterIfMatched ? 999D : 0.25D * rule.weight;
        }

        BigDecimal minPrice = resolveMinPriceValue(tickets);
        if (minPrice != null && profile.budgetMax != null) {
            if (minPrice.compareTo(profile.budgetMax) <= 0) {
                score += 0.10D;
            } else if (!profile.budgetRequired) {
                score -= 0.08D;
            }
        }

        if (profile.timeConstraint != null && sessions != null) {
            boolean timeMatched = sessions.stream()
                    .map(EventSession::getShowTime)
                    .filter(Objects::nonNull)
                    .anyMatch(time -> !time.isBefore(profile.timeConstraint.start) && time.isBefore(profile.timeConstraint.end));
            if (timeMatched) {
                score += 0.12D;
            } else if (!profile.timeConstraint.required) {
                score -= 0.06D;
            }
        }

        return score;
    }

    private boolean isFinalScoreAcceptable(double finalScore,
                                           QdrantClient.SearchResult searchResult,
                                           IntentProfile profile) {
        if (profile != null && !profile.requiredRules.isEmpty()) {
            return finalScore > 0D;
        }

        double qdrantScore = searchResult == null || searchResult.getScore() == null ? 0D : searchResult.getScore();
        return qdrantScore >= MIN_SCORE_WITHOUT_REQUIRED_RULE || finalScore >= MIN_SCORE_WITHOUT_REQUIRED_RULE;
    }

    private List<EventSession> loadFutureSessions(Long eventId) {
        LocalDateTime now = LocalDateTime.now();

        return eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>()
                        .eq(EventSession::getEventId, eventId)
                        .ne(EventSession::getStatus, SESSION_STATUS_HIDDEN)
                        .isNotNull(EventSession::getShowTime)
                        .gt(EventSession::getShowTime, now)
                        .orderByAsc(EventSession::getShowTime)
        );
    }

    private List<TicketCategory> loadTickets(Long eventId) {
        return ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
        );
    }

    private AiAssistantEventVO buildEventVO(Candidate candidate) {
        Event event = candidate.event;
        AiAssistantEventVO vo = new AiAssistantEventVO();

        vo.setId(event.getId());
        vo.setTitle(event.getTitle());
        vo.setPosterUrl(event.getPosterUrl());
        vo.setCity(event.getCity());
        vo.setVenue(event.getVenue());

        if (candidate.sessions != null && !candidate.sessions.isEmpty() && candidate.sessions.get(0).getShowTime() != null) {
            vo.setShowTime(String.valueOf(candidate.sessions.get(0).getShowTime()));
        }

        if (candidate.aiProfile != null) {
            vo.setEventType(candidate.aiProfile.getEventType());
        }

        vo.setMinPrice(resolveMinPrice(candidate.tickets));
        vo.setReason(buildReason(candidate));
        return vo;
    }

    private String resolveMinPrice(List<TicketCategory> tickets) {
        BigDecimal minPrice = resolveMinPriceValue(tickets);
        return minPrice == null ? null : minPrice.stripTrailingZeros().toPlainString();
    }

    private BigDecimal resolveMinPriceValue(List<TicketCategory> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return null;
        }

        Optional<BigDecimal> minPrice = tickets.stream()
                .map(TicketCategory::getPrice)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo);

        return minPrice.orElse(null);
    }

    private String buildReason(Candidate candidate) {
        String summary = candidate.aiProfile == null ? "" : safe(candidate.aiProfile.getAiSummary());

        List<String> matched = candidate.reasons.stream()
                .filter(reason -> reason.startsWith("命中") || reason.startsWith("满足"))
                .limit(2)
                .collect(Collectors.toList());

        if (!matched.isEmpty()) {
            return "匹配原因：" + String.join("；", matched) + (StringUtils.hasText(summary) ? "。" + summary : "");
        }

        if (StringUtils.hasText(summary)) {
            return summary;
        }

        return "与您的描述语义相似度较高";
    }

    private String generateAnswer(String question, String city, List<AiAssistantEventVO> events) {
        String context = events.stream()
                .map(event -> """
                        演出ID：%s
                        标题：%s
                        城市：%s
                        场馆：%s
                        时间：%s
                        类型：%s
                        最低票价：%s
                        推荐理由：%s
                        """.formatted(
                        event.getId(),
                        safe(event.getTitle()),
                        safe(event.getCity()),
                        safe(event.getVenue()),
                        safe(event.getShowTime()),
                        safe(event.getEventType()),
                        safe(event.getMinPrice()),
                        safe(event.getReason())
                ))
                .collect(Collectors.joining("\n"));

        String prompt = """
                你是 Ave Monica 票务网站的 AI 找演出助手。
                你只能基于【候选演出列表】回答，不能编造不存在的演出。
                回答要简洁、口语化、适合用户阅读。
                不要输出 Markdown 表格。
                不要输出 JSON。
                如果用户提到预算、城市、风格、时间、人群或氛围，请结合候选演出说明推荐理由。
                最多推荐 3 场重点演出。

                当前城市：%s
                用户需求：%s

                候选演出列表：
                %s
                """.formatted(city, question, context);

        try {
            return ollamaClient.chat(prompt);
        } catch (Exception e) {
            log.warn("AI助手生成回答失败，将使用兜底回答", e);
            return buildFallbackAnswer(events);
        }
    }

    private String buildFallbackAnswer(List<AiAssistantEventVO> events) {
        if (events == null || events.isEmpty()) {
            return "暂时没有找到特别匹配的演出，你可以换个城市、风格、预算或时间再试试。";
        }

        String names = events.stream()
                .limit(3)
                .map(AiAssistantEventVO::getTitle)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("、"));

        return "我为你找到了几场比较匹配的演出：" + names + "。你可以点开卡片查看详情。";
    }

    private String buildNoResultAnswer(String question, String city, IntentProfile profile) {
        String suggestion = isAllCity(city) ? "周末演唱会" : city + " 周末演唱会";
        if (profile != null && !profile.requiredRules.isEmpty()) {
            suggestion = profile.requiredRules.get(0).terms.stream().findFirst().orElse(suggestion) + " 演出";
        }
        return "暂时没有找到完全匹配“" + question + "”的演出。你可以试试放宽城市、预算、时间或风格，比如输入“" + suggestion + "”。";
    }

    private AiAssistantChatResponse response(String answer, List<AiAssistantEventVO> events) {
        AiAssistantChatResponse response = new AiAssistantChatResponse();
        response.setAnswer(answer);
        response.setEvents(events == null ? Collections.emptyList() : events);
        return response;
    }

    private Map<String, Object> buildCandidateDebugRow(Candidate candidate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventId", candidate.event == null ? candidate.eventId() : candidate.event.getId());
        row.put("title", candidate.event == null ? null : candidate.event.getTitle());
        row.put("city", candidate.event == null ? null : candidate.event.getCity());
        row.put("status", candidate.event == null ? null : candidate.event.getStatus());
        row.put("qdrantScore", candidate.searchResult == null ? null : candidate.searchResult.getScore());
        row.put("finalScore", roundScore(candidate.finalScore));
        row.put("passed", candidate.passed);
        row.put("reasons", candidate.reasons);
        row.put("futureSessionCount", candidate.sessions == null ? 0 : candidate.sessions.size());
        row.put("minPrice", resolveMinPrice(candidate.tickets));
        return row;
    }

    private List<Map<String, Object>> buildQdrantDebugRows(List<QdrantClient.SearchResult> searchResults) {
        if (searchResults == null) {
            return Collections.emptyList();
        }

        return searchResults.stream()
                .map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("eventId", item.getEventId());
                    row.put("score", item.getScore());

                    Map<String, Object> payload = item.getPayload() == null ? Collections.emptyMap() : item.getPayload();
                    row.put("payloadTitle", payload.get("title"));
                    row.put("payloadCity", payload.get("city"));
                    row.put("payloadStatus", payload.get("status"));
                    row.put("payloadEventType", payload.get("eventType"));
                    row.put("payloadStyle", payload.get("style"));
                    row.put("payloadTags", payload.get("tags"));
                    row.put("payloadMood", payload.get("mood"));
                    row.put("payloadAudience", payload.get("audience"));
                    row.put("payloadSummary", payload.get("summary"));
                    return row;
                })
                .collect(Collectors.toList());
    }

    private List<Long> extractEventIds(List<QdrantClient.SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return Collections.emptyList();
        }

        return searchResults.stream()
                .map(QdrantClient.SearchResult::getEventId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private int resolveQdrantLimit(int size) {
        return Math.min(MAX_QDRANT_LIMIT, Math.max(MIN_QDRANT_LIMIT, size * 8));
    }

    private String normalizeQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new RuntimeException("请输入你想看的演出偏好");
        }

        String text = question.trim();
        return text.length() > MAX_QUESTION_LENGTH ? text.substring(0, MAX_QUESTION_LENGTH) : text;
    }

    private String normalizeCity(String city) {
        return StringUtils.hasText(city) ? city.trim() : "全国";
    }

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private boolean cityMatches(String queryCity, String eventCity) {
        if (!StringUtils.hasText(queryCity) || isAllCity(queryCity)) {
            return true;
        }
        if (!StringUtils.hasText(eventCity)) {
            return false;
        }
        String q = stripCitySuffix(queryCity);
        String e = stripCitySuffix(eventCity);
        return e.contains(q) || q.contains(e);
    }

    private boolean isAllCity(String city) {
        return !StringUtils.hasText(city) || "全国".equals(city) || "全部".equals(city);
    }

    private String stripCitySuffix(String city) {
        if (!StringUtils.hasText(city)) {
            return "";
        }
        return city.trim()
                .replace("特别行政区", "")
                .replace("市", "")
                .replace("省", "")
                .replace("岛", "");
    }

    private void addRequiredRule(IntentProfile profile, RuleField field, List<String> terms, double weight, String label) {
        addRule(profile.requiredRules, new TermRule(field, normalizeTerms(terms), weight, label, true));
    }

    private void addBoostRule(IntentProfile profile, RuleField field, List<String> terms, double weight, String label) {
        addRule(profile.boostRules, new TermRule(field, normalizeTerms(terms), weight, label, false));
    }

    private void addPenaltyRule(IntentProfile profile, RuleField field, List<String> terms, double weight, boolean filterIfMatched, String label) {
        addRule(profile.penaltyRules, new TermRule(field, normalizeTerms(terms), weight, label, filterIfMatched));
    }

    private void addRule(List<TermRule> rules, TermRule rule) {
        if (rule.terms.isEmpty()) {
            return;
        }
        boolean exists = rules.stream().anyMatch(item -> item.field == rule.field && item.terms.equals(rule.terms));
        if (!exists) {
            rules.add(rule);
        }
    }

    private List<String> normalizeTerms(List<String> terms) {
        if (terms == null) {
            return Collections.emptyList();
        }

        return terms.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(term -> !isWeakTerm(term))
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> expandKnownTerms(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }

        String lower = keyword.toLowerCase();
        if (containsAny(lower, List.of("二次元", "动漫", "acg", "虚拟偶像", "vocaloid", "洛天依", "初音", "bang dream", "邦邦", "声优"))) {
            return List.of("二次元", "动漫", "ACG", "虚拟偶像", "Vocaloid", "洛天依", "初音", "BanG Dream", "邦邦", "声优", "游戏音乐", "动画", "偶像企划");
        }
        if (containsAny(lower, List.of("摇滚", "rock", "朋克", "金属"))) {
            return List.of("摇滚", "Rock", "朋克", "金属", "独立摇滚");
        }
        if (containsAny(lower, List.of("民谣", "folk"))) {
            return List.of("民谣", "Folk", "独立民谣", "木吉他");
        }
        if (containsAny(lower, List.of("电音", "电子", "edm", "dj"))) {
            return List.of("电子", "电音", "EDM", "DJ", "蹦迪");
        }
        if (containsAny(lower, List.of("音乐剧", "musical"))) {
            return List.of("音乐剧", "Musical");
        }
        if (containsAny(lower, List.of("脱口秀", "喜剧"))) {
            return List.of("脱口秀", "单口喜剧", "喜剧");
        }
        return List.of(keyword.trim());
    }

    private boolean isWeakTerm(String term) {
        if (!StringUtils.hasText(term)) {
            return true;
        }
        return COMMON_WEAK_TERMS.stream().anyMatch(weak -> term.contains(weak) || weak.contains(term));
    }

    private int repeatByWeight(double weight) {
        if (weight >= 0.9D) {
            return 4;
        }
        if (weight >= 0.7D) {
            return 3;
        }
        if (weight >= 0.5D) {
            return 2;
        }
        return 1;
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.toLowerCase().contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (!StringUtils.hasText(text) || keywords == null) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean isExplicitHardKeyword(String question, String keyword) {
        if (!StringUtils.hasText(question) || !StringUtils.hasText(keyword)) {
            return false;
        }

        // 用户原文直接包含该词，才算显式强约束。
        if (containsTerm(question, keyword)) {
            return true;
        }

        // 演出类型类关键词，走专门的显式判断。
        return isKnownEventTypeKeyword(keyword) && isExplicitEventTypeInQuestion(question, keyword);
    }

    private boolean isKnownEventTypeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return false;
        }

        String k = keyword.trim().toLowerCase();

        return k.equals("live")
                || k.equals("livehouse")
                || k.equals("live house")
                || k.contains("演唱会")
                || k.contains("音乐节")
                || k.contains("音乐剧")
                || k.contains("话剧")
                || k.contains("舞台剧")
                || k.contains("脱口秀")
                || k.contains("漫展")
                || k.contains("同人展")
                || k.contains("展览")
                || k.contains("乐队现场")
                || k.contains("音乐现场")
                || k.contains("小型现场")
                || k.contains("小场地");
    }

    private boolean isExplicitEventTypeInQuestion(String question, String eventType) {
        if (!StringUtils.hasText(question) || !StringUtils.hasText(eventType)) {
            return false;
        }

        String q = question.toLowerCase().replace(" ", "");
        String type = eventType.toLowerCase().replace(" ", "");

        if (type.contains("livehouse")) {
            return containsAny(q, List.of(
                    "livehouse",
                    "livehouse演出",
                    "livehouse现场",
                    "小场地",
                    "小场地现场",
                    "小型现场",
                    "小现场",
                    "乐队现场"
            ));
        }

        if (type.contains("演唱会")) {
            return containsAny(q, List.of("演唱会", "巡演", "个人演唱会", "concert"));
        }

        if (type.contains("音乐节")) {
            return containsAny(q, List.of("音乐节", "festival"));
        }

        if (type.contains("音乐剧")) {
            return containsAny(q, List.of("音乐剧", "musical"));
        }

        if (type.contains("话剧") || type.contains("舞台剧")) {
            return containsAny(q, List.of("话剧", "舞台剧", "戏剧", "剧场"));
        }

        if (type.contains("脱口秀")) {
            return containsAny(q, List.of("脱口秀", "单口喜剧", "standup", "stand-up"));
        }

        if (type.contains("漫展") || type.contains("同人展")) {
            return containsAny(q, List.of("漫展", "同人展", "动漫展", "comic", "acg展"));
        }

        if (type.contains("展览")) {
            return containsAny(q, List.of("展览", "艺术展", "美术馆", "摄影展"));
        }

        return q.contains(type);
    }

    private boolean containsTerm(String text, String term) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(term)) {
            return false;
        }
        return text.toLowerCase().contains(term.toLowerCase());
    }

    private BigDecimal toBigDecimal(String text) {
        try {
            return StringUtils.hasText(text) ? new BigDecimal(text) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double roundScore(double score) {
        return Math.round(score * 10000D) / 10000D;
    }

    private List<String> safeList(List<String> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private List<AiQueryIntent.WeightedKeyword> safeWeightedList(List<AiQueryIntent.WeightedKeyword> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private String joinText(String... parts) {
        return String.join("\n", parts == null ? new String[0] : parts);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private enum RuleField {
        ALL,
        STYLE,
        EVENT_TYPE,
        CITY,
        ARTIST,
        VENUE,
        MOOD,
        AUDIENCE,
        TAG
    }

    private static class IntentProfile {
        private final String originalQuestion;
        private final String currentCity;
        private String coreIntent;
        private String queryCity;
        private boolean strictMode;
        private BigDecimal budgetMax;
        private BigDecimal budgetMin;
        private boolean budgetRequired;
        private TimeConstraint timeConstraint;
        private AiQueryIntent modelIntent;
        private final List<TermRule> requiredRules = new ArrayList<>();
        private final List<TermRule> boostRules = new ArrayList<>();
        private final List<TermRule> penaltyRules = new ArrayList<>();
        private final Set<String> weakTerms = new LinkedHashSet<>();

        private IntentProfile(String originalQuestion, String currentCity) {
            this.originalQuestion = originalQuestion;
            this.currentCity = currentCity;
            this.coreIntent = originalQuestion;
        }

        private Map<String, Object> toDebugMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("coreIntent", coreIntent);
            map.put("currentCity", currentCity);
            map.put("queryCity", queryCity);
            map.put("strictMode", strictMode);
            map.put("budgetMin", budgetMin);
            map.put("budgetMax", budgetMax);
            map.put("budgetRequired", budgetRequired);
            map.put("timePreference", timeConstraint == null ? null : timeConstraint.label);
            map.put("requiredRules", requiredRules.stream().map(TermRule::toDebugMap).collect(Collectors.toList()));
            map.put("boostRules", boostRules.stream().map(TermRule::toDebugMap).collect(Collectors.toList()));
            map.put("penaltyRules", penaltyRules.stream().map(TermRule::toDebugMap).collect(Collectors.toList()));
            map.put("weakTerms", new ArrayList<>(weakTerms));
            return map;
        }
    }

    private static class TermRule {
        private final RuleField field;
        private final List<String> terms;
        private final double weight;
        private final String label;
        private final boolean filterIfMatched;

        private TermRule(RuleField field, List<String> terms, double weight, String label, boolean filterIfMatched) {
            this.field = field;
            this.terms = terms == null ? Collections.emptyList() : terms;
            this.weight = weight;
            this.label = label;
            this.filterIfMatched = filterIfMatched;
        }

        private Map<String, Object> toDebugMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("field", field);
            map.put("terms", terms);
            map.put("weight", weight);
            map.put("label", label);
            map.put("filterIfMatched", filterIfMatched);
            return map;
        }
    }

    private static class TimeConstraint {
        private final String code;
        private final LocalDateTime start;
        private final LocalDateTime end;
        private final boolean required;
        private final String label;

        private TimeConstraint(String code, LocalDateTime start, LocalDateTime end, boolean required, String label) {
            this.code = code;
            this.start = start;
            this.end = end;
            this.required = required;
            this.label = label;
        }
    }

    private static class MatchDecision {
        private boolean passed = true;
        private final List<String> reasons = new ArrayList<>();
        private final List<TermRule> matchedRequiredRules = new ArrayList<>();
        private final List<TermRule> matchedBoostRules = new ArrayList<>();
        private final List<TermRule> matchedPenaltyRules = new ArrayList<>();
    }

    private static class Candidate {
        private final Event event;
        private final List<EventSession> sessions;
        private final List<TicketCategory> tickets;
        private final EventAiProfile aiProfile;
        private final QdrantClient.SearchResult searchResult;
        private final double finalScore;
        private final List<String> reasons;
        private final boolean passed;

        private Candidate(Event event,
                          List<EventSession> sessions,
                          List<TicketCategory> tickets,
                          EventAiProfile aiProfile,
                          QdrantClient.SearchResult searchResult,
                          double finalScore,
                          List<String> reasons,
                          boolean passed) {
            this.event = event;
            this.sessions = sessions == null ? Collections.emptyList() : sessions;
            this.tickets = tickets == null ? Collections.emptyList() : tickets;
            this.aiProfile = aiProfile;
            this.searchResult = searchResult;
            this.finalScore = finalScore;
            this.reasons = reasons == null ? Collections.emptyList() : reasons;
            this.passed = passed;
        }

        private double getFinalScore() {
            return finalScore;
        }

        private Long eventId() {
            return searchResult == null ? null : searchResult.getEventId();
        }
    }

    private static class CandidateEvaluation {
        private List<Candidate> accepted = new ArrayList<>();
        private final List<Map<String, Object>> analysis = new ArrayList<>();

        private List<Candidate> getAccepted() {
            return accepted;
        }

        private List<Map<String, Object>> getAnalysis() {
            return analysis;
        }
    }
}
