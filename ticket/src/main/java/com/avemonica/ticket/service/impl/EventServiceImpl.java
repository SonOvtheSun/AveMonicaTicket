package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.dto.EventAddDTO;
import com.avemonica.ticket.dto.EventSessionDTO;
import com.avemonica.ticket.dto.TicketCategoryDTO;
import com.avemonica.ticket.entity.*;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.*;
import com.avemonica.ticket.service.EventArtistService;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.security.core.context.SecurityContextHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.util.StringUtils;

@Service
public class EventServiceImpl extends ServiceImpl<EventMapper, Event> implements EventService {

    @Autowired
    private UserService userService;
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private EventSessionMapper eventSessionMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;
    @Autowired
    private EventArtistService eventArtistService;

    @Autowired
    private ArtistMapper artistMapper; // 注入艺人的 Mapper

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 记录某演出浏览量的 Key (String 类型: event:views:1)
    public static final String EVENT_VIEWS_KEY = "event:views:";

    // 记录某演出想看用户的 Key (Set 类型: event:want:1，内部存 userId)
    public static final String EVENT_WANT_KEY = "event:want:";

    /**
     * 1. 切换“想看”状态
     */
    @Override
    public boolean toggleWant(Long eventId, Long userId) {
        String wantKey = EVENT_WANT_KEY + eventId;

        // 判断当前用户是否在 Set 中
        Boolean isMember = redisTemplate.opsForSet().isMember(wantKey, userId.toString());

        if (Boolean.TRUE.equals(isMember)) {
            // 如果已经在里面，说明是取消想看 -> 从 Set 中移除
            redisTemplate.opsForSet().remove(wantKey, userId.toString());
            return false; // 返回当前状态：未想看
        } else {
            // 如果不在里面，说明是点击想看 -> 加入 Set
            redisTemplate.opsForSet().add(wantKey, userId.toString());
            return true; // 返回当前状态：已想看
        }
    }

    /**
     * 2. 获取详情时：注入实时 Redis 数据并自增浏览量
     */
    @Override
    public Event getEventDetailWithRealTimeStats(Long eventId, Long currentUserId) {
        // 先从数据库或本地缓存查出基础 Event 信息
        Event event = this.getById(eventId);
        if (event == null) return null;

        String viewsKey = EVENT_VIEWS_KEY + eventId;
        String wantKey = EVENT_WANT_KEY + eventId;

        // 🚨 核心1：每次被访问，Redis 浏览量 +1
        Long currentViews = redisTemplate.opsForValue().increment(viewsKey);
        event.setPageViews(event.getPageViews() + currentViews.intValue()); // DB 基础值 + Redis 新增值

        // 🚨 核心2：获取实时想看总人数
        Long wantCount = redisTemplate.opsForSet().size(wantKey);
        // 如果 Redis 里没数据，说明还没人点过或者 Redis 刚清空，兜底用 DB 里的数据；否则用 Redis 的
        event.setWantCount(wantCount != null && wantCount > 0 ? wantCount.intValue() : event.getWantCount());

        // 🚨 核心3：判断当前用户是否点过想看
        if (currentUserId != null) {
            Boolean hasWanted = redisTemplate.opsForSet().isMember(wantKey, currentUserId.toString());
            event.setHasWanted(hasWanted != null ? hasWanted : false);
        } else {
            event.setHasWanted(false);
        }

        return event;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveEventWithTicketsAndArtists(EventAddDTO dto) {
        User currentUser = getCurrentUser();
        boolean isSuperAdmin = currentUser.getId() == 1L;

        List<EventSessionDTO> sessions = normalizeSessions(dto);
        validateEventSessions(dto, sessions);

        Event event = new Event();
        BeanUtils.copyProperties(dto, event);
        event.setCreateBy(currentUser.getId());

        // 用第一个场次回填 tb_event.show_time / sale_time，兼容列表排序和旧代码
        fillEventDefaultTime(event, sessions);

        if (isSuperAdmin) {
            event.setAuditStatus(Event.AUDIT_APPROVED);
            if (event.getStatus() == null) {
                event.setStatus(1);
            }
        } else {
            // 普通提交人：先记录他选择的完整提交内容
            try {
                event.setPendingPayload(objectMapper.writeValueAsString(dto));
            } catch (Exception e) {
                throw new BusinessException("保存新增审核快照失败");
            }

            // 待审核期间不能直接按提交状态展示，否则 C 端可能提前可见
            event.setAuditStatus(Event.AUDIT_PENDING);
            event.setStatus(4);
        }

        event.setAuditSubmitTime(LocalDateTime.now());

        this.save(event);

        // 新模型：保存 sessions 和每个 session 下的票档
        syncEventSessionsAndTickets(event.getId(), sessions);

        // 保存艺人关联
        if (dto.getArtistIds() != null && !dto.getArtistIds().isEmpty()) {
            List<EventArtist> relations = dto.getArtistIds().stream().map(artistId -> {
                EventArtist ea = new EventArtist();
                ea.setEventId(event.getId());
                ea.setArtistId(artistId);
                return ea;
            }).collect(Collectors.toList());
            eventArtistService.saveBatch(relations);
        }
    }

    private User getCurrentUser() {
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        return userService.getOne(new LambdaQueryWrapper<User>().eq(User::getId, Long.valueOf(userId)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEventWithTicketsAndArtists(Long id, EventAddDTO dto) {
        Event oldEvent = getById(id);
        if (oldEvent == null) {
            throw new BusinessException("修改的演出不存在！");
        }

        User currentUser = getCurrentUser();
        boolean isSuperAdmin = currentUser.getId() == 1L;

        /*
         * 多场次模型校验：
         * 1. 如果 dto.sessions 有值，用 sessions 校验
         * 2. 如果 dto.sessions 为空，则兼容旧字段 showTime / saleTime / tickets，生成一个默认场次
         */
        List<EventSessionDTO> sessions = normalizeSessions(dto);
        validateEventSessions(dto, sessions);

        /*
         * 关键：把规范化后的 sessions 回写进 dto。
         * 这样普通管理员提交修改审核时，pendingPayload 里也会带上 sessions。
         */
        dto.setSessions(sessions);

        // 1. 超管：免审，直接覆盖主表、场次、票档、艺人关系
        if (isSuperAdmin) {
            Integer finalStatus = dto.getStatus() != null ? dto.getStatus() : oldEvent.getStatus();
            applyEventMainData(id, dto, Event.AUDIT_APPROVED, finalStatus);
            return;
        }

        // 2. 普通管理员：新增待审核中，不允许直接改
        if (Objects.equals(oldEvent.getAuditStatus(), Event.AUDIT_PENDING)) {
            throw new BusinessException("该演出正在审核中，如需修改，请先撤销审核申请");
        }

        // 3. 普通管理员：修改待审核中，不允许再次改
        if (Objects.equals(oldEvent.getEditAuditStatus(), Event.EDIT_AUDIT_PENDING)) {
            throw new BusinessException("该演出的修改正在审核中，如需再次修改，请先撤销审核申请");
        }

        // 4. 普通管理员编辑已审核通过演出：只保存修改快照，不影响客户端旧数据
        if (Objects.equals(oldEvent.getAuditStatus(), Event.AUDIT_APPROVED)) {
            try {
                oldEvent.setPendingPayload(objectMapper.writeValueAsString(dto));
                oldEvent.setEditAuditStatus(Event.EDIT_AUDIT_PENDING);
                oldEvent.setAuditSubmitTime(LocalDateTime.now());
                updateById(oldEvent);
                return;
            } catch (Exception e) {
                throw new BusinessException("保存修改审核快照失败");
            }
        }

        // 5. 普通管理员编辑已撤销/已驳回演出：允许重新提交新增审核
        if (Objects.equals(oldEvent.getAuditStatus(), Event.AUDIT_REJECTED)
                || Objects.equals(oldEvent.getAuditStatus(), Event.AUDIT_REVOKED)) {
            applyEventMainData(id, dto, Event.AUDIT_PENDING, Event.STATUS_OFFLINE);
            return;
        }

        throw new BusinessException("当前演出状态不允许修改");
    }

    /**
     * 1. 核心改造：获取管理后台的演出列表 (带数据隔离)
     */
    @Override
    public IPage<Event> listAdminEvents(int current, int size, String keyword) {
        User currentUser = getCurrentUser();
        boolean isSuperAdmin = (currentUser.getId() == 1L);

        List<String> permissions = userMapper.selectPermissionsByUserId(currentUser.getId());
        boolean canViewAllEvents =
                isSuperAdmin ||
                        permissions.contains("audit:manage") ||
                        permissions.contains("event:view_all");

        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        if (!canViewAllEvents) {
            wrapper.eq(Event::getCreateBy, currentUser.getId());
        }
        wrapper.orderByDesc(Event::getCreateTime);

        if (StringUtils.hasText(keyword)) {
            boolean isNumeric = keyword.matches("\\d+");

            wrapper.and(w -> {
                    w.like(Event::getTitle, keyword)
                    .or()
                    .like(Event::getVenue, keyword)
                    // 👇 跨表子查询魔法：根据艺人名字反查出所有的演出 ID
                    // ⚠️ 请确保以下 SQL 中的表名 (tb_event_artist 和 tb_artist) 与你数据库里的真实表名完全一致！
                    .or()
                    .inSql(Event::getId,
                            "SELECT event_id FROM tb_event_artist WHERE artist_id IN " +
                                    "(SELECT id FROM tb_artist WHERE name LIKE '%" + keyword + "%')"

                    );
                    if (isNumeric) {
                        w.or().eq(Event::getId, Long.valueOf(keyword));
                    }
            });


        }

        // 1. 查基础演出
        IPage<Event> pageData = this.page(new Page<>(current, size), wrapper);
        List<Event> records = pageData.getRecords();
        if (records == null || records.isEmpty()) {
            return pageData;
        }

        List<Long> eventIds = records.stream().map(Event::getId).collect(Collectors.toList());

        // ======================= 补丁 1：装配场次 + 票档 =======================
        List<EventSession> allSessions = eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>()
                        .in(EventSession::getEventId, eventIds)
                        .orderByAsc(EventSession::getSortOrder)
                        .orderByAsc(EventSession::getShowTime)
        );

        List<TicketCategory> allTickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>().in(TicketCategory::getEventId, eventIds)
        );

        Map<Long, List<TicketCategory>> ticketMapByEventId = allTickets.stream()
                .collect(Collectors.groupingBy(TicketCategory::getEventId));

        Map<Long, List<TicketCategory>> ticketMapBySessionId = allTickets.stream()
                .filter(t -> t.getSessionId() != null)
                .collect(Collectors.groupingBy(TicketCategory::getSessionId));

        for (EventSession session : allSessions) {
            session.setTickets(ticketMapBySessionId.getOrDefault(session.getId(), new ArrayList<>()));
        }

        Map<Long, List<EventSession>> sessionMapByEventId = allSessions.stream()
                .collect(Collectors.groupingBy(EventSession::getEventId));

        // ======================= 补丁 2：装配艺人 =======================
        // 2.1 查关系表 tb_event_artist
        List<EventArtist> eventArtists = eventArtistService.list(
                new LambdaQueryWrapper<EventArtist>().in(EventArtist::getEventId, eventIds)
        );

        // 2.2 取出所有不重复的艺人 ID 并查出艺人详情
        Map<Long, Artist> artistMap = new HashMap<>();
        if (!eventArtists.isEmpty()) {
            List<Long> artistIds = eventArtists.stream().map(EventArtist::getArtistId).distinct().collect(Collectors.toList());
            if (!artistIds.isEmpty()) {
                // 根据 ID 批量查出艺人
                List<Artist> artistsList = artistMapper.selectBatchIds(artistIds);
                artistMap = artistsList.stream().collect(Collectors.toMap(Artist::getId, a -> a));
            }
        }

        // 2.3 将艺人组装成方便前端解析的 Map 结构并按 event_id 分组
        Map<Long, List<Map<String, Object>>> eventArtistMap = new HashMap<>();
        for (EventArtist ea : eventArtists) {
            Map<String, Object> artistInfo = new HashMap<>();
            Artist artist = artistMap.get(ea.getArtistId());

            if (artist == null) {
                // 情况 1：艺人被删了或者数据库找不到
                artistInfo.put("id", ea.getArtistId());
                artistInfo.put("name", "未知艺人 (ID:" + ea.getArtistId() + ")");
                artistInfo.put("notFound", true);
            } else {
                // 情况 2：正常找到艺人
                artistInfo.put("id", artist.getId());
                artistInfo.put("name", artist.getName());
                artistInfo.put("auditStatus", artist.getAuditStatus()); // 假设 0 是待审核
                artistInfo.put("notFound", false);
            }
            eventArtistMap.computeIfAbsent(ea.getEventId(), k -> new ArrayList<>()).add(artistInfo);
        }

        // ======================= 统一赋值 =======================
        records.forEach(event -> {
            event.setSessions(sessionMapByEventId.getOrDefault(event.getId(), new ArrayList<>()));

        // 兼容旧表格的“票务策略”展示：这里给全部票档
            event.setTickets(ticketMapByEventId.getOrDefault(event.getId(), new ArrayList<>()));

            event.setArtists(eventArtistMap.getOrDefault(event.getId(), new ArrayList<>()));
        });

        return pageData;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyEventMainData(Long id, EventAddDTO dto, Integer auditStatus, Integer status) {
        Event oldEvent = getById(id);
        if (oldEvent == null) {
            throw new BusinessException("演出不存在");
        }

        List<EventSessionDTO> sessions = normalizeSessions(dto);
        validateEventSessions(dto, sessions);

        Event newEvent = new Event();
        BeanUtils.copyProperties(dto, newEvent);
        newEvent.setId(id);
        newEvent.setCreateBy(oldEvent.getCreateBy());
        newEvent.setAuditStatus(auditStatus);
        newEvent.setStatus(status);
        newEvent.setEditAuditStatus(null);
        newEvent.setPendingPayload(null);
        newEvent.setAuditSubmitTime(LocalDateTime.now());

        // 用第一个场次回填旧字段
        fillEventDefaultTime(newEvent, sessions);

        updateById(newEvent);

        this.update(
                new LambdaUpdateWrapper<Event>()
                        .eq(Event::getId, id)
                        .set(Event::getEditAuditStatus, null)
                        .set(Event::getPendingPayload, null)
        );

        eventArtistService.remove(
                new LambdaQueryWrapper<EventArtist>().eq(EventArtist::getEventId, id)
        );

        if (dto.getArtistIds() != null && !dto.getArtistIds().isEmpty()) {
            List<EventArtist> relations = dto.getArtistIds().stream().map(artistId -> {
                EventArtist ea = new EventArtist();
                ea.setEventId(id);
                ea.setArtistId(artistId);
                return ea;
            }).collect(Collectors.toList());
            eventArtistService.saveBatch(relations);
        }

        // 新模型：同步场次和每场票档
        syncEventSessionsAndTickets(id, sessions);
    }

    private void syncTicketCategories(Long eventId, Long sessionId, List<TicketCategoryDTO> tickets) {
        List<TicketCategory> oldTickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
                        .eq(TicketCategory::getSessionId, sessionId)
        );

        if (tickets == null || tickets.isEmpty()) {
            oldTickets.forEach(t -> ticketCategoryMapper.deleteById(t.getId()));
            return;
        }

        Set<Long> keepTicketIds = new HashSet<>();

        for (TicketCategoryDTO t : tickets) {
            if (!StringUtils.hasText(t.getName())) {
                continue;
            }

            TicketCategory existingTicket = null;

            if (t.getId() != null) {
                existingTicket = oldTickets.stream()
                        .filter(ot -> Objects.equals(ot.getId(), t.getId()))
                        .findFirst()
                        .orElse(null);
            }

            if (existingTicket == null) {
                existingTicket = oldTickets.stream()
                        .filter(ot -> Objects.equals(ot.getName(), t.getName()))
                        .findFirst()
                        .orElse(null);
            }

            if (existingTicket != null) {
                int oldTotalStock = existingTicket.getTotalStock() != null ? existingTicket.getTotalStock() : 0;
                int oldRemainingStock = existingTicket.getRemainingStock() != null ? existingTicket.getRemainingStock() : 0;
                int newStock = t.getStock() != null ? t.getStock() : 0;
                int stockDiff = newStock - oldTotalStock;

                existingTicket.setName(t.getName());
                existingTicket.setPrice(t.getPrice());
                existingTicket.setTotalStock(newStock);
                existingTicket.setRemainingStock(Math.max(0, oldRemainingStock + stockDiff));
                existingTicket.setEventId(eventId);
                existingTicket.setSessionId(sessionId);

                ticketCategoryMapper.updateById(existingTicket);
                keepTicketIds.add(existingTicket.getId());
            } else {
                TicketCategory category = new TicketCategory();
                category.setEventId(eventId);
                category.setSessionId(sessionId);
                category.setName(t.getName());
                category.setPrice(t.getPrice());
                category.setTotalStock(t.getStock());
                category.setRemainingStock(t.getStock());

                ticketCategoryMapper.insert(category);
                keepTicketIds.add(category.getId());
            }
        }

        for (TicketCategory oldTicket : oldTickets) {
            if (!keepTicketIds.contains(oldTicket.getId())) {
                ticketCategoryMapper.deleteById(oldTicket.getId());
            }
        }
    }

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

            Integer sessionStatus = session.getStatus() != null
                    ? session.getStatus()
                    : dto.getStatus();

            if (sessionStatus != null && sessionStatus == 1 && session.getSaleTime() == null) {
                throw new BusinessException(prefix + "：上架状态必须设置开票时间");
            }

            if (session.getSaleTime() != null
                    && session.getShowTime() != null
                    && session.getSaleTime().isAfter(session.getShowTime().minusHours(24))) {
                throw new BusinessException(prefix + "：开票时间必须早于演出时间至少 24 小时");
            }
        }
    }

    private List<EventSessionDTO> normalizeSessions(EventAddDTO dto) {
        if (dto.getSessions() == null || dto.getSessions().isEmpty()) {
            return new ArrayList<>();
        }

        return dto.getSessions().stream()
                .filter(session -> session != null && session.getShowTime() != null)
                .collect(Collectors.toList());
    }

    private void fillEventDefaultTime(Event event, List<EventSessionDTO> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            event.setShowTime(null);
            event.setSaleTime(null);
            return;
        }

        EventSessionDTO first = sessions.stream()
                .filter(s -> s.getShowTime() != null)
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

    private void syncEventSessionsAndTickets(Long eventId, List<EventSessionDTO> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            ticketCategoryMapper.delete(
                    new LambdaQueryWrapper<TicketCategory>()
                            .eq(TicketCategory::getEventId, eventId)
            );

            eventSessionMapper.delete(
                    new LambdaQueryWrapper<EventSession>()
                            .eq(EventSession::getEventId, eventId)
            );

            return;
        }

        List<EventSession> oldSessions = eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>().eq(EventSession::getEventId, eventId)
        );

        Set<Long> keepSessionIds = new HashSet<>();

        for (int i = 0; i < sessions.size(); i++) {
            EventSessionDTO dto = sessions.get(i);

            EventSession session = null;

            if (dto.getId() != null) {
                session = oldSessions.stream()
                        .filter(s -> Objects.equals(s.getId(), dto.getId()))
                        .findFirst()
                        .orElse(null);
            }

            if (session == null) {
                session = new EventSession();
                session.setEventId(eventId);
                session.setCreateTime(LocalDateTime.now());
            }

            session.setSessionName(StringUtils.hasText(dto.getSessionName()) ? dto.getSessionName() : "默认场次");
            session.setShowTime(dto.getShowTime());
            session.setSaleTime(dto.getSaleTime());
            session.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
            session.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : i);
            session.setUpdateTime(LocalDateTime.now());

            if (session.getId() == null) {
                eventSessionMapper.insert(session);
            } else {
                eventSessionMapper.updateById(session);
            }

            keepSessionIds.add(session.getId());

            syncTicketCategories(eventId, session.getId(), dto.getTickets());
        }

        // 删除已经被前端移除的场次，并删除其票档
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

}