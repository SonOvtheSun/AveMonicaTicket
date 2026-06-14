package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tb_event_reservation")
public class EventReservation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long eventId;
    private Long ticketId;
    private String spectatorIds; // 存入数据库的逗号分隔字符串
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long sessionId;
}