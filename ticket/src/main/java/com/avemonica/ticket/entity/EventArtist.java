package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 演出-艺人关联实体类
 */
@Data
@TableName("tb_event_artist")
public class EventArtist {

    // 演出ID
    private Long eventId;

    // 艺人ID
    private Long artistId;

}