package com.avemonica.ticket.dto;

import lombok.Data;

@Data
public class AiAssistantChatRequest {

    /**
     * 用户输入的问题 / 喜好。
     */
    private String question;

    /**
     * 当前首页城市，例如 全国 / 上海 / 北京。
     */
    private String city;

    /**
     * 希望返回的演出数量。
     */
    private Integer size;
}