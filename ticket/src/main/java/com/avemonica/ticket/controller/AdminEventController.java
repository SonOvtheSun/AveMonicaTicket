package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.config.AuthExp;
import com.avemonica.ticket.dto.EventAddDTO;
import com.avemonica.ticket.entity.*;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.mapper.EventSessionMapper;
import com.avemonica.ticket.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

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
    private EventSessionMapper eventSessionMapper;

    @Autowired
    private UploadFileService uploadFileService;

    @Autowired
    @Qualifier("eventLocalCache")
    private Cache<String, String> localCache;

    @Autowired
    private EventAiIndexService eventAiIndexService;

    private static final String EVENT_CACHE_KEY_PREFIX = "event:detail:";

    private static final int EVENT_STATUS_ONLINE = 1;
    private static final int EVENT_STATUS_STOPPED = 3;
    private static final int EVENT_STATUS_HIDDEN = 4;

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


    /**
     * 当前项目约定：SecurityContext 中的 authentication.name 是用户 ID。
     * userId = 1 的账号是超管 admin，编辑演出时不走审核，直接生效。
     */
    private boolean isSuperAdmin() {
        try {
            String userId = SecurityContextHolder.getContext().getAuthentication().getName();
            return "1".equals(userId);
        } catch (Exception e) {
            return false;
        }
    }

    @PostMapping("/{id}/rebuild-ai-index")
    @PreAuthorize(AuthExp.EVENT_EDIT)
    public Result<String> rebuildEventAiIndex(@PathVariable Long id) {
        eventAiIndexService.rebuildEventAiIndex(id, true);
        return Result.success("AI索引重建任务已提交，请稍后查看索引状态");
    }


    @GetMapping("/list")
    @PreAuthorize(AuthExp.EVENT_VIEW)
    public Result<IPage<Event>> listEvents(@RequestParam(defaultValue = "1") int current,
                                           @RequestParam(defaultValue = "10") int size,
                                           @RequestParam(required = false) String keyword) { // 🚨 接收 keyword
        // 将 keyword 穿透传给 Service
        return Result.success(eventService.listAdminEvents(current, size, keyword));
    }

    @GetMapping("/audit-list")
    @PreAuthorize(AuthExp.AUDIT_MANAGE)
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
            List<Map<String, Object>> artists = artistMapper.selectArtistMapsByEventId(event.getId());
            event.setArtists(artists);

            List<EventSession> sessions = eventSessionMapper.selectList(
                    new LambdaQueryWrapper<EventSession>()
                            .eq(EventSession::getEventId, event.getId())
                            .orderByAsc(EventSession::getSortOrder)
                            .orderByAsc(EventSession::getShowTime)
            );

            List<TicketCategory> tickets = ticketService.list(
                    new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getId())
            );

            Map<Long, List<TicketCategory>> ticketMapBySessionId = tickets.stream()
                    .filter(t -> t.getSessionId() != null)
                    .collect(java.util.stream.Collectors.groupingBy(TicketCategory::getSessionId));

            for (EventSession session : sessions) {
                session.setTickets(ticketMapBySessionId.getOrDefault(session.getId(), new java.util.ArrayList<>()));
            }

            event.setSessions(sessions);
            event.setTickets(tickets);

            if (Objects.equals(event.getEditAuditStatus(), Event.EDIT_AUDIT_PENDING)) {
                event.setPendingArtists(buildPendingArtists(event.getPendingPayload()));
            }
        }

        return Result.success(pageData);
    }

    @PutMapping("/audit/{id}")
    @PreAuthorize(AuthExp.AUDIT_MANAGE)
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
                    Event oldEvent = eventService.getById(id);
                    EventAddDTO dto = objectMapper.readValue(event.getPendingPayload(), EventAddDTO.class);
                    Integer finalStatus = dto.getStatus() != null ? dto.getStatus() : event.getStatus();

                    eventService.applyEventMainData(id, dto, Event.AUDIT_APPROVED, finalStatus);

                    // 修改审核通过后，如果目标状态是上架中，需要基于更新后的场次重新判断
                    if (Objects.equals(finalStatus, EVENT_STATUS_ONLINE)) {
                        Event updatedForValidation = eventService.getById(id);
                        validateCanSetOnline(updatedForValidation);
                    }


                    eventService.update(
                            new LambdaUpdateWrapper<Event>()
                                    .eq(Event::getId, id)
                                    .set(Event::getEditAuditStatus, null)
                                    .set(Event::getPendingPayload, null)
                                    .set(Event::getAuditSubmitTime, LocalDateTime.now())
                    );

                    Event updatedEvent = eventService.getById(id);
                    deleteReplacedEventImages(oldEvent, updatedEvent);

                    evictEventDetailCache(id);

                    return Result.success("演出修改审核已通过，客户端信息已同步更新");
                } catch (BusinessException e) {
                    throw e;
                } catch (Exception e) {
                    throw new BusinessException("解析演出修改审核快照失败");
                }
            } else {
                // 修改审核被驳回后，pendingPayload 中的新海报/详情图不会被任何演出使用，需要清理。
                deletePendingEventImages(event);

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
        // 2. 新增审核
        if (Objects.equals(event.getAuditStatus(), Event.AUDIT_PENDING)) {
            if (isPass) {
                Integer submittedStatus = event.getStatus();

                // 新增审核通过时，恢复提交者当初选择的状态
                if (event.getPendingPayload() != null && !event.getPendingPayload().trim().isEmpty()) {
                    try {
                        EventAddDTO dto = objectMapper.readValue(event.getPendingPayload(), EventAddDTO.class);
                        if (dto.getStatus() != null) {
                            submittedStatus = dto.getStatus();
                        }
                    } catch (Exception e) {
                        throw new BusinessException("解析新增审核提交状态失败");
                    }
                }

                eventService.update(
                        new LambdaUpdateWrapper<Event>()
                                .eq(Event::getId, id)
                                .set(Event::getAuditStatus, Event.AUDIT_APPROVED)
                                .set(Event::getStatus, submittedStatus)
                                .set(Event::getPendingPayload, null)
                                .set(Event::getAuditSubmitTime, LocalDateTime.now())
                );

                evictEventDetailCache(id);
                return Result.success("演出项目已审核通过，状态已恢复为提交者选择的状态");
            } else {
                eventService.update(
                        new LambdaUpdateWrapper<Event>()
                                .eq(Event::getId, id)
                                .set(Event::getAuditStatus, Event.AUDIT_REJECTED)
                                .set(Event::getStatus, 4)
                                .set(Event::getAuditSubmitTime, LocalDateTime.now())
                );

                evictEventDetailCache(id);
                return Result.success("已驳回该演出项目");
            }
        }

        throw new BusinessException("当前演出没有待审核申请");
    }

    @PutMapping("/revoke/{id}")
    @PreAuthorize(AuthExp.EVENT_WRITE)
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
            // 撤销后 pendingPayload 中的新海报/详情图不会被使用，需要清理。
            deletePendingEventImages(event);

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
    @PreAuthorize(AuthExp.EVENT_EDIT)
    public Result<String> updateEventStatus(@PathVariable Long id, @RequestParam Integer status) {
        Event event = eventService.getById(id);
        if(event == null){
            throw new BusinessException("演出不存在");
        }
        if(event.getAuditStatus() != Event.AUDIT_APPROVED && status != 4){
            throw new BusinessException("未审核演出无法设置为其他状态");
        }
        if (Objects.equals(status, EVENT_STATUS_ONLINE)) {
            validateCanSetOnline(event);
        }

        event.setStatus(status);
        eventService.updateById(event);
        evictEventDetailCache(id);

        return Result.success("状态更新成功", null);
    }

    @PutMapping("/{id}")
    @PreAuthorize(AuthExp.EVENT_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public Result<String> updateEvent(@PathVariable Long id, @RequestBody @Validated EventAddDTO dto) {
        Event oldEvent = eventService.getById(id);
        if (oldEvent == null) {
            throw new BusinessException("演出不存在");
        }

        eventService.updateEventWithTicketsAndArtists(id, dto);

        if (isSuperAdmin() && Objects.equals(dto.getStatus(), EVENT_STATUS_ONLINE)) {
            Event updatedForValidation = eventService.getById(id);
            validateCanSetOnline(updatedForValidation);
        }

        /*
         * 超管 admin 修改演出时，EventService 会直接覆盖主表，不走修改审核。
         * 因此这里必须在数据库更新成功后，立刻删除被替换掉的旧海报/旧详情图。
         *
         * 普通管理员修改已审核演出时，只会写 pendingPayload 等待审核，
         * 主表 posterUrl/detailsUrl 不会变化，所以不能在这里删除旧图。
         * 普通管理员的旧图删除放在 auditEvent() 修改审核通过后处理。
         */
        if (isSuperAdmin()) {
            Event updatedEvent = eventService.getById(id);
            deleteReplacedEventImages(oldEvent, updatedEvent);
        }

        evictEventDetailCache(id);
        return Result.success("修改成功");
    }

    /**
     * 审核员专属：下架演出（打回未审核状态，并强制隐藏）
     */
    @PutMapping("/takedown/{id}")
    @PreAuthorize(AuthExp.EVENT_TAKEDOWN)
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
    @PreAuthorize(AuthExp.EVENT_PUBLISH)
    public Result<String> addEvent(@RequestBody @Validated EventAddDTO dto) {
        eventService.saveEventWithTicketsAndArtists(dto);
        return Result.success("演出发布成功", null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(AuthExp.EVENT_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public Result<String> deleteEvent(@PathVariable Long id) {
        Event event = eventService.getById(id);
        if (event == null) {
            throw new BusinessException("演出不存在");
        }

        ticketService.remove(
                new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, id)
        );

        eventSessionMapper.delete(
                new LambdaQueryWrapper<EventSession>().eq(EventSession::getEventId, id)
        );

        eventArtistService.remove(
                new LambdaQueryWrapper<EventArtist>().eq(EventArtist::getEventId, id)
        );

        eventService.removeById(id);

        // 删除演出本身及 pendingPayload 中已经上传但不会再使用的海报/详情图。
        deleteAllEventImages(event);

        evictEventDetailCache(id);

        return Result.success("演出已删除", null);
    }

    @PutMapping("/confirm-edit-reject/{id}")
    @PreAuthorize(AuthExp.EVENT_EDIT)
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

    @PutMapping("/confirm-new-reject/{id}")
    @PreAuthorize(AuthExp.EVENT_EDIT)
    public Result<String> confirmEventNewReject(@PathVariable Long id) {
        Event event = eventService.getById(id);
        if (event == null) {
            throw new BusinessException("演出不存在");
        }

        if (!Objects.equals(event.getAuditStatus(), Event.AUDIT_REJECTED)) {
            throw new BusinessException("当前演出没有待确认的审核驳回状态");
        }

        eventService.update(
                new LambdaUpdateWrapper<Event>()
                        .eq(Event::getId, id)
                        // 审核未通过确认后，回到“未审核/未提交审核”状态
                        .set(Event::getAuditStatus, Event.AUDIT_REVOKED)
                        .set(Event::getStatus, Event.STATUS_HIDDEN)
                        .set(Event::getPendingPayload, null)
                        .set(Event::getAuditSubmitTime, LocalDateTime.now())
        );

        evictEventDetailCache(id);

        return Result.success("已确认审核未通过，演出已回到未审核状态");
    }


    /**
     * 删除被新图片替换掉的旧海报和旧详情图。
     *
     * 触发时机：
     * 1. 超管直接修改演出；
     * 2. 修改审核通过后真正覆盖主表。
     *
     * 注意：
     * 只有数据库已经成功更新后才会执行删除，避免“更新失败但旧图已删”的问题。
     */
    private void deleteReplacedEventImages(Event oldEvent, Event updatedEvent) {
        if (oldEvent == null || updatedEvent == null) {
            return;
        }

        deleteIfReplaced(oldEvent.getPosterUrl(), updatedEvent.getPosterUrl());
        deleteIfReplaced(oldEvent.getDetailsUrl(), updatedEvent.getDetailsUrl());
    }

    /**
     * 修改审核被驳回或撤销时，pendingPayload 中的新图不会被使用，需要删除。
     */
    private void deletePendingEventImages(Event event) {
        if (event == null || event.getPendingPayload() == null || event.getPendingPayload().trim().isEmpty()) {
            return;
        }

        try {
            EventAddDTO dto = objectMapper.readValue(event.getPendingPayload(), EventAddDTO.class);

            deleteIfReplaced(dto.getPosterUrl(), event.getPosterUrl());
            deleteIfReplaced(dto.getDetailsUrl(), event.getDetailsUrl());
        } catch (Exception e) {
            log.warn("解析 pendingPayload 中的演出图片失败，eventId={}", event.getId(), e);
        }
    }

    /**
     * 删除演出时，清理当前主表图片，以及 pendingPayload 中可能存在的新图。
     */
    private void deleteAllEventImages(Event event) {
        if (event == null) {
            return;
        }

        Set<String> urls = new HashSet<>();
        collectEventImageUrl(urls, event.getPosterUrl());
        collectEventImageUrl(urls, event.getDetailsUrl());

        if (event.getPendingPayload() != null && !event.getPendingPayload().trim().isEmpty()) {
            try {
                EventAddDTO dto = objectMapper.readValue(event.getPendingPayload(), EventAddDTO.class);
                collectEventImageUrl(urls, dto.getPosterUrl());
                collectEventImageUrl(urls, dto.getDetailsUrl());
            } catch (Exception e) {
                log.warn("解析待删除演出的 pendingPayload 图片失败，eventId={}", event.getId(), e);
            }
        }

        urls.forEach(this::deleteEventImageQuietly);
    }

    private void deleteIfReplaced(String oldUrl, String newUrl) {
        if (oldUrl == null || oldUrl.trim().isEmpty()) {
            return;
        }

        if (Objects.equals(oldUrl, newUrl)) {
            return;
        }

        deleteEventImageQuietly(oldUrl);
    }

    private void collectEventImageUrl(Set<String> urls, String url) {
        if (isDeletableEventImage(url)) {
            urls.add(url);
        }
    }

    /**
     * 只允许删除演出物料目录下的本地上传图片，避免误删头像、系统背景图、外链图片。
     * 当前 AddEventForm 中海报和详情图都上传到 poster 目录。
     */
    private boolean isDeletableEventImage(String url) {
        return url != null
                && url.startsWith("/uploads/poster/")
                && uploadFileService.isLocalUploadUrl(url);
    }

    private void deleteEventImageQuietly(String url) {
        if (!isDeletableEventImage(url)) {
            return;
        }

        try {
            uploadFileService.deleteUploadFile(url);
        } catch (Exception e) {
            log.warn("删除演出图片失败，url={}", url, e);
        }
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

    /**
     * 设置演出为“上架中”之前，校验是否已经过了演出时间。
     *
     * 规则：
     * 1. 优先检查多场次 tb_event_session；
     * 2. 只要存在一个未来场次，就允许上架；
     * 3. 如果所有已配置场次都已经过期，则禁止上架；
     * 4. 如果没有配置任何 showTime，认为是“时间待定”，不在这里拦截。
     */
    private void validateCanSetOnline(Event event) {
        if (event == null || event.getId() == null) {
            throw new BusinessException("演出不存在");
        }

        LocalDateTime now = LocalDateTime.now();

        List<EventSession> sessions = eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>()
                        .eq(EventSession::getEventId, event.getId())
        );

        boolean hasConfiguredShowTime = false;
        boolean hasFutureShowTime = false;
        LocalDateTime latestShowTime = null;

        if (sessions != null && !sessions.isEmpty()) {
            for (EventSession session : sessions) {
                if (session == null) {
                    continue;
                }

                // 隐藏场次不参与判断
                if (Objects.equals(session.getStatus(), EVENT_STATUS_HIDDEN)) {
                    continue;
                }

                LocalDateTime showTime = session.getShowTime();
                if (showTime == null) {
                    continue;
                }

                hasConfiguredShowTime = true;

                if (latestShowTime == null || showTime.isAfter(latestShowTime)) {
                    latestShowTime = showTime;
                }

                if (showTime.isAfter(now)) {
                    hasFutureShowTime = true;
                    break;
                }
            }

            if (hasConfiguredShowTime && !hasFutureShowTime) {
                throw new BusinessException(
                        "该演出的所有场次均已结束，不能设置为上架中。最后一场演出时间："
                                + latestShowTime
                );
            }

            return;
        }

        // 兼容旧数据：如果主表 event.showTime 有值，也做一次兜底判断
        if (event.getShowTime() != null && !event.getShowTime().isAfter(now)) {
            throw new BusinessException(
                    "该演出时间已过，不能设置为上架中。演出时间："
                            + event.getShowTime()
            );
        }
    }
}
