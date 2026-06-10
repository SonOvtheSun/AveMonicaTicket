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
        if (userId == null) return Result.error(502, "登录已过期");

        // 将 List<Long> 转为逗号隔开的字符串 "1,2,3"
        String specIdsStr = dto.getSpectatorIds().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        // 检查是否已有记录
        EventReservation old = reservationService.getOne(
                new LambdaQueryWrapper<EventReservation>()
                        .eq(EventReservation::getUserId, userId)
                        .eq(EventReservation::getEventId, dto.getEventId())
        );

        if (old == null) {
            EventReservation target = new EventReservation();
            target.setUserId(userId);
            target.setEventId(dto.getEventId());
            target.setTicketId(dto.getTicketId());
            target.setSpectatorIds(specIdsStr);
            target.setCreateTime(LocalDateTime.now());
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
    @GetMapping("/get/{eventId}")
    public Result<Map<String, Object>> getReservation(@PathVariable Long eventId) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.success(null); // 未登录不返回报错

        EventReservation res = reservationService.getOne(
                new LambdaQueryWrapper<EventReservation>()
                        .eq(EventReservation::getUserId, userId)
                        .eq(EventReservation::getEventId, eventId)
        );

        if (res == null) return Result.success(null);

        // 还原为前端需要的结构
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("ticketId", res.getTicketId());

        List<Long> specIds = Arrays.stream(res.getSpectatorIds().split(","))
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