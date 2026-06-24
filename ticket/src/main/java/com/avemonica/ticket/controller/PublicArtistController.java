package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.entity.UserFavorite;
import com.avemonica.ticket.service.ArtistService;
import com.avemonica.ticket.service.UserFavoriteService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/artist")
public class PublicArtistController {

    @Autowired
    private ArtistService artistService;

    @Autowired
    private UserFavoriteService userFavoriteService;

    /**
     * C端获取已上架音乐人列表。
     *
     * 工业级热度方案：
     * 1. 不在前台接口实时计算热度；
     * 2. heatValue / recentWeekLikeCount / recentEventCount 由后台任务预计算写入 tb_artist；
     * 3. 前台这里只做轻量查询和索引排序。
     */
    @GetMapping("/page")
    public Result<IPage<Artist>> pagePublicArtists(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "18") int size,
            @RequestParam(required = false) String style,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String firstLetter
    ) {
        int safeCurrent = current < 1 ? 1 : current;
        int safeSize = size < 1 ? 18 : size;

        LambdaQueryWrapper<Artist> wrapper = new LambdaQueryWrapper<>();

        wrapper.eq(Artist::getAuditStatus, 1);

        if (StringUtils.hasText(style) && !"全部".equals(style)) {
            wrapper.like(Artist::getStyle, style);
        }

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                    .like(Artist::getName, keyword)
                    .or()
                    .like(Artist::getRegion, keyword)
            );
        }

        if (StringUtils.hasText(firstLetter) && !"全部".equals(firstLetter)) {
            wrapper.eq(Artist::getFirstLetter, firstLetter);
        }

        wrapper.orderByDesc(Artist::getHeatValue)
                .orderByDesc(Artist::getLikeCount)
                .orderByDesc(Artist::getId);

        IPage<Artist> pageData = artistService.page(new Page<>(safeCurrent, safeSize), wrapper);
        return Result.success(pageData);
    }

    /**
     * C端音乐人详情。
     *
     * 详情页也不实时计算热度，只读取 tb_artist 中已经预计算好的字段。
     */
    @GetMapping("/{id}")
    public Result<Artist> getArtistDetail(@PathVariable Long id) {
        Artist artist = artistService.getById(id);
        if (artist == null || artist.getAuditStatus() == null || artist.getAuditStatus() != 1) {
            return Result.error("该音乐人不存在或暂未上架");
        }

        Long userId = getCurrentUserIdQuietly();

        artist.setLikeCount(artist.getLikeCount() == null ? 0 : artist.getLikeCount());
        artist.setHeatValue(artist.getHeatValue() == null ? 0L : artist.getHeatValue());
        artist.setRecentWeekLikeCount(
                artist.getRecentWeekLikeCount() == null ? 0 : artist.getRecentWeekLikeCount()
        );
        artist.setRecentEventCount(
                artist.getRecentEventCount() == null ? 0 : artist.getRecentEventCount()
        );
        artist.setIsFavorited(isArtistFavorited(id, userId));

        return Result.success(artist);
    }

    private Boolean isArtistFavorited(Long artistId, Long userId) {
        if (artistId == null || userId == null) {
            return false;
        }

        long count = userFavoriteService.count(
                new LambdaQueryWrapper<UserFavorite>()
                        .eq(UserFavorite::getUserId, userId)
                        .eq(UserFavorite::getTargetId, artistId)
                        .eq(UserFavorite::getType, 2)
        );

        return count > 0;
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