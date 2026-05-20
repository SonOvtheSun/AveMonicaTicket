package com.avemonica.ticket.mapper;

import com.avemonica.ticket.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper // 告诉 Spring Boot 这是一个 MyBatis 的 Mapper
public interface UserMapper extends BaseMapper<User> {
    // 基础的 CRUD 已经全部集成，无需手写！
    /**
     * 根据用户 ID，连表查询出该用户拥有的所有权限字符 (perm_key)
     */
    @Select("SELECT p.permission_code FROM sys_permission p " +
            "JOIN tb_user u ON p.role = u.role " +
            "WHERE u.id = #{userId}")
    List<String> selectPermissionsByUserId(@Param("userId") Long userId);
}