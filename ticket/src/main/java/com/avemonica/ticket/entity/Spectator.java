package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_spectator") // 假设你的观演人表叫这个
public class Spectator {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String idCard;
    private Integer idType;
    private Integer isDeleted;
    private Integer isDefault;
    private LocalDateTime createTime;
}
