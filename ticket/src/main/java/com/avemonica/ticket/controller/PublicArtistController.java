package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.service.ArtistService;
import com.avemonica.ticket.service.EventService; // 🚨 引入演出服务
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
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
            wrapper.eq(Artist::getStyle, style);
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
//                            .eq(Event::getStatus, 1) // 1: 必须是已上架/在售的活跃演出
//                            .ge(Event::getShowTime, now) // 必须是未开始的未来演出
                            // 跨表联查桥接表：筛选出包含当前艺人ID的演出
                            .inSql(Event::getId, "SELECT event_id FROM tb_event_artist WHERE artist_id = " + artist.getId())
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
        return Result.success(artist);
    }

}