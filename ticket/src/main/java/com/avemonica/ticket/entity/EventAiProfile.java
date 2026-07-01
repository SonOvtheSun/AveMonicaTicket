package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_event_ai_profile")
public class EventAiProfile {

    @TableId(value = "event_id", type = IdType.INPUT)
    private Long eventId;

    private String tagJson;

    private String styleTags;

    private String city;

    private String eventType;

    private String aiSummary;

    private String embeddingText;

    private String posterUrl;

    private String detailsUrl;

    private String vectorCollection;

    private String vectorPointId;

    private String llmModel;

    private String embeddingModel;

    private String sourceHash;

    /**
     * 0 待处理，1 成功，2 失败
     */
    private Integer indexStatus;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer indexProgress;

    private String indexStep;

    private LocalDateTime indexStartTime;

    private LocalDateTime indexFinishTime;
}