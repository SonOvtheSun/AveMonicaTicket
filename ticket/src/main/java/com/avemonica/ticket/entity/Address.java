package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tb_address")
public class Address {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String receiverName;
    private String phone;
    private String province; // 演示方便，可以暂时前端不用省市级联，直接填在 detailAddress 里
    private String city;
    private String district;
    private String detailAddress;
    private Integer isDefault;
    private Integer isDeleted;
}