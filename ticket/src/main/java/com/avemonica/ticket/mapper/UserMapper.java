package com.avemonica.ticket.mapper;

import com.avemonica.ticket.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper // 告诉 Spring Boot 这是一个 MyBatis 的 Mapper
public interface UserMapper extends BaseMapper<User> {
    // 基础的 CRUD 已经全部集成，无需手写！
}