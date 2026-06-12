package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data // Lombok 自动生成 Get/Set/toString 等方法
@TableName("tb_user") // 告诉 MyBatis-Plus 这个类对应数据库的哪张表

public class User {

    // 告诉 MyBatis-Plus 这是一个自增主键
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField(exist = false) private List<String> permissions;
    private String username;
    private String password; // 注意：这里存的将是 BCrypt 加密后的密文
    private String phone;
    private String avatar;
    private Integer role;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String idCard;
    private String address;
    private Integer idType;
    private String realName;
    private Integer gender;
    private LocalDate birthday;
    private String bio;
    private String email;
}