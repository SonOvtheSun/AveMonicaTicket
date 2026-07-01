package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.EventCommentAddDTO;
import com.avemonica.ticket.dto.EventCommentVoteDTO;
import com.avemonica.ticket.entity.UserBehavior;
import com.avemonica.ticket.service.EventCommentService;
import com.avemonica.ticket.service.RecommendBehaviorService;
import com.avemonica.ticket.vo.EventCommentVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/event/comment")
public class PublicEventCommentController {

    @Autowired
    private EventCommentService eventCommentService;

    @Autowired
    private RecommendBehaviorService recommendBehaviorService;

    /**
     * 评论分页。
     *
     * sort:
     * time_desc 时间倒序
     * time_asc 时间正序
     * hot_desc 热度倒序
     * hot_asc 热度正序
     */
    @GetMapping("/page")
    public Result<Page<EventCommentVO>> pageComments(@RequestParam Long eventId,
                                                     @RequestParam(defaultValue = "1") Integer current,
                                                     @RequestParam(defaultValue = "10") Integer size,
                                                     @RequestParam(defaultValue = "time_desc") String sort) {
        Long currentUserId = getCurrentUserIdQuietly();
        Page<EventCommentVO> page = eventCommentService.pageComments(eventId, currentUserId, current, size, sort);
        return Result.success(page);
    }

    /**
     * 发布评论。
     */
    @PostMapping("/add")
    public Result<String> addComment(@RequestBody EventCommentAddDTO dto) {
        Long userId = getCurrentUserId();
        eventCommentService.addComment(dto, userId);

        recordCommentBehaviorQuietly(userId, dto);

        return Result.success("评论发布成功");
    }

    /**
     * 点赞 / 拉踩 / 取消。
     */
    @PostMapping("/vote")
    public Result<String> vote(@RequestBody EventCommentVoteDTO dto) {
        Long userId = getCurrentUserId();
        eventCommentService.vote(dto, userId);
        return Result.success("操作成功");
    }

    private Long getCurrentUserId() {
        Long userId = getCurrentUserIdQuietly();
        if (userId == null) {
            throw new RuntimeException("请先登录");
        }
        return userId;
    }

    private Long getCurrentUserIdQuietly() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getPrincipal() == null || "anonymousUser".equals(auth.getPrincipal())) {
                return null;
            }

            if (auth.getPrincipal() instanceof User) {
                return Long.valueOf(((User) auth.getPrincipal()).getUsername());
            }

            return Long.valueOf(auth.getName());
        } catch (Exception e) {
            return null;
        }
    }

    private void recordCommentBehaviorQuietly(Long userId, EventCommentAddDTO dto) {
        if (userId == null || dto == null || dto.getEventId() == null) {
            return;
        }

        try {
            recommendBehaviorService.recordBehavior(
                    userId,
                    null,
                    dto.getEventId(),
                    UserBehavior.TYPE_COMMENT
            );
        } catch (Exception e) {
            log.warn("记录评论推荐行为失败，userId={}, eventId={}", userId, dto.getEventId(), e);
        }
    }
}