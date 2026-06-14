package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.ReservationSaveDTO;
import com.avemonica.ticket.entity.EventReservation;
import com.avemonica.ticket.service.ReservationService; // 需自行继承 ServiceImpl
import com.avemonica.ticket.service.ReservationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reservation")
public class ReservationController {

    @Autowired
    private ReservationService reservationService;

    /**
     * 保存或修改云端预填信息
     */
    @PostMapping("/save")
    public Result<String> saveReservation(@RequestBody ReservationSaveDTO dto) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(502, "登录已过期");
        }

        if (dto.getEventId() == null) {
            return Result.error("演出ID不能为空");
        }

        if (dto.getSessionId() == null) {
            return Result.error("场次ID不能为空");
        }

        if (dto.getTicketId() == null) {
            return Result.error("票档ID不能为空");
        }

        if (dto.getSpectatorIds() == null || dto.getSpectatorIds().isEmpty()) {
            return Result.error("请至少选择一位观演人");
        }

        String specIdsStr = dto.getSpectatorIds().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        EventReservation old = reservationService.getOne(
                new LambdaQueryWrapper<EventReservation>()
                        .eq(EventReservation::getUserId, userId)
                        .eq(EventReservation::getEventId, dto.getEventId())
                        .eq(EventReservation::getSessionId, dto.getSessionId())
        );

        if (old == null) {
            EventReservation target = new EventReservation();
            target.setUserId(userId);
            target.setEventId(dto.getEventId());
            target.setSessionId(dto.getSessionId());
            target.setTicketId(dto.getTicketId());
            target.setSpectatorIds(specIdsStr);
            target.setCreateTime(LocalDateTime.now());
            target.setUpdateTime(LocalDateTime.now());
            reservationService.save(target);
        } else {
            old.setTicketId(dto.getTicketId());
            old.setSpectatorIds(specIdsStr);
            old.setUpdateTime(LocalDateTime.now());
            reservationService.updateById(old);
        }

        return Result.success("云端预填预约信息同步成功");
    }

    /**
     * 获取当前用户本场演出的云端预填
     */
    @GetMapping("/get")
    public Result<Map<String, Object>> getReservation(@RequestParam Long eventId,
                                                      @RequestParam Long sessionId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.success(null);
        }

        EventReservation res = reservationService.getOne(
                new LambdaQueryWrapper<EventReservation>()
                        .eq(EventReservation::getUserId, userId)
                        .eq(EventReservation::getEventId, eventId)
                        .eq(EventReservation::getSessionId, sessionId)
        );

        if (res == null) {
            return Result.success(null);
        }

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("eventId", res.getEventId());
        resultMap.put("sessionId", res.getSessionId());
        resultMap.put("ticketId", res.getTicketId());

        List<Long> specIds = Arrays.stream(res.getSpectatorIds().split(","))
                .filter(StringUtils::hasText)
                .map(Long::valueOf)
                .collect(Collectors.toList());

        resultMap.put("spectatorIds", specIds);

        return Result.success(resultMap);
    }

    private Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return Long.valueOf(((User) auth.getPrincipal()).getUsername());
        } catch (Exception e) {
            return null;
        }
    }
}