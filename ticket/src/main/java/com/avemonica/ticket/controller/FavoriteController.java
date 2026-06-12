package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.FavoriteToggleDTO;
import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.service.UserFavoriteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/favorite")
public class FavoriteController {

    @Autowired
    private UserFavoriteService userFavoriteService;

    /**
     * 获取当前登录用户的 ID
     */
    private Long getCurrentUserId() {
        String userIdStr = SecurityContextHolder.getContext().getAuthentication().getName();
        return Long.valueOf(userIdStr);
    }

    /**
     * 1. 切换 收藏/取消收藏 (支持演出和艺人)
     */
    @PostMapping("/toggle")
    public Result<Boolean> toggleFavorite(@RequestBody @Validated FavoriteToggleDTO dto) {
        try {
            Long userId = getCurrentUserId();
            boolean isFavorited = userFavoriteService.toggleFavorite(userId, dto.getTargetId(), dto.getType());
            return Result.success("操作成功", isFavorited);
        } catch (Exception e) {
            return Result.error(401, "请先登录");
        }
    }

    /**
     * 2. 获取我收藏的演出列表
     */
    @GetMapping("/events")
    public Result<List<Event>> getMyFavoriteEvents() {
        Long userId = getCurrentUserId();
        List<Event> events = userFavoriteService.getUserFavoriteEvents(userId);
        return Result.success(events);
    }

    /**
     * 3. 获取我关注的音乐人列表
     */
    @GetMapping("/artists")
    public Result<List<Artist>> getMyFavoriteArtists() {
        Long userId = getCurrentUserId();
        List<Artist> artists = userFavoriteService.getUserFavoriteArtists(userId);
        return Result.success(artists);
    }
}