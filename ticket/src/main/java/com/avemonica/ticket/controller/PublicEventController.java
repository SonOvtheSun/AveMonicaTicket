package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.TicketService; // 或者注入 TicketMapper
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/event")
public class PublicEventController {

    @Autowired
    private EventService eventService;

    @Autowired
    private TicketService ticketService; // 🚨 注入票档业务层（若无Service，可直接注入 TicketMapper）

    @Autowired
    private com.avemonica.ticket.mapper.ArtistMapper artistMapper;

    @GetMapping("/upcoming")
    public Result<List<Event>> getUpcomingEvents() {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();

        // 1. 筛选状态为预售中（1）且演出时间大于当前时间的记录，随机取 8 条
        wrapper.eq(Event::getStatus, 1)
                .gt(Event::getShowTime, LocalDateTime.now())
                .last("ORDER BY RAND() LIMIT 10");

        List<Event> events = eventService.list(wrapper);

        // 2. 🚨 核心修复：补充票档数据链路
        // 遍历这 8 场演出，去数据库把它们各自的票档策略（含价格）查出来并塞进去
        for (Event event : events) {
            List<TicketCategory> tickets = ticketService.list(
                    new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getId())
            );
            event.setTickets(tickets); // 🚨 将票档列表注入 Event 实体，对齐前端的 event.tickets
        }

        return Result.success(events);
    }

    @GetMapping("/{id}")
    public Result<Event> getEventDetail(@PathVariable Long id) {
        // 1. 查基础信息
        Event event = eventService.getById(id);
        if (event == null || event.getStatus() == 4) { // 4为已隐藏
            return Result.error("该演出不存在或已下架");
        }

        // 2. 查票档信息
        List<TicketCategory> tickets = ticketService.list(
                new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, id)
        );
        event.setTickets(tickets);

        // 3. 查参演艺人信息 (复用你在 Admin 里的写法)
        List<java.util.Map<String, Object>> artists = artistMapper.selectArtistMapsByEventId(id);
        event.setArtists(artists);

        return Result.success(event);
    }
}