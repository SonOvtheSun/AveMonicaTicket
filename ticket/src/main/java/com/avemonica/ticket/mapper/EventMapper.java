package com.avemonica.ticket.mapper;

import com.avemonica.ticket.entity.Event;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 演出表数据库访问接口
 */
@Mapper
public interface EventMapper extends BaseMapper<Event> {
    // 基础的 CRUD 方法已由 BaseMapper 提供
    // 如果以后需要编写复杂的自定义多表关联统计 SQL，可以在这里扩展方法
}