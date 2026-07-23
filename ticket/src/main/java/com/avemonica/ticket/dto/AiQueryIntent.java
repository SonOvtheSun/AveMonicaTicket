package com.avemonica.ticket.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiQueryIntent {

    private String coreIntent;

    /**
     * 必须强匹配的关键词。
     * 例如：二次元、动漫、虚拟偶像、洛天依。
     */
    private List<String> hardKeywords = new ArrayList<>();

    /**
     * 加权偏好词。
     */
    private List<WeightedKeyword> positiveKeywords = new ArrayList<>();

    /**
     * 泛化词，应该降低权重。
     * 例如：演出、活动、想看、推荐。
     */
    private List<String> weakKeywords = new ArrayList<>();

    /**
     * 不希望出现的方向。
     */
    private List<String> negativeKeywords = new ArrayList<>();

    private String eventType;

    private String city;

    private BigDecimal budgetMax;

    private String timePreference;

    /**
     * 是否强约束模式。
     * 用户说“二次元演出”“动漫演唱会”时，应该为 true。
     */
    private Boolean strictMode = false;

    @Data
    public static class WeightedKeyword {
        private String keyword;
        private Double weight;
    }
}