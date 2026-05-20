package com.avemonica.ticket.service;

import com.avemonica.ticket.dto.EventAddDTO;
import com.avemonica.ticket.entity.Event;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 演出管理业务层接口
 */
public interface EventService extends IService<Event> {

    /**
     * 发布新演出（级联保存演出基础信息、多档票价、多参演艺人）
     * * @param dto 包含演出、票档、艺人ID列表的复合 DTO
     */
    void saveEventWithTicketsAndArtists(EventAddDTO dto);

    IPage<Event> listAdminEvents(int current, int size, String keyword);

    void updateEventWithTicketsAndArtists(Long id, EventAddDTO dto);
}