package com.avemonica.ticket.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EventAiTagResult {

    /**
     * 风格标签，例如 摇滚、流行、二次元、古典、电子。
     */
    private List<String> style = new ArrayList<>();

    /**
     * 城市。
     */
    private String city;

    /**
     * 演出类型，例如 演唱会、话剧、漫展、音乐节、Livehouse、音乐剧、脱口秀、展览。
     */
    private String eventType;

    /**
     * 自由标签。
     */
    private List<String> tags = new ArrayList<>();

    /**
     * 氛围标签。
     */
    private List<String> mood = new ArrayList<>();

    /**
     * 适合人群。
     */
    private List<String> audience = new ArrayList<>();

    /**
     * 卖点。
     */
    private List<String> sellingPoints = new ArrayList<>();

    /**
     * 100 字以内精简描述。
     */
    private String summary;
}