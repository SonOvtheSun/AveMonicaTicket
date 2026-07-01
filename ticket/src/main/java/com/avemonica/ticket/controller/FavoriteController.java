package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.FavoriteToggleDTO;
import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.UserBehavior;
import com.avemonica.ticket.service.RecommendBehaviorService;
import com.avemonica.ticket.service.UserFavoriteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

@Slf4j
@RestController
@RequestMapping("/api/favorite")
public class FavoriteController {

    @Autowired
    private UserFavoriteService userFavoriteService;

    @Autowired
    private RecommendBehaviorService recommendBehaviorService;

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

            recordEventFavoriteBehaviorQuietly(userId, dto, isFavorited);

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

    private void recordEventFavoriteBehaviorQuietly(Long userId, FavoriteToggleDTO dto, boolean isFavorited) {
        if (userId == null || dto == null || dto.getTargetId() == null || dto.getType() == null) {
            return;
        }

        // type = 1 才是演出想看；type = 2 是关注音乐人，不能写进 event_id。
        if (!Objects.equals(dto.getType(), 1)) {
            return;
        }

        try {
            recommendBehaviorService.recordBehavior(
                    userId,
                    null,
                    dto.getTargetId(),
                    !isFavorited ? UserBehavior.TYPE_WANT_EVENT : UserBehavior.TYPE_CANCEL_WANT_EVENT
            );
        } catch (Exception e) {
            log.warn("记录演出收藏推荐行为失败，userId={}, eventId={}", userId, dto.getTargetId(), e);
        }
    }
}