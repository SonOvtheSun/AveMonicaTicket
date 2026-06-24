package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.mapper.ArtistHeatMapper;
import com.avemonica.ticket.service.ArtistService;
import com.avemonica.ticket.service.EventService; // 🚨 引入演出服务
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/artist")
public class PublicArtistController {



    @Autowired
    private ArtistService artistService;

    @Autowired
    private EventService eventService; // 🚨 注入演出服务层用于数量穿透统计

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ArtistHeatMapper artistHeatMapper;

    /**
     * C端获取已上架音乐人列表 (带演出数量动态统计)
     */
    @GetMapping("/page")
    public Result<IPage<Artist>> pagePublicArtists(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "18") int size,
            @RequestParam(required = false) String style,
            @RequestParam(required = false) String keyword
    ) {
        LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();

        // 铁律：C端只能看到审核通过且正常在库（状态为1）的艺人
        wrapper.eq(Artist::getAuditStatus, 1);

        if (StringUtils.hasText(style) && !"全部".equals(style)) {
            wrapper.like(Artist::getStyle, style);
        }

        if (StringUtils.hasText(keyword)) {
            wrapper.like(Artist::getName, keyword);
        }

        wrapper.orderByDesc(Artist::getId);

        IPage<Artist> pageData = artistService.page(new Page<>(current, size), wrapper);

        // 🚨 核心关联魔法：遍历每一个音乐人，动态计算其“最近演出场数”
        LocalDateTime now = LocalDateTime.now();
        for (Artist artist : pageData.getRecords()) {
            long eventCount = eventService.count(
                    new LambdaQueryWrapper<Event>()
                            // C端只统计未隐藏的演出
                            .ne(Event::getStatus, Event.STATUS_HIDDEN)

                            // 当前艺人关联的演出
                            .inSql(
                                    Event::getId,
                                    "SELECT event_id FROM tb_event_artist WHERE artist_id = " + artist.getId()
                            )

                            // 至少有一个未来场次
                            .apply(
                                    "EXISTS (" +
                                            "SELECT 1 FROM tb_event_session s " +
                                            "WHERE s.event_id = tb_event.id " +
                                            "AND s.status <> 4 " +
                                            "AND s.show_time >= {0}" +
                                            ")",
                                    now
                            )
            );
            // 写入刚才扩展的临时字段中
            artist.setRecentEventCount((int) eventCount);
        }

        return Result.success(pageData);
    }

    @GetMapping("/{id}")
    public Result<Artist> getArtistDetail(@PathVariable Long id) {
        Artist artist = artistService.getById(id);
        if (artist == null || artist.getAuditStatus() != 1) {
            return Result.error("该音乐人不存在或暂未上架");
        }

        Long userId = getCurrentUserIdQuietly();

        Long heatValue = artistHeatMapper.calculateArtistHeat(id);
        Integer recentWeekLikeCount = artistHeatMapper.calculateRecentWeekLikeCount(id);

        boolean isFavorited = false;
        if (userId != null) {
            Integer count = artistHeatMapper.countArtistFavorited(id, userId);
            isFavorited = count != null && count > 0;
        }

        artist.setLikeCount(artist.getLikeCount() == null ? 0 : artist.getLikeCount());
        artist.setHeatValue(heatValue == null ? 0L : heatValue);
        artist.setRecentWeekLikeCount(recentWeekLikeCount == null ? 0 : recentWeekLikeCount);
        artist.setIsFavorited(isFavorited);

        return Result.success(artist);
    }

    private Long getCurrentUserIdQuietly() {
        try {
            String name = SecurityContextHolder.getContext().getAuthentication().getName();
            if (!StringUtils.hasText(name) || "anonymousUser".equals(name)) {
                return null;
            }
            return Long.valueOf(name);
        } catch (Exception e) {
            return null;
        }
    }

}