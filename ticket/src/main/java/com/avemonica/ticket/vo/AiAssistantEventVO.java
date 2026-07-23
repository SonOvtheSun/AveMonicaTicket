package com.avemonica.ticket.vo;

import lombok.Data;

@Data
public class AiAssistantEventVO {

    private Long id;

    private String title;

    private String posterUrl;

    private String city;

    private String venue;

    private String showTime;

    private String eventType;

    private String minPrice;

    private String reason;
}