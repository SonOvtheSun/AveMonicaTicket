
package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.dto.EventAddDTO;
import com.avemonica.ticket.dto.EventSessionDTO;
import com.avemonica.ticket.dto.TicketCategoryDTO;
import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.EventArtist;
import com.avemonica.ticket.entity.EventSession;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.entity.User;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.mapper.EventMapper;
import com.avemonica.ticket.mapper.EventSessionMapper;
import com.avemonica.ticket.mapper.TicketCategoryMapper;
import com.avemonica.ticket.mapper.UserMapper;
import com.avemonica.ticket.service.EventArtistService;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EventServiceImpl extends ServiceImpl<EventMapper, Event> implements EventService {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private EventSessionMapper eventSessionMapper;

    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;

    @Autowired
    private EventArtistService eventArtistService;

    @Autowired
    private ArtistMapper artistMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    public static final String EVENT_VIEWS_KEY = "event:views:";
    public static final String EVENT_WANT_KEY = "event:want:";

    private static final int EVENT_STATUS_ONLINE = 1;
    private static final int EVENT_STATUS_HIDDEN = 4;
    private static final int SESSION_STATUS_HIDDEN = 4;

    /**
     * C 端“想看”切换。
     *
     * Redis Set 结构：
     * key: event:want:{eventId}
     * value: userId
     */
    @Override
    public boolean toggleWant(Long eventId, Long userId) {
        String wantKey = EVENT_WANT_KEY + eventId;
        String userIdText = String.valueOf(userId);

        Boolean isMember = redisTemplate.opsForSet().isMember(wantKey, userIdText);
        if (Boolean.TRUE.equals(isMember)) {
            redisTemplate.opsForSet().remove(wantKey, userIdText);
            return false;
        }

        redisTemplate.opsForSet().add(wantKey, userIdText);
        return true;
    }

    /**
     * 带实时统计数据的详情读取。
     *
     * 当前公共详情接口已在 Controller 中做多级缓存与实时数据注入；
     * 该方法保留给 Service 接口调用方使用。
     */
    @Override
    public Event getEventDetailWithRealTimeStats(Long eventId, Long currentUserId) {
        Event event = getById(eventId);
        if (event == null) {
            return null;
        }

        Long currentViews = redisTemplate.opsForValue().increment(EVENT_VIEWS_KEY + eventId);
        event.setPageViews((event.getPageViews() != null ? event.getPageViews() : 0) + currentViews.intValue());

        String wantKey = EVENT_WANT_KEY + eventId;
        Long wantCount = redisTemplate.opsForSet().size(wantKey);
        if (wantCount != null && wantCount > 0) {
            event.setWantCount(wantCount.intValue());
        }

        if (currentUserId != null) {
            Boolean hasWanted = redisTemplate.opsForSet().isMember(wantKey, String.valueOf(currentUserId));
            event.setHasWanted(Boolean.TRUE.equals(hasWanted));
        } else {
            event.setHasWanted(false);
        }

        return event;
    }

    /**
     * 新增演出。
     *
     * 新数据结构约定：
     * 1. 场次只从 dto.sessions 读取；
     * 2. 票档只存在于 sessions[*].tickets；
     * 3. 不再从 dto.showTime / dto.saleTime / dto.tickets 生成默认场次；
     * 4. 允许 sessions 为空，表示“演出时间待定”。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveEventWithTicketsAndArtists(EventAddDTO dto) {
        User currentUser = getCurrentUser();
        boolean superAdmin = isSuperAdmin(currentUser);

        List<EventSessionDTO> sessions = normalizeSessions(dto);
        validateEventSessions(dto, sessions);
        dto.setSessions(sessions);

        Event event = new Event();
        BeanUtils.copyProperties(dto, event);
        event.setCreateBy(currentUser.getId());
        fillEventSummaryTime(event, sessions);

        if (superAdmin) {
            event.setAuditStatus(Event.AUDIT_APPROVED);
            if (event.getStatus() == null) {
                event.setStatus(EVENT_STATUS_ONLINE);
            }
        } else {
            event.setPendingPayload(toJson(dto, "保存新增审核快照失败"));
            event.setAuditStatus(Event.AUDIT_PENDING);
            event.setStatus(EVENT_STATUS_HIDDEN);
        }

        event.setAuditSubmitTime(LocalDateTime.now());

        save(event);

        syncSessionsAndTickets(event.getId(), sessions);
        syncEventArtists(event.getId(), dto.getArtistIds());
    }

    /**
     * 修改演出。
     *
     * 超管：直接生效；
     * 普通管理员：
     * 1. 已通过演出 -> 写入 pendingPayload，等待修改审核；
     * 2. 新增待审核或修改待审核 -> 不允许重复提交；
     * 3. 已驳回/已撤销 -> 作为新增审核重新提交。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEventWithTicketsAndArtists(Long id, EventAddDTO dto) {
        Event oldEvent = getById(id);
        if (oldEvent == null) {
            throw new BusinessException("修改的演出不存在！");
        }

        User currentUser = getCurrentUser();
        boolean superAdmin = isSuperAdmin(currentUser);

        List<EventSessionDTO> sessions = normalizeSessions(dto);
        validateEventSessions(dto, sessions);
        dto.setSessions(sessions);

        if (superAdmin) {
            Integer finalStatus = dto.getStatus() != null ? dto.getStatus() : oldEvent.getStatus();
            applyEventMainData(id, dto, Event.AUDIT_APPROVED, finalStatus);
            return;
        }

        if (Objects.equals(oldEvent.getAuditStatus(), Event.AUDIT_PENDING)) {
            throw new BusinessException("该演出正在审核中，如需修改，请先撤销审核申请");
        }

        if (Objects.equals(oldEvent.getEditAuditStatus(), Event.EDIT_AUDIT_PENDING)) {
            throw new BusinessException("该演出的修改正在审核中，如需再次修改，请先撤销审核申请");
        }

        if (Objects.equals(oldEvent.getAuditStatus(), Event.AUDIT_APPROVED)) {
            oldEvent.setPendingPayload(toJson(dto, "保存修改审核快照失败"));
            oldEvent.setEditAuditStatus(Event.EDIT_AUDIT_PENDING);
            oldEvent.setAuditSubmitTime(LocalDateTime.now());
            updateById(oldEvent);
            return;
        }

        if (Objects.equals(oldEvent.getAuditStatus(), Event.AUDIT_REJECTED)
                || Objects.equals(oldEvent.getAuditStatus(), Event.AUDIT_REVOKED)) {
            applyEventMainData(id, dto, Event.AUDIT_PENDING, EVENT_STATUS_HIDDEN);
            return;
        }

        throw new BusinessException("当前演出状态不允许修改");
    }

    /**
     * 后台演出分页列表。
     *
     * 数据隔离：
     * 1. 超管、审核员、event:view_all 可看全部；
     * 2. 普通发布者只能看自己创建的演出。
     *
     * 返回结构：
     * 1. sessions[*].tickets 为唯一票务结构；
     * 2. artists 为后台表格展示用；
     * 3. 不再填充根级 event.tickets。
     */
    @Override
    public IPage<Event> listAdminEvents(int current, int size, String keyword) {
        User currentUser = getCurrentUser();
        boolean canViewAllEvents = canViewAllEvents(currentUser);

        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        if (!canViewAllEvents) {
            wrapper.eq(Event::getCreateBy, currentUser.getId());
        }

        if (StringUtils.hasText(keyword)) {
            applyAdminKeywordFilter(wrapper, keyword);
        }

        wrapper.orderByDesc(Event::getCreateTime);

        IPage<Event> pageData = page(new Page<>(current, size), wrapper);
        List<Event> records = pageData.getRecords();
        if (records == null || records.isEmpty()) {
            return pageData;
        }

        List<Long> eventIds = records.stream().map(Event::getId).collect(Collectors.toList());

        Map<Long, List<EventSession>> sessionMap = buildSessionMap(eventIds);
        Map<Long, List<Map<String, Object>>> artistMap = buildArtistMap(eventIds);

        for (Event event : records) {
            event.setSessions(sessionMap.getOrDefault(event.getId(), new ArrayList<>()));
            event.setArtists(artistMap.getOrDefault(event.getId(), new ArrayList<>()));
        }

        return pageData;
    }

    /**
     * 将审核快照真正落库。
     *
     * 使用场景：
     * 1. 审核通过新增演出；
     * 2. 审核通过修改演出；
     * 3. 超管直接修改演出。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyEventMainData(Long id, EventAddDTO dto, Integer auditStatus, Integer status) {
        Event oldEvent = getById(id);
        if (oldEvent == null) {
            throw new BusinessException("演出不存在");
        }

        List<EventSessionDTO> sessions = normalizeSessions(dto);
        validateEventSessions(dto, sessions);
        dto.setSessions(sessions);

        Event newEvent = new Event();
        BeanUtils.copyProperties(dto, newEvent);
        newEvent.setId(id);
        newEvent.setCreateBy(oldEvent.getCreateBy());
        newEvent.setAuditStatus(auditStatus);
        newEvent.setStatus(status);
        newEvent.setEditAuditStatus(null);
        newEvent.setPendingPayload(null);
        newEvent.setAuditSubmitTime(LocalDateTime.now());
        fillEventSummaryTime(newEvent, sessions);

        updateById(newEvent);

        /*
         * updateById 对 null 字段的处理可能受 MyBatis-Plus 策略影响。
         * 这里显式清理修改审核字段，保证状态收敛。
         */
        update(new LambdaUpdateWrapper<Event>()
                .eq(Event::getId, id)
                .set(Event::getEditAuditStatus, null)
                .set(Event::getPendingPayload, null)
        );

        syncEventArtists(id, dto.getArtistIds());
        syncSessionsAndTickets(id, sessions);
    }

    /**
     * 获取当前登录用户。
     */
    private User getCurrentUser() {
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        return userService.getOne(new LambdaQueryWrapper<User>().eq(User::getId, Long.valueOf(userId)));
    }

    private boolean isSuperAdmin(User user) {
        return user != null && Objects.equals(user.getId(), 1L);
    }

    /**
     * 管理端查看权限判断。
     */
    private boolean canViewAllEvents(User user) {
        if (isSuperAdmin(user)) {
            return true;
        }

        List<String> permissions = userMapper.selectPermissionsByUserId(user.getId());
        return permissions.contains("audit:manage") || permissions.contains("event:view_all");
    }

    /**
     * 后台关键词搜索：支持标题、场馆、艺人名、数字 ID。
     */
    private void applyAdminKeywordFilter(LambdaQueryWrapper<Event> wrapper, String keyword) {
        boolean numeric = keyword.matches("\\d+");

        wrapper.and(w -> {
            w.like(Event::getTitle, keyword)
                    .or()
                    .like(Event::getVenue, keyword)
                    .or()
                    .apply(
                            "EXISTS (" +
                                    "SELECT 1 FROM tb_event_artist ea " +
                                    "INNER JOIN tb_artist a ON ea.artist_id = a.id " +
                                    "WHERE ea.event_id = tb_event.id " +
                                    "AND a.name LIKE CONCAT('%', {0}, '%')" +
                                    ")",
                            keyword
                    );

            if (numeric) {
                w.or().eq(Event::getId, Long.valueOf(keyword));
            }
        });
    }

    /**
     * 标准化 sessions。
     *
     * 规则：
     * 1. null 或空数组表示“时间待定”；
     * 2. 没有 showTime 的 session 不算有效场次；
     * 3. 不从根级 showTime / saleTime / tickets 创建默认场次。
     */
    private List<EventSessionDTO> normalizeSessions(EventAddDTO dto) {
        if (dto.getSessions() == null || dto.getSessions().isEmpty()) {
            return new ArrayList<>();
        }

        return dto.getSessions().stream()
                .filter(session -> session != null && session.getShowTime() != null)
                .collect(Collectors.toList());
    }

    /**
     * 校验有效场次。
     *
     * 当前约束：
     * 1. 上架场次必须设置 saleTime；
     * 2. saleTime 必须早于 showTime 至少 24 小时。
     */
    private void validateEventSessions(EventAddDTO dto, List<EventSessionDTO> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        for (int i = 0; i < sessions.size(); i++) {
            EventSessionDTO session = sessions.get(i);
            String prefix = "第 " + (i + 1) + " 个场次";

            if (session.getShowTime() == null) {
                throw new BusinessException(prefix + "：请选择演出时间");
            }

            Integer sessionStatus = session.getStatus() != null ? session.getStatus() : dto.getStatus();

            if (Objects.equals(sessionStatus, EVENT_STATUS_ONLINE) && session.getSaleTime() == null) {
                throw new BusinessException(prefix + "：上架状态必须设置开票时间");
            }

            if (session.getSaleTime() != null
                    && session.getSaleTime().isAfter(session.getShowTime().minusHours(24))) {
                throw new BusinessException(prefix + "：开票时间必须早于演出时间至少 24 小时");
            }
        }
    }

    /**
     * 回填 Event 主表的摘要时间字段。
     *
     * 说明：
     * 1. event.showTime / saleTime 只用于列表排序、摘要展示；
     * 2. 购票业务以 tb_event_session 为准；
     * 3. 无有效场次时，摘要时间置空。
     */
    private void fillEventSummaryTime(Event event, List<EventSessionDTO> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            event.setShowTime(null);
            event.setSaleTime(null);
            return;
        }

        EventSessionDTO first = sessions.stream()
                .min(Comparator.comparing(EventSessionDTO::getShowTime))
                .orElse(null);

        if (first == null) {
            event.setShowTime(null);
            event.setSaleTime(null);
            return;
        }

        event.setShowTime(first.getShowTime());
        event.setSaleTime(first.getSaleTime());
    }

    /**
     * 同步演出艺人关系。
     */
    private void syncEventArtists(Long eventId, List<Long> artistIds) {
        eventArtistService.remove(new LambdaQueryWrapper<EventArtist>().eq(EventArtist::getEventId, eventId));

        if (artistIds == null || artistIds.isEmpty()) {
            return;
        }

        List<EventArtist> relations = artistIds.stream()
                .map(artistId -> {
                    EventArtist relation = new EventArtist();
                    relation.setEventId(eventId);
                    relation.setArtistId(artistId);
                    return relation;
                })
                .collect(Collectors.toList());

        eventArtistService.saveBatch(relations);
    }

    /**
     * 同步场次与场次票档。
     *
     * sessions 为空表示清空所有时间场次和票档，前台展示“演出时间待定”。
     */
    private void syncSessionsAndTickets(Long eventId, List<EventSessionDTO> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            deleteAllSessionsAndTickets(eventId);
            return;
        }

        List<EventSession> oldSessions = eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>().eq(EventSession::getEventId, eventId)
        );

        Set<Long> keepSessionIds = new HashSet<>();

        for (int i = 0; i < sessions.size(); i++) {
            EventSessionDTO dto = sessions.get(i);
            EventSession session = findExistingSession(oldSessions, dto.getId());

            if (session == null) {
                session = new EventSession();
                session.setEventId(eventId);
                session.setCreateTime(LocalDateTime.now());
            }

            session.setSessionName(StringUtils.hasText(dto.getSessionName()) ? dto.getSessionName() : "场次" + (i + 1));
            session.setShowTime(dto.getShowTime());
            session.setSaleTime(dto.getSaleTime());
            session.setStatus(dto.getStatus() != null ? dto.getStatus() : EVENT_STATUS_ONLINE);
            session.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : i);
            session.setUpdateTime(LocalDateTime.now());

            if (session.getId() == null) {
                eventSessionMapper.insert(session);
            } else {
                eventSessionMapper.updateById(session);
            }

            keepSessionIds.add(session.getId());
            syncSessionTickets(eventId, session.getId(), dto.getTickets());
        }

        deleteRemovedSessions(oldSessions, keepSessionIds);
    }

    private EventSession findExistingSession(List<EventSession> oldSessions, Long sessionId) {
        if (sessionId == null) {
            return null;
        }

        return oldSessions.stream()
                .filter(session -> Objects.equals(session.getId(), sessionId))
                .findFirst()
                .orElse(null);
    }

    private void deleteAllSessionsAndTickets(Long eventId) {
        ticketCategoryMapper.delete(new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, eventId));
        eventSessionMapper.delete(new LambdaQueryWrapper<EventSession>().eq(EventSession::getEventId, eventId));
    }

    private void deleteRemovedSessions(List<EventSession> oldSessions, Set<Long> keepSessionIds) {
        for (EventSession oldSession : oldSessions) {
            if (!keepSessionIds.contains(oldSession.getId())) {
                ticketCategoryMapper.delete(
                        new LambdaQueryWrapper<TicketCategory>()
                                .eq(TicketCategory::getSessionId, oldSession.getId())
                );
                eventSessionMapper.deleteById(oldSession.getId());
            }
        }
    }

    /**
     * 同步单个场次下的票档。
     *
     * 新数据结构要求：
     * 1. 新增票档 id 为空；
     * 2. 修改票档必须带 id；
     * 3. 不再使用票档名称匹配旧记录。
     */
    private void syncSessionTickets(Long eventId, Long sessionId, List<TicketCategoryDTO> tickets) {
        List<TicketCategory> oldTickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
                        .eq(TicketCategory::getSessionId, sessionId)
        );

        if (tickets == null || tickets.isEmpty()) {
            for (TicketCategory oldTicket : oldTickets) {
                ticketCategoryMapper.deleteById(oldTicket.getId());
            }
            return;
        }

        Set<Long> keepTicketIds = new HashSet<>();

        for (TicketCategoryDTO dto : tickets) {
            if (!StringUtils.hasText(dto.getName())) {
                continue;
            }

            TicketCategory ticket = dto.getId() == null ? null : findExistingTicket(oldTickets, dto.getId());

            if (ticket == null) {
                ticket = new TicketCategory();
                ticket.setEventId(eventId);
                ticket.setSessionId(sessionId);
                ticket.setName(dto.getName());
                ticket.setPrice(dto.getPrice());
                ticket.setTotalStock(dto.getStock());
                ticket.setRemainingStock(dto.getStock());
                ticketCategoryMapper.insert(ticket);
            } else {
                updateExistingTicket(ticket, dto);
                ticketCategoryMapper.updateById(ticket);
            }

            keepTicketIds.add(ticket.getId());
        }

        for (TicketCategory oldTicket : oldTickets) {
            if (!keepTicketIds.contains(oldTicket.getId())) {
                ticketCategoryMapper.deleteById(oldTicket.getId());
            }
        }
    }

    private TicketCategory findExistingTicket(List<TicketCategory> oldTickets, Long ticketId) {
        return oldTickets.stream()
                .filter(ticket -> Objects.equals(ticket.getId(), ticketId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 修改已有票档时保留已售数量。
     *
     * remainingStock 调整规则：
     * 新剩余库存 = 旧剩余库存 + 新总库存 - 旧总库存。
     */
    private void updateExistingTicket(TicketCategory ticket, TicketCategoryDTO dto) {
        int oldTotalStock = ticket.getTotalStock() != null ? ticket.getTotalStock() : 0;
        int oldRemainingStock = ticket.getRemainingStock() != null ? ticket.getRemainingStock() : 0;
        int newStock = dto.getStock() != null ? dto.getStock() : 0;
        int stockDiff = newStock - oldTotalStock;

        ticket.setName(dto.getName());
        ticket.setPrice(dto.getPrice());
        ticket.setTotalStock(newStock);
        ticket.setRemainingStock(Math.max(0, oldRemainingStock + stockDiff));
    }

    /**
     * 批量装配场次与票档。
     */
    private Map<Long, List<EventSession>> buildSessionMap(List<Long> eventIds) {
        List<EventSession> sessions = eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>()
                        .in(EventSession::getEventId, eventIds)
                        .ne(EventSession::getStatus, SESSION_STATUS_HIDDEN)
                        .orderByAsc(EventSession::getSortOrder)
                        .orderByAsc(EventSession::getShowTime)
        );

        if (sessions.isEmpty()) {
            return new HashMap<>();
        }

        List<Long> sessionIds = sessions.stream().map(EventSession::getId).collect(Collectors.toList());

        List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>().in(TicketCategory::getSessionId, sessionIds)
        );

        Map<Long, List<TicketCategory>> ticketMap = tickets.stream()
                .collect(Collectors.groupingBy(TicketCategory::getSessionId));

        for (EventSession session : sessions) {
            session.setTickets(ticketMap.getOrDefault(session.getId(), new ArrayList<>()));
        }

        return sessions.stream().collect(Collectors.groupingBy(EventSession::getEventId));
    }

    /**
     * 批量装配艺人信息。
     */
    private Map<Long, List<Map<String, Object>>> buildArtistMap(List<Long> eventIds) {
        List<EventArtist> relations = eventArtistService.list(
                new LambdaQueryWrapper<EventArtist>().in(EventArtist::getEventId, eventIds)
        );

        if (relations.isEmpty()) {
            return new HashMap<>();
        }

        List<Long> artistIds = relations.stream()
                .map(EventArtist::getArtistId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, Artist> artistEntityMap = artistMapper.selectBatchIds(artistIds)
                .stream()
                .collect(Collectors.toMap(Artist::getId, artist -> artist));

        Map<Long, List<Map<String, Object>>> result = new HashMap<>();

        for (EventArtist relation : relations) {
            Artist artist = artistEntityMap.get(relation.getArtistId());
            Map<String, Object> artistInfo = new HashMap<>();
            artistInfo.put("id", relation.getArtistId());

            if (artist == null) {
                artistInfo.put("name", "未知艺人");
                artistInfo.put("notFound", true);
                artistInfo.put("auditStatus", null);
            } else {
                artistInfo.put("name", artist.getName());
                artistInfo.put("auditStatus", artist.getAuditStatus());
                artistInfo.put("notFound", false);
            }

            result.computeIfAbsent(relation.getEventId(), key -> new ArrayList<>()).add(artistInfo);
        }

        return result;
    }

    private String toJson(Object data, String errorMessage) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new BusinessException(errorMessage);
        }
    }
}
