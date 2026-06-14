package com.avemonica.ticket.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class EventAddDTO {
    private String title;          // 演出标题
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8") private LocalDateTime showTime;// 演出时间
    private String venue;          // 场地
    private String address;        // 详细地址
    private String posterUrl;      // 海报图
    private String detailsUrl;     // 详情图
    private Integer status;        // 状态 (默认可传2:预售)
    private String city;
    private Integer runningTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8") private LocalDateTime saleTime;

    private List<Long> artistIds;            // 多选：参演艺人ID列表
    private List<TicketCategoryDTO> tickets; // 动态增减：多档票价列表
    private String style;

    private Long collectionId;
    private String collectionAlias;
    private List<EventSessionDTO> sessions;

}