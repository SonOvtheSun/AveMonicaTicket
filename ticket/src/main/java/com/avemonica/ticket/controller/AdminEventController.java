package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.EventAddDTO;
import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.service.ArtistService;
import com.avemonica.ticket.service.EventService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.avemonica.ticket.entity.Event;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.service.TicketService;
import com.avemonica.ticket.entity.EventArtist;
import com.avemonica.ticket.service.EventArtistService;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Objects;

import java.util.List;
import java.util.Map;

import static com.avemonica.ticket.entity.Event.AUDIT_PENDING;

@Slf4j
@RestController
@RequestMapping("/api/admin/event")
public class AdminEventController {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventService eventService;

    @Autowired
    private ArtistMapper artistMapper;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private EventArtistService eventArtistService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("eventLocalCache")
    private Cache<String, String> localCache;

    private static final String EVENT_CACHE_KEY_PREFIX = "event:detail:";

    private void evictEventDetailCache(Long eventId) {
        if (eventId == null) {
            return;
        }
        String cacheKey = EVENT_CACHE_KEY_PREFIX + eventId;
        try {
            localCache.invalidate(cacheKey);
            redisTemplate.delete(cacheKey);
            log.info("已删除演出详情 L1/L2 缓存，cacheKey={}", cacheKey);
        } catch (Exception e) {
            log.warn("删除演出详情缓存失败，cacheKey={}", cacheKey, e);
        }
    }


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

        wrapper.and(w -> w
                .eq(Event::getAuditStatus, Event.AUDIT_PENDING)
                .or()
                .eq(Event::getEditAuditStatus, Event.EDIT_AUDIT_PENDING)
        );
        wrapper.orderByDesc(Event::getAuditSubmitTime);

        wrapper.orderByDesc(Event::getCreateTime);

        IPage<Event> pageData = eventService.page(new Page<>(current, size), wrapper);

        // 🚨 核心逻辑：使用 Map 来接收并组装
        for (Event event : pageData.getRecords()) {
            // 当前已生效的参演艺人
            List<Map<String, Object>> artists = artistMapper.selectArtistMapsByEventId(event.getId());
            event.setArtists(artists);

            // 当前已生效的票档
            List<TicketCategory> tickets = ticketService.list(
                    new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getId())
            );
            event.setTickets(tickets);

            // 如果是修改审核，则额外组装“修改后的艺人”
            if (Objects.equals(event.getEditAuditStatus(), Event.EDIT_AUDIT_PENDING)) {
                event.setPendingArtists(buildPendingArtists(event.getPendingPayload()));
            }
        }

        return Result.success(pageData);
    }

    @PutMapping("/audit/{id}")
    @PreAuthorize("hasAuthority('event:audit') or principal.username == '1'")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> auditEvent(@PathVariable Long id, @RequestParam Boolean isPass) {
        Event event = eventService.getById(id);
        if (event == null) {
            return Result.error("该演出记录不存在");
        }

        // 1. 修改审核：审核通过后才覆盖主表
        if (Objects.equals(event.getEditAuditStatus(), Event.EDIT_AUDIT_PENDING)) {
            if (isPass) {
                try {
                    EventAddDTO dto = objectMapper.readValue(event.getPendingPayload(), EventAddDTO.class);
                    Integer finalStatus = dto.getStatus() != null ? dto.getStatus() : event.getStatus();

                    eventService.applyEventMainData(id, dto, Event.AUDIT_APPROVED, finalStatus);

                    eventService.update(
                            new LambdaUpdateWrapper<Event>()
                                    .eq(Event::getId, id)
                                    .set(Event::getEditAuditStatus, null)
                                    .set(Event::getPendingPayload, null)
                                    .set(Event::getAuditSubmitTime, LocalDateTime.now())
                    );


                    evictEventDetailCache(id);

                    return Result.success("演出修改审核已通过，客户端信息已同步更新");
                } catch (Exception e) {
                    throw new BusinessException("解析演出修改审核快照失败");
                }
            } else {
                eventService.update(
                        new LambdaUpdateWrapper<Event>()
                                .eq(Event::getId, id)
                                .set(Event::getEditAuditStatus, 2)
                                .set(Event::getPendingPayload, null)
                                .set(Event::getAuditSubmitTime, LocalDateTime.now())
                );

                return Result.success("已驳回演出修改申请，客户端继续展示原信息");
            }
        }

        // 2. 新增审核
        if (Objects.equals(event.getAuditStatus(), Event.AUDIT_PENDING)) {
            if (isPass) {
                event.setAuditStatus(Event.AUDIT_APPROVED);
                event.setStatus(3);
            } else {
                event.setAuditStatus(Event.AUDIT_REJECTED);
                event.setStatus(4);
            }

            eventService.updateById(event);
            evictEventDetailCache(id);

            return Result.success(isPass ? "演出项目已审核通过" : "已驳回该演出项目");
        }

        throw new BusinessException("当前演出没有待审核申请");
    }

    @PutMapping("/revoke/{id}")
    @PreAuthorize("hasAuthority('event:publish') or principal.username == '1'")
    public Result<String> revokeEventAudit(@PathVariable Long id) {
        Event event = eventService.getById(id);
        if (event == null) {
            throw new BusinessException("演出不存在");
        }

        // 撤销新增审核
        if (Objects.equals(event.getAuditStatus(), Event.AUDIT_PENDING)) {
            event.setAuditStatus(Event.AUDIT_REVOKED);
            event.setStatus(4);
            eventService.updateById(event);
            evictEventDetailCache(id);
            return Result.success("已撤销新增审核申请，可重新编辑后提交");
        }

        // 撤销修改审核
        if (Objects.equals(event.getEditAuditStatus(), Event.EDIT_AUDIT_PENDING)) {
            eventService.update(
                    new LambdaUpdateWrapper<Event>()
                            .eq(Event::getId, id)
                            .set(Event::getEditAuditStatus, null)
                            .set(Event::getPendingPayload, null)
                            .set(Event::getAuditSubmitTime, LocalDateTime.now())
            );
            return Result.success("已撤销修改审核申请，客户端信息未受影响");
        }

        throw new BusinessException("当前状态无需撤销审核");
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
        evictEventDetailCache(id);


        return Result.success("状态更新成功", null);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('event:publish') or principal.username == '1'")
    public Result<String> updateEvent(@PathVariable Long id, @RequestBody @Validated EventAddDTO dto) {
        eventService.updateEventWithTicketsAndArtists(id, dto);
        evictEventDetailCache(id);
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
        evictEventDetailCache(id);

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
        evictEventDetailCache(id);
        return Result.success("演出已删除", null);
    }

    @PutMapping("/confirm-edit-reject/{id}")
    @PreAuthorize("hasAuthority('event:publish') or principal.username == '1'")
    public Result<String> confirmEventEditReject(@PathVariable Long id) {
        Event event = eventService.getById(id);
        if (event == null) {
            throw new BusinessException("演出不存在");
        }

        if (!Objects.equals(event.getEditAuditStatus(), Event.EDIT_AUDIT_REJECTED)) {
            throw new BusinessException("当前演出没有待确认的修改驳回状态");
        }

        eventService.update(
                new LambdaUpdateWrapper<Event>()
                        .eq(Event::getId, id)
                        .set(Event::getEditAuditStatus, null)
                        .set(Event::getPendingPayload, null)
                        .set(Event::getAuditSubmitTime, LocalDateTime.now())
        );

        return Result.success("已确认修改驳回结果");
    }

    private List<Map<String, Object>> buildPendingArtists(String pendingPayload) {
        if (pendingPayload == null || pendingPayload.trim().isEmpty()) {
            return new java.util.ArrayList<>();
        }

        try {
            EventAddDTO dto = objectMapper.readValue(pendingPayload, EventAddDTO.class);

            if (dto.getArtistIds() == null || dto.getArtistIds().isEmpty()) {
                return new java.util.ArrayList<>();
            }

            List<Artist> artistList = artistMapper.selectBatchIds(dto.getArtistIds());

            Map<Long, Artist> artistMap = artistList.stream()
                    .collect(java.util.stream.Collectors.toMap(Artist::getId, a -> a));

            return dto.getArtistIds().stream().map(artistId -> {
                Map<String, Object> map = new java.util.HashMap<>();
                Artist artist = artistMap.get(artistId);

                map.put("id", artistId);

                if (artist == null) {
                    map.put("name", "未知艺人");
                    map.put("notFound", true);
                    map.put("auditStatus", null);
                } else {
                    map.put("name", artist.getName());
                    map.put("avatarUrl", artist.getAvatarUrl());
                    map.put("style", artist.getStyle());
                    map.put("auditStatus", artist.getAuditStatus());
                    map.put("notFound", false);
                }

                return map;
            }).collect(java.util.stream.Collectors.toList());

        } catch (Exception e) {
            log.warn("解析修改审核中的艺人信息失败，pendingPayload={}", pendingPayload, e);
            return new java.util.ArrayList<>();
        }
    }
}
