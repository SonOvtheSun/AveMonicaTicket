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
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            if (event.getStatus() == null) event.setStatus(Event.STATUS_PRESALE);
        } else {
            // 普通管理员：强制待审核，且强制设定为下架状态 (不可见)
            event.setAuditStatus(Event.AUDIT_PENDING);
            event.setStatus(Event.STATUS_OFFLINE);
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
        Event event = getById(id);
        if(event == null){
            throw new BusinessException("修改的演出不存在！");
        }

        User currentUser = getCurrentUser();
        boolean isSuperAdmin = (currentUser.getId() == 1L);

        Event newEvent = new Event();
        BeanUtils.copyProperties(dto, newEvent);
        newEvent.setId(id);
        event.setCreateBy(event.getCreateBy());

        if(!isSuperAdmin){
            event.setAuditStatus(Event.AUDIT_PENDING);
            event.setStatus(Event.STATUS_HIDDEN);
        } else{
            event.setAuditStatus(event.getAuditStatus());
        }

        this.updateById(newEvent);

        eventArtistService.remove(new LambdaQueryWrapper<EventArtist>().eq(EventArtist::getEventId, id));
        if (dto.getArtistIds() != null && !dto.getArtistIds().isEmpty()) {
            List<EventArtist> relations = dto.getArtistIds().stream().map(artistId -> {
                EventArtist ea = new EventArtist();
                ea.setEventId(id);
                ea.setArtistId(artistId);
                return ea;
            }).collect(Collectors.toList());
            eventArtistService.saveBatch(relations);
        }

        if (dto.getTickets() != null) {
            // 获取数据库里现存的老票档
            List<TicketCategory> oldTickets = ticketCategoryMapper.selectList(
                    new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, id)
            );

            // 记录前端传来的新票档名称，用于后续判断哪些票档被删除了
            List<String> newTicketNames = dto.getTickets().stream()
                    // 假设你的 DTO 内部票档类里有 getName() 方法
                    .map(TicketCategoryDTO::getName)
                    .toList();

            // 遍历前端传来的表单票档
            dto.getTickets().forEach(t -> {
                // 在老票档中寻找有没有名字一样的 (例如都是 "VIP票")
                TicketCategory existingTicket = oldTickets.stream()
                        .filter(ot -> ot.getName().equals(t.getName()))
                        .findFirst().orElse(null);

                if (existingTicket != null) {
                    // 【场景 A：修改老票档】
                    // 计算总库存扩容或缩减了多少张
                    int stockDiff = t.getStock() - existingTicket.getTotalStock();
                    existingTicket.setPrice(t.getPrice());
                    existingTicket.setTotalStock(t.getStock());
                    // 剩余库存同步加上差值 (如果减库存，差值是负数，自然就减掉了)
                    existingTicket.setRemainingStock(existingTicket.getRemainingStock() + stockDiff);

                    ticketCategoryMapper.updateById(existingTicket);
                } else {
                    // 【场景 B：全新追加的票档】
                    TicketCategory category = new TicketCategory();
                    category.setEventId(id);
                    category.setName(t.getName());
                    category.setPrice(t.getPrice());
                    category.setTotalStock(t.getStock());
                    category.setRemainingStock(t.getStock()); // 新增票档初始剩余等于总数
                    ticketCategoryMapper.insert(category);
                }
            });

            // 【场景 C：处理被前端删掉的票档】
            oldTickets.forEach(ot -> {
                if (!newTicketNames.contains(ot.getName())) {
                    ticketCategoryMapper.deleteById(ot.getId());
                }
            });
        }
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
            wrapper.and(w -> w.like(Event::getTitle, keyword)
                    .or()
                    .like(Event::getVenue, keyword)
                    // 👇 跨表子查询魔法：根据艺人名字反查出所有的演出 ID
                    // ⚠️ 请确保以下 SQL 中的表名 (tb_event_artist 和 tb_artist) 与你数据库里的真实表名完全一致！
                    .or()
                    .inSql(Event::getId,
                            "SELECT event_id FROM tb_event_artist WHERE artist_id IN " +
                                    "(SELECT id FROM tb_artist WHERE name LIKE '%" + keyword + "%')"
                    ));
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


}