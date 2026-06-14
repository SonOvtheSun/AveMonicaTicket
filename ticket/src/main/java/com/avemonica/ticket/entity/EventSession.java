package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("tb_event_session")
public class EventSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属演出ID
     */
    private Long eventId;

    /**
     * 场次名称：如 下午场、晚场、Day1
     */
    private String sessionName;

    /**
     * 本场演出时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime showTime;

    /**
     * 本场开票时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime saleTime;

    /**
     * 状态：1上架，3停售，4隐藏
     */
    private Integer status;

    /**
     * 排序
     */
    private Integer sortOrder;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 当前场次下的票档
     */
    @TableField(exist = false)
    private List<TicketCategory> tickets;
}