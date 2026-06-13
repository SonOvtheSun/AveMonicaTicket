package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.EventCollection;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.service.EventCollectionService;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.TicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private ArtistMapper artistMapper;


    private Long parseLongValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        String text = value.toString();
        if (!StringUtils.hasText(text)) return null;
        return Long.valueOf(text);
    }

    /**
     * 新版前端传 events: [{ eventId, collectionAlias }]
     * 旧版前端传 eventIds: [1,2,3]
     * 这里同时兼容两种格式。
     */
    private List<Map<String, Object>> resolveCollectionEvents(Map<String, Object> payload) {
        Object eventsObj = payload.get("events");
        if (eventsObj instanceof List<?>) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : (List<?>) eventsObj) {
                if (!(item instanceof Map<?, ?>)) continue;

                Map<?, ?> itemMap = (Map<?, ?>) item;
                Long eventId = parseLongValue(itemMap.get("eventId"));
                if (eventId == null) continue;

                String alias = itemMap.get("collectionAlias") == null
                        ? ""
                        : itemMap.get("collectionAlias").toString().trim();

                result.add(Map.of(
                        "eventId", eventId,
                        "collectionAlias", alias
                ));
            }
            return result;
        }

        Object eventIdsObj = payload.get("eventIds");
        List<Map<String, Object>> result = new ArrayList<>();
        if (eventIdsObj instanceof List<?>) {
            for (Object item : (List<?>) eventIdsObj) {
                Long eventId = parseLongValue(item);
                if (eventId == null) continue;
                result.add(Map.of(
                        "eventId", eventId,
                        "collectionAlias", ""
                ));
            }
        }
        return result;
    }

    private void bindEventsToCollection(Long collectionId, Map<String, Object> payload) {
        List<Map<String, Object>> collectionEvents = resolveCollectionEvents(payload);

        if (collectionEvents == null || collectionEvents.isEmpty()) {
            return;
        }

        for (Map<String, Object> item : collectionEvents) {
            Long eventId = parseLongValue(item.get("eventId"));
            String alias = item.get("collectionAlias") == null
                    ? ""
                    : item.get("collectionAlias").toString().trim();

            eventService.update(new LambdaUpdateWrapper<Event>()
                    .eq(Event::getId, eventId)
                    .set(Event::getCollectionId, collectionId)
                    .set(Event::getCollectionAlias, alias)
            );
        }
    }

    /**
     * 1. 获取所有合集列表 (同时包含每个合集下的演出场次)
     */
    @GetMapping("/list")
    public Result<List<EventCollection>> listCollections(@RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<EventCollection> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(EventCollection::getName, keyword);
        }
        wrapper.orderByDesc(EventCollection::getCreateTime);
        List<EventCollection> collections = collectionService.list(wrapper);

        // 🚨 核心：为每个合集装配其包含的演出列表，供前端直接查看
        for (EventCollection collection : collections) {
            List<Event> relatedEvents = eventService.list(
                    new LambdaQueryWrapper<Event>()
                            .select(Event::getId, Event::getTitle, Event::getCollectionAlias, Event::getCity)
                            .eq(Event::getCollectionId, collection.getId())
                            .ne(Event::getStatus, 4) // 不展示隐藏的演出
            );
            // 假设你在 EventCollection 实体类中扩展了 @TableField(exist = false) private List<Event> events;
            collection.setEvents(relatedEvents);
        }

        return Result.success(collections);
    }

    /**
     * 2. 获取可选演出列表 (供合集弹窗多选下拉框使用)
     * 包含：1. 没有关联任何合集的演出；2. 当前合集已经关联的演出
     */
    @GetMapping("/available-events")
    public Result<List<Event>> getAvailableEvents(@RequestParam(required = false) Long collectionId) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(Event::getId, Event::getTitle, Event::getCollectionAlias, Event::getCity)
                .ne(Event::getStatus, 4); // 过滤隐藏项目

        if (collectionId != null) {
            // 查出无合集的，或者属于当前合集的
            wrapper.and(w -> w.isNull(Event::getCollectionId).or().eq(Event::getCollectionId, collectionId));
        } else {
            // 新增时，只能选择未绑定的演出
            wrapper.isNull(Event::getCollectionId);
        }

        return Result.success(eventService.list(wrapper));
    }


    /**
     * 3. 获取合集内演出详情：给前端弹窗使用，不走公共详情接口，避免增加浏览量。
     */
    @GetMapping("/event-detail/{eventId}")
    @PreAuthorize("hasAnyAuthority('event:publish', 'event:edit', 'event:view', 'audit:manage') or authentication.name == '1'")
    public Result<Event> getCollectionEventDetail(@PathVariable Long eventId) {
        Event event = eventService.getById(eventId);
        if (event == null) {
            return Result.error("演出不存在");
        }

        List<TicketCategory> tickets = ticketService.list(
                new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, eventId)
        );
        event.setTickets(tickets);

        List<Map<String, Object>> artists = artistMapper.selectArtistMapsByEventId(eventId);
        event.setArtists(artists);

        return Result.success(event);
    }

    /**
     * 3. 新建合集并批量绑定演出
     */
    @PostMapping("/add")
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("hasAnyAuthority('event:publish', 'audit:manage') or authentication.name == '1'")
    public Result<String> addCollection(@RequestBody Map<String, Object> payload) {
        String name = (String) payload.get("name");
        if (!StringUtils.hasText(name)) {
            return Result.error("合集名称不能为空");
        }

        // 保存合集主体
        EventCollection collection = new EventCollection();
        collection.setName(name);
        collectionService.save(collection);

        // 批量绑定演出，同时保存每个演出在合集内的别名
        bindEventsToCollection(collection.getId(), payload);

        return Result.success("合集创建并绑定演出成功");
    }

    /**
     * 4. 编辑合集：更新名称，并动态添加/删除演出 (核心战区)
     */
    @PutMapping("/update")
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("hasAnyAuthority('event:publish', 'audit:manage') or authentication.name == '1'")
    public Result<String> updateCollection(@RequestBody Map<String, Object> payload) {
        Long id = Long.valueOf(payload.get("id").toString());
        String name = (String) payload.get("name");
        // 1. 更新名字
        EventCollection collection = collectionService.getById(id);
        if (collection == null) return Result.error("合集不存在");
        collection.setName(name);
        collectionService.updateById(collection);

        // 2. 先把原本属于这个合集的所有演出全部解绑，并清空别名
        eventService.update(new LambdaUpdateWrapper<Event>()
                .eq(Event::getCollectionId, id)
                .set(Event::getCollectionId, null)
                .set(Event::getCollectionAlias, null)
        );

        // 3. 重新绑定最新勾选的演出列表，同时保存每个演出的合集别名
        bindEventsToCollection(id, payload);

        return Result.success("合集信息及演出绑定关系更新成功");
    }

    /**
     * 5. 删除合集 (同时解绑旗下所有演出)
     */
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("hasAnyAuthority('event:publish', 'audit:manage') or authentication.name == '1'")
    public Result<String> deleteCollection(@PathVariable Long id) {
        collectionService.removeById(id);
        // 解绑旗下演出
        eventService.update(new LambdaUpdateWrapper<Event>()
                .eq(Event::getCollectionId, id)
                .set(Event::getCollectionId, null)
                .set(Event::getCollectionAlias, null)
        );
        return Result.success("合集已删除，所属演出已自动释放为单场状态");
    }
}