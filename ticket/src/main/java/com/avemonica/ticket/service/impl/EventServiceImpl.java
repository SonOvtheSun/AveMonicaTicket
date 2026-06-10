package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.dto.EventAddDTO;
import com.avemonica.ticket.dto.TicketCategoryDTO;
import com.avemonica.ticket.entity.*;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.mapper.EventMapper;
import com.avemonica.ticket.mapper.TicketCategoryMapper;
import com.avemonica.ticket.mapper.UserMapper;
import com.avemonica.ticket.service.EventArtistService;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.UserService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
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
    private ObjectMapper objectMapper;

    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;
    @Autowired
    private EventArtistService eventArtistService;

    @Autowired
    private ArtistMapper artistMapper; // 注入艺人的 Mapper

    @Override
    @Transactional(rollbackFor = Exception.class) // 开启强事务
    public void saveEventWithTicketsAndArtists(EventAddDTO dto) {
        User currentUser = getCurrentUser();
        boolean isSuperAdmin = (currentUser.getId() == 1L);

        Event event = new Event();
        BeanUtils.copyProperties(dto, event);
        event.setCreateBy(currentUser.getId()); // 绑定发布人

        // 🛡️ 审核流与状态流转判定
        if (isSuperAdmin) {
            // 超管：直接通过，状态由前端传过来的决定 (默认预售)
            event.setAuditStatus(Event.AUDIT_APPROVED);
            if (event.getStatus() == null) event.setStatus(1);
        } else {
            // 普通管理员：强制待审核，且强制设定为下架状态 (不可见)
            event.setAuditStatus(Event.AUDIT_PENDING);
            event.setStatus(Event.STATUS_OFFLINE);
        }
        event.setAuditSubmitTime(LocalDateTime.now());

        if (event.getStatus() != null && event.getStatus() == 1 && event.getSaleTime() == null) {
            throw new BusinessException("发布失败：演出设置为上架状态时，必须明确设定开票时间！");
        }

        if (event.getSaleTime() != null && event.getShowTime() != null) {
            // 如果开票时间 晚于 (演出时间 - 24小时)
            if (event.getSaleTime().isAfter(event.getShowTime().minusHours(24))) {
                throw new BusinessException("风控拦截：开票时间必须早于演出时间至少 24 小时，请重新设置！");
            }
        }

        this.save(event);

        // 2. 批量保存票档 tb_ticket_category
        if (dto.getTickets() != null) {
            dto.getTickets().forEach(t -> {
                TicketCategory category = new TicketCategory();
                category.setEventId(event.getId());
                category.setName(t.getName());
                category.setPrice(t.getPrice());
                category.setTotalStock(t.getStock());
                category.setRemainingStock(t.getStock()); // 初始剩余库存等于总库存
                ticketCategoryMapper.insert(category);
            });
        }

        // 3. 批量保存艺人关联 tb_event_artist
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

        validateEventDto(dto);

        // 1. 超管：免审，直接覆盖主表、票档、艺人关系
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
        boolean hasAuditPerm = permissions.contains("audit:manage");

        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        if (!isSuperAdmin && !hasAuditPerm) {
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

        // ======================= 补丁 1：装配票档 =======================
        List<TicketCategory> allTickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>().in(TicketCategory::getEventId, eventIds)
        );
        Map<Long, List<TicketCategory>> ticketMap = allTickets.stream()
                .collect(Collectors.groupingBy(TicketCategory::getEventId));

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
            event.setTickets(ticketMap.getOrDefault(event.getId(), new ArrayList<>()));
            event.setArtists(eventArtistMap.getOrDefault(event.getId(), new ArrayList<>())); // 赋入艺人数据
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

        validateEventDto(dto);

        Event newEvent = new Event();
        BeanUtils.copyProperties(dto, newEvent);
        newEvent.setId(id);
        newEvent.setCreateBy(oldEvent.getCreateBy());
        newEvent.setAuditStatus(auditStatus);
        newEvent.setStatus(status);
        newEvent.setEditAuditStatus(null);
        newEvent.setPendingPayload(null);
        newEvent.setAuditSubmitTime(LocalDateTime.now());

        updateById(newEvent);

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

        syncTicketCategories(id, dto.getTickets());
    }

    private void syncTicketCategories(Long eventId, List<TicketCategoryDTO> tickets) {
        List<TicketCategory> oldTickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, eventId)
        );

        if (tickets == null || tickets.isEmpty()) {
            oldTickets.forEach(t -> ticketCategoryMapper.deleteById(t.getId()));
            return;
        }

        List<String> newTicketNames = tickets.stream()
                .map(TicketCategoryDTO::getName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        tickets.forEach(t -> {
            TicketCategory existingTicket = oldTickets.stream()
                    .filter(ot -> Objects.equals(ot.getName(), t.getName()))
                    .findFirst()
                    .orElse(null);

            if (existingTicket != null) {
                int stockDiff = t.getStock() - existingTicket.getTotalStock();
                existingTicket.setPrice(t.getPrice());
                existingTicket.setTotalStock(t.getStock());
                existingTicket.setRemainingStock(Math.max(0, existingTicket.getRemainingStock() + stockDiff));
                ticketCategoryMapper.updateById(existingTicket);
            } else {
                TicketCategory category = new TicketCategory();
                category.setEventId(eventId);
                category.setName(t.getName());
                category.setPrice(t.getPrice());
                category.setTotalStock(t.getStock());
                category.setRemainingStock(t.getStock());
                ticketCategoryMapper.insert(category);
            }
        });

        oldTickets.forEach(ot -> {
            if (!newTicketNames.contains(ot.getName())) {
                ticketCategoryMapper.deleteById(ot.getId());
            }
        });
    }

    private void validateEventDto(EventAddDTO dto) {
        if (dto.getStatus() != null && dto.getStatus() == 1 && dto.getSaleTime() == null) {
            throw new BusinessException("演出设置为上架状态时，必须明确设定开票时间！");
        }

        if (dto.getSaleTime() != null && dto.getShowTime() != null) {
            if (dto.getSaleTime().isAfter(dto.getShowTime().minusHours(24))) {
                throw new BusinessException("开票时间必须早于演出时间至少 24 小时，请重新设置！");
            }
        }
    }

}