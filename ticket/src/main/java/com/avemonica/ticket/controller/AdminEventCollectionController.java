package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.EventAddDTO;
import com.avemonica.ticket.dto.EventSessionDTO;
import com.avemonica.ticket.dto.TicketCategoryDTO;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.EventArtist;
import com.avemonica.ticket.entity.EventCollection;
import com.avemonica.ticket.entity.EventSession;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.mapper.EventSessionMapper;
import com.avemonica.ticket.service.EventArtistService;
import com.avemonica.ticket.service.EventCollectionService;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.TicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/collection")
public class AdminEventCollectionController {

    @Autowired
    private EventCollectionService collectionService;

    @Autowired
    private EventService eventService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private EventArtistService eventArtistService;

    @Autowired
    private EventSessionMapper eventSessionMapper;

    @Autowired
    private ArtistMapper artistMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private boolean isSuperAdmin() {
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        return "1".equals(userId);
    }

    private Long parseLongValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();

        String text = value.toString();
        if (!StringUtils.hasText(text)) return null;

        return Long.valueOf(text);
    }

    /**
     * 新版前端必须传：
     * {
     *   "name": "合集名称",
     *   "events": [
     *      { "eventId": 1, "collectionAlias": "上海场" },
     *      { "eventId": 2, "collectionAlias": "北京场" }
     *   ]
     * }
     *
     * 不再兼容旧的 eventIds: [1,2,3] 格式。
     */
    private List<CollectionEventPayload> resolveCollectionEvents(Map<String, Object> payload) {
        Object eventsObj = payload.get("events");

        if (!(eventsObj instanceof List<?>)) {
            throw new BusinessException("参数错误：events 不能为空");
        }

        List<?> rawList = (List<?>) eventsObj;
        List<CollectionEventPayload> result = new ArrayList<>();

        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }

            Map<?, ?> itemMap = (Map<?, ?>) item;

            Long eventId = parseLongValue(itemMap.get("eventId"));
            if (eventId == null) {
                continue;
            }

            String alias = itemMap.get("collectionAlias") == null
                    ? ""
                    : itemMap.get("collectionAlias").toString().trim();

            CollectionEventPayload eventPayload = new CollectionEventPayload();
            eventPayload.setEventId(eventId);
            eventPayload.setCollectionAlias(alias);
            result.add(eventPayload);
        }

        // 去重校验
        Map<Long, CollectionEventPayload> dedupMap = new LinkedHashMap<>();
        for (CollectionEventPayload item : result) {
            if (dedupMap.containsKey(item.getEventId())) {
                throw new BusinessException("同一个演出不能重复加入同一个合集，eventId=" + item.getEventId());
            }
            dedupMap.put(item.getEventId(), item);
        }

        return new ArrayList<>(dedupMap.values());
    }

    /**
     * 获取所有合集列表，同时装配当前已经正式归属该合集的演出。
     */
    @GetMapping("/list")
    public Result<List<EventCollection>> listCollections(@RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<EventCollection> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(keyword)) {
            wrapper.like(EventCollection::getName, keyword);
        }

        wrapper.orderByDesc(EventCollection::getCreateTime);

        List<EventCollection> collections = collectionService.list(wrapper);

        for (EventCollection collection : collections) {
            List<Event> relatedEvents = eventService.list(
                    new LambdaQueryWrapper<Event>()
                            .select(
                                    Event::getId,
                                    Event::getTitle,
                                    Event::getCollectionId,
                                    Event::getCollectionAlias,
                                    Event::getCity,
                                    Event::getStatus,
                                    Event::getEditAuditStatus
                            )
                            .eq(Event::getCollectionId, collection.getId())
                            .ne(Event::getStatus, Event.STATUS_HIDDEN)
            );

            collection.setEvents(relatedEvents);
        }

        return Result.success(collections);
    }

    /**
     * 获取可选演出列表。
     *
     * 新增合集：只展示未归属合集、审核已通过、无修改待审核的演出。
     * 编辑合集：展示未归属合集的演出 + 当前合集内的演出。
     */
    @GetMapping("/available-events")
    public Result<List<Event>> getAvailableEvents(@RequestParam(required = false) Long collectionId) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();

        wrapper.select(
                        Event::getId,
                        Event::getTitle,
                        Event::getCollectionId,
                        Event::getCollectionAlias,
                        Event::getCity,
                        Event::getStatus,
                        Event::getAuditStatus,
                        Event::getEditAuditStatus
                )
                .eq(Event::getAuditStatus, Event.AUDIT_APPROVED)
                .ne(Event::getStatus, Event.STATUS_HIDDEN)
                .and(w -> w.isNull(Event::getEditAuditStatus)
                        .or()
                        .ne(Event::getEditAuditStatus, Event.EDIT_AUDIT_PENDING)
                );

        if (collectionId != null) {
            wrapper.and(w -> w
                    .isNull(Event::getCollectionId)
                    .or()
                    .eq(Event::getCollectionId, collectionId)
            );
        } else {
            wrapper.isNull(Event::getCollectionId);
        }

        wrapper.orderByDesc(Event::getCreateTime);

        return Result.success(eventService.list(wrapper));
    }

    /**
     * 获取合集内演出详情：给前端管理弹窗使用，不走公共详情接口，避免增加浏览量。
     */
    @GetMapping("/event-detail/{eventId}")
    @PreAuthorize("hasAnyAuthority('event:publish', 'event:edit', 'event:view', 'audit:manage') or authentication.name == '1'")
    public Result<Event> getCollectionEventDetail(@PathVariable Long eventId) {
        Event event = eventService.getById(eventId);

        if (event == null) {
            return Result.error("演出不存在");
        }

        attachSessionsTicketsAndArtists(event);

        return Result.success(event);
    }

    /**
     * 新建合集：
     * 1. 合集名称本身直接创建；
     * 2. 如果选择了演出加入合集，则对对应演出提交“修改审核”；
     * 3. 超管免审，直接把演出加入合集。
     */
    @PostMapping("/add")
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("hasAnyAuthority('event:publish', 'audit:manage') or authentication.name == '1'")
    public Result<String> addCollection(@RequestBody Map<String, Object> payload) {
        String name = (String) payload.get("name");

        if (!StringUtils.hasText(name)) {
            return Result.error("合集名称不能为空");
        }

        EventCollection collection = new EventCollection();
        collection.setName(name);
        collectionService.save(collection);

        List<CollectionEventPayload> collectionEvents = resolveCollectionEvents(payload);

        for (CollectionEventPayload item : collectionEvents) {
            submitEventCollectionChangeAudit(
                    item.getEventId(),
                    collection.getId(),
                    item.getCollectionAlias()
            );
        }

        return Result.success(isSuperAdmin()
                ? "合集创建成功，演出已直接加入合集"
                : "合集创建成功，涉及演出的加入合集变更已提交审核");
    }

    /**
     * 编辑合集：
     * 1. 合集名称直接更新；
     * 2. 新增演出到合集：对该演出提交修改审核；
     * 3. 从合集中移除演出：对该演出提交修改审核；
     * 4. 修改演出在合集中的别名：对该演出提交修改审核；
     * 5. 没变化的演出不处理。
     */
    @PutMapping("/update")
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("hasAnyAuthority('event:publish', 'audit:manage') or authentication.name == '1'")
    public Result<String> updateCollection(@RequestBody Map<String, Object> payload) {
        Long collectionId = parseLongValue(payload.get("id"));
        String name = (String) payload.get("name");

        if (collectionId == null) {
            return Result.error("合集ID不能为空");
        }

        EventCollection collection = collectionService.getById(collectionId);
        if (collection == null) {
            return Result.error("合集不存在");
        }

        if (StringUtils.hasText(name)) {
            collection.setName(name);
            collectionService.updateById(collection);
        }

        List<CollectionEventPayload> newPayloadEvents = resolveCollectionEvents(payload);

        Map<Long, String> newEventAliasMap = new LinkedHashMap<>();
        for (CollectionEventPayload item : newPayloadEvents) {
            newEventAliasMap.put(
                    item.getEventId(),
                    item.getCollectionAlias() == null ? "" : item.getCollectionAlias()
            );
        }

        List<Event> oldEvents = eventService.list(
                new LambdaQueryWrapper<Event>()
                        .select(
                                Event::getId,
                                Event::getTitle,
                                Event::getCollectionId,
                                Event::getCollectionAlias,
                                Event::getAuditStatus,
                                Event::getEditAuditStatus
                        )
                        .eq(Event::getCollectionId, collectionId)
        );

        Map<Long, Event> oldEventMap = oldEvents.stream()
                .collect(Collectors.toMap(Event::getId, e -> e));

        int changedCount = 0;

        // 1. 新加入合集，或者原来就在合集里但别名变化
        for (Map.Entry<Long, String> entry : newEventAliasMap.entrySet()) {
            Long eventId = entry.getKey();
            String newAlias = entry.getValue();

            Event oldEvent = oldEventMap.get(eventId);

            boolean isNewAdd = oldEvent == null;
            boolean aliasChanged = oldEvent != null && !Objects.equals(
                    normalizeAlias(oldEvent.getCollectionAlias()),
                    normalizeAlias(newAlias)
            );

            if (isNewAdd || aliasChanged) {
                submitEventCollectionChangeAudit(eventId, collectionId, newAlias);
                changedCount++;
            }
        }

        // 2. 从当前合集中移除
        for (Event oldEvent : oldEvents) {
            if (!newEventAliasMap.containsKey(oldEvent.getId())) {
                submitEventCollectionChangeAudit(oldEvent.getId(), null, null);
                changedCount++;
            }
        }

        if (changedCount == 0) {
            return Result.success("合集名称已更新，演出归属未发生变化");
        }

        return Result.success(isSuperAdmin()
                ? "合集修改成功，演出归属已直接更新"
                : "合集修改成功，涉及演出的加入/移除/别名变化已提交审核");
    }

    /**
     * 删除合集：
     * 1. 空合集：直接删除；
     * 2. 有演出的合集：
     *    - 超管：直接解绑演出并删除合集；
     *    - 普通管理员：为合集内演出提交“移出合集”的演出修改审核，不直接删除合集。
     */
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("hasAnyAuthority('event:publish', 'audit:manage') or authentication.name == '1'")
    public Result<String> deleteCollection(@PathVariable Long id) {
        EventCollection collection = collectionService.getById(id);
        if (collection == null) {
            return Result.error("合集不存在");
        }

        List<Event> relatedEvents = eventService.list(
                new LambdaQueryWrapper<Event>()
                        .select(
                                Event::getId,
                                Event::getTitle,
                                Event::getCollectionId,
                                Event::getCollectionAlias,
                                Event::getAuditStatus,
                                Event::getEditAuditStatus
                        )
                        .eq(Event::getCollectionId, id)
        );

        if (relatedEvents.isEmpty()) {
            collectionService.removeById(id);
            return Result.success("空合集已删除");
        }

        if (isSuperAdmin()) {
            eventService.update(new LambdaUpdateWrapper<Event>()
                    .eq(Event::getCollectionId, id)
                    .set(Event::getCollectionId, null)
                    .set(Event::getCollectionAlias, null)
            );

            collectionService.removeById(id);
            return Result.success("合集已删除，所属演出已直接释放为单场状态");
        }

        for (Event event : relatedEvents) {
            submitEventCollectionChangeAudit(event.getId(), null, null);
        }

        return Result.success("该合集包含演出，已提交演出移出合集审核；审核通过后再删除空合集");
    }

    private String normalizeAlias(String alias) {
        return alias == null ? "" : alias.trim();
    }

    /**
     * 提交“某个演出的合集归属变化”。
     *
     * 普通管理员：
     * - 不直接改 tb_event.collection_id / collection_alias
     * - 生成 EventAddDTO 快照写入 event.pending_payload
     * - 设置 event.edit_audit_status = 0
     *
     * 超管：
     * - 直接修改生效。
     */
    private void submitEventCollectionChangeAudit(Long eventId, Long newCollectionId, String newAlias) {
        Event event = eventService.getById(eventId);

        if (event == null) {
            throw new BusinessException("演出不存在，eventId=" + eventId);
        }

        if (isSuperAdmin()) {
            eventService.update(new LambdaUpdateWrapper<Event>()
                    .eq(Event::getId, eventId)
                    .set(Event::getCollectionId, newCollectionId)
                    .set(Event::getCollectionAlias, newAlias)
            );
            return;
        }

        if (!Objects.equals(event.getAuditStatus(), Event.AUDIT_APPROVED)) {
            throw new BusinessException("演出未审核通过，不能变更合集归属：" + event.getTitle());
        }

        if (Objects.equals(event.getEditAuditStatus(), Event.EDIT_AUDIT_PENDING)) {
            throw new BusinessException("演出已有修改审核待处理，请先处理后再调整合集：" + event.getTitle());
        }

        try {
            EventAddDTO dto = buildEventCollectionChangePayload(eventId, newCollectionId, newAlias);

            eventService.update(new LambdaUpdateWrapper<Event>()
                    .eq(Event::getId, eventId)
                    .set(Event::getEditAuditStatus, Event.EDIT_AUDIT_PENDING)
                    .set(Event::getPendingPayload, objectMapper.writeValueAsString(dto))
                    .set(Event::getAuditSubmitTime, LocalDateTime.now())
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("提交演出合集变更审核失败");
        }
    }

    /**
     * 构造完整的 EventAddDTO 快照。
     * 注意：不兼容旧 tickets 格式，只生成 sessions。
     */
    private EventAddDTO buildEventCollectionChangePayload(Long eventId, Long newCollectionId, String newAlias) {
        Event event = eventService.getById(eventId);

        if (event == null) {
            throw new BusinessException("演出不存在，eventId=" + eventId);
        }

        EventAddDTO dto = new EventAddDTO();

        dto.setTitle(event.getTitle());
        dto.setShowTime(event.getShowTime());
        dto.setSaleTime(event.getSaleTime());
        dto.setCity(event.getCity());
        dto.setVenue(event.getVenue());
        dto.setAddress(event.getAddress());
        dto.setStatus(event.getStatus());
        dto.setPosterUrl(event.getPosterUrl());
        dto.setDetailsUrl(event.getDetailsUrl());
        dto.setStyle(event.getStyle());
        dto.setRunningTime(event.getRunningTime());

        dto.setCollectionId(newCollectionId);
        dto.setCollectionAlias(newAlias);

        List<EventArtist> eventArtists = eventArtistService.list(
                new LambdaQueryWrapper<EventArtist>()
                        .eq(EventArtist::getEventId, eventId)
        );

        dto.setArtistIds(
                eventArtists.stream()
                        .map(EventArtist::getArtistId)
                        .collect(Collectors.toList())
        );

        List<EventSession> sessions = eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>()
                        .eq(EventSession::getEventId, eventId)
                        .orderByAsc(EventSession::getSortOrder)
                        .orderByAsc(EventSession::getShowTime)
        );

        if (sessions.isEmpty()) {
            throw new BusinessException("演出未配置时间场次，无法提交合集变更审核：" + event.getTitle());
        }

        List<EventSessionDTO> sessionDTOs = new ArrayList<>();

        for (EventSession session : sessions) {
            EventSessionDTO sessionDTO = new EventSessionDTO();

            sessionDTO.setId(session.getId());
            sessionDTO.setSessionName(session.getSessionName());
            sessionDTO.setShowTime(session.getShowTime());
            sessionDTO.setSaleTime(session.getSaleTime());
            sessionDTO.setStatus(session.getStatus());
            sessionDTO.setSortOrder(session.getSortOrder());

            List<TicketCategory> tickets = ticketService.list(
                    new LambdaQueryWrapper<TicketCategory>()
                            .eq(TicketCategory::getEventId, eventId)
                            .eq(TicketCategory::getSessionId, session.getId())
            );

            List<TicketCategoryDTO> ticketDTOs = tickets.stream().map(ticket -> {
                TicketCategoryDTO ticketDTO = new TicketCategoryDTO();

                ticketDTO.setId(ticket.getId());
                ticketDTO.setName(ticket.getName());
                ticketDTO.setPrice(ticket.getPrice());
                ticketDTO.setStock(ticket.getTotalStock());

                return ticketDTO;
            }).collect(Collectors.toList());

            sessionDTO.setTickets(ticketDTOs);
            sessionDTOs.add(sessionDTO);
        }

        dto.setSessions(sessionDTOs);

        return dto;
    }

    /**
     * 管理端详情弹窗使用：装配 sessions、tickets、artists。
     */
    private void attachSessionsTicketsAndArtists(Event event) {
        Long eventId = event.getId();

        List<EventSession> sessions = eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>()
                        .eq(EventSession::getEventId, eventId)
                        .orderByAsc(EventSession::getSortOrder)
                        .orderByAsc(EventSession::getShowTime)
        );

        List<TicketCategory> allTickets = ticketService.list(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
        );

        Map<Long, List<TicketCategory>> ticketsBySessionId = allTickets.stream()
                .filter(ticket -> ticket.getSessionId() != null)
                .collect(Collectors.groupingBy(TicketCategory::getSessionId));

        for (EventSession session : sessions) {
            session.setTickets(
                    ticketsBySessionId.getOrDefault(session.getId(), new ArrayList<>())
            );
        }

        event.setSessions(sessions);
        event.setTickets(allTickets);

        List<Map<String, Object>> artists = artistMapper.selectArtistMapsByEventId(eventId);
        event.setArtists(artists);
    }

    private static class CollectionEventPayload {
        private Long eventId;
        private String collectionAlias;

        public Long getEventId() {
            return eventId;
        }

        public void setEventId(Long eventId) {
            this.eventId = eventId;
        }

        public String getCollectionAlias() {
            return collectionAlias;
        }

        public void setCollectionAlias(String collectionAlias) {
            this.collectionAlias = collectionAlias;
        }
    }
}