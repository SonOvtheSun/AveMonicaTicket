package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.EventAddDTO;
import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.service.ArtistService;
import com.avemonica.ticket.service.EventService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.avemonica.ticket.entity.Event;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.service.TicketService;
import com.avemonica.ticket.entity.EventArtist;
import com.avemonica.ticket.service.EventArtistService;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static com.avemonica.ticket.entity.Event.AUDIT_PENDING;

@RestController
@RequestMapping("/api/admin/event")
public class AdminEventController {

    @Autowired
    private EventService eventService;

    @Autowired
    private ArtistMapper artistMapper;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private EventArtistService eventArtistService;


    @GetMapping("/list")
    @PreAuthorize("hasAuthority('event:list')")
    public Result<IPage<Event>> listEvents(@RequestParam(defaultValue = "1") int current,
                                           @RequestParam(defaultValue = "10") int size,
                                           @RequestParam(required = false) String keyword) { // 🚨 接收 keyword
        // 将 keyword 穿透传给 Service
        return Result.success(eventService.listAdminEvents(current, size, keyword));
    }





    @GetMapping("/audit-list")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == '1'")
    public Result<IPage<Event>> getPendingEvents(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "5") Integer size) {

        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Event::getAuditStatus, 0);
        wrapper.orderByDesc(Event::getCreateTime);

        IPage<Event> pageData = eventService.page(new Page<>(current, size), wrapper);

        // 🚨 核心逻辑：使用 Map 来接收并组装
        for (Event event : pageData.getRecords()) {
            List<Map<String, Object>> artists = artistMapper.selectArtistMapsByEventId(event.getId());
            event.setArtists(artists);
        }

        return Result.success(pageData);
    }

    @PutMapping("/audit/{id}")
    @PreAuthorize("hasAuthority('event:audit') or principal.username == '1'") // 假设你超管账号叫admin
    public Result<String> auditEvent(@PathVariable Long id, @RequestParam Boolean isPass) {
        Event event = eventService.getById(id);
        if (event == null) {
            return Result.error("该演出记录不存在");
        }

        if (isPass) {
            // 场景 A：审核通过
            event.setAuditStatus(1); // 1: 审核通过
            event.setStatus(3);      // 顺便将显示状态初始化为 1: 预售中
        } else {
            // 场景 B：审核驳回
            event.setAuditStatus(2); // 2: 已驳回
            event.setStatus(4);      // 强制设为 4: 已隐藏状态
        }

        eventService.updateById(event);
        return Result.success(isPass ? "演出项目已审核通过并转为预售状态" : "已驳回该演出项目的发布申请");
    }

    @PutMapping("/status/{id}")
    @PreAuthorize("hasAuthority('event:publish') or principal.username == '1'")
    public Result<String> updateEventStatus(@PathVariable Long id, @RequestParam Integer status) {
        Event event = eventService.getById(id);
        if(event == null){
            throw new BusinessException("演出不存在");
        }
        if(event.getAuditStatus() != Event.AUDIT_APPROVED && status != 4){
            throw new BusinessException("未审核演出无法设置为其他状态");
        }
        event.setStatus(status);
        eventService.updateById(event);

        return Result.success("状态更新成功", null);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('event:publish') or principal.username == '1'")
    public Result<String> updateEvent(@PathVariable Long id, @RequestBody @Validated EventAddDTO dto) {
        eventService.updateEventWithTicketsAndArtists(id, dto);
        return Result.success("修改成功");
    }

    /**
     * 审核员专属：下架演出（打回未审核状态，并强制隐藏）
     */
    @PutMapping("/takedown/{id}")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == '1'")
    public Result<String> takeDownEvent(@PathVariable Long id) {
        Event event = eventService.getById(id);
        if (event == null) {
            throw new BusinessException("演出不存在");
        }

        event.setAuditStatus(AUDIT_PENDING); // 0 代表待审核/未审核状态
        event.setStatus(4);      // 4 代表前端已隐藏状态
        eventService.updateById(event);

        return Result.success("已成功下架并打回该演出");
    }

    @PostMapping("/add")
    @PreAuthorize("hasAuthority('event:add') or principal.username == '1'")
    public Result<String> addEvent(@RequestBody @Validated EventAddDTO dto) {
        eventService.saveEventWithTicketsAndArtists(dto);
        return Result.success("演出发布成功", null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('event:delete')")
    @Transactional(rollbackFor = Exception.class) // 🚨 加上事务，防止删了票档但演出没删掉的情况发生
    public Result<String> deleteEvent(@PathVariable Long id) {
        ticketService.remove(
                new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, id)
        );
        eventArtistService.remove(
                new LambdaQueryWrapper<EventArtist>().eq(EventArtist::getEventId, id)
        );


        // 实际工业项目中推荐“逻辑删除”（将 status 设为 0），此处为物理删除演示
        eventService.removeById(id);
        return Result.success("演出已删除", null);
    }
}
