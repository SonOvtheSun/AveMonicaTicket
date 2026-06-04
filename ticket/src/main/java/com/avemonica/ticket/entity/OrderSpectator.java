package com.avemonica.ticket.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tb_order_spectator")
public class OrderSpectator {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long eventId;
    private Long spectatorId;
    private Long deleteToken;
}