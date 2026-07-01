package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.service.RecommendService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/recommend")
public class RecommendController {

    @Autowired
    private RecommendService recommendService;

    /**
     * 首页为您推荐。
     *
     * scene 目前先只支持 home，保留参数是为了以后扩展 detail/search。
     */
    @GetMapping("/events")
    public Result<List<Event>> recommendEvents(@RequestParam(defaultValue = "home") String scene,
                                               @RequestParam(defaultValue = "10") Integer size,
                                               @RequestParam(required = false, defaultValue = "全国") String city) {
        if (!"home".equals(scene)) {
            return Result.error("暂不支持该推荐场景");
        }

        Long userId = getCurrentUserIdQuietly();
        List<Event> events = recommendService.recommendHomeEvents(userId, city, size);
        return Result.success(events);
    }

    private Long getCurrentUserIdQuietly() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getPrincipal() == null) {
                return null;
            }

            if ("anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
                return null;
            }

            if (auth.getPrincipal() instanceof User) {
                String username = ((User) auth.getPrincipal()).getUsername();
                return StringUtils.hasText(username) ? Long.valueOf(username) : null;
            }

            String name = auth.getName();
            return StringUtils.hasText(name) && !"anonymousUser".equals(name) ? Long.valueOf(name) : null;
        } catch (Exception e) {
            return null;
        }
    }
}