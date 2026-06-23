package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.config.AuthExp;
import com.avemonica.ticket.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 后台订单管理 Controller。
 *
 * 职责边界：
 * 1. 只接收请求参数；
 * 2. 不写订单聚合查询、退票审核等业务逻辑；
 * 3. 具体业务交给 AdminOrderService。
 */
@RestController
@RequestMapping("/api/admin/order")
public class AdminOrderController {

    @Autowired
    private OrderService orderService;

    /**
     * 订单分页查询。
     *
     * searchType 可选：
     * orderId：订单ID/订单号，完整匹配；
     * userId：用户ID，完整匹配；
     * eventName：演出名称，模糊匹配；
     * eventId：演出ID，完整匹配。
     */
    @GetMapping("/page")
    @PreAuthorize(AuthExp.AUDIT_MANAGE + " or authentication.name == '1'")
    public Result<Map<String, Object>> pageOrders(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String searchType,
            @RequestParam(required = false) String keyword
    ) {
        return Result.success(
                orderService.pageAdminOrders(current, size, status, searchType, keyword)
        );
    }

    /**
     * 订单详情。
     */
    @GetMapping("/detail/{id}")
    @PreAuthorize(AuthExp.AUDIT_MANAGE + " or authentication.name == '1'")
    public Result<Map<String, Object>> getOrderDetail(@PathVariable Long id) {
        return Result.success(orderService.getAdminOrderDetail(id));
    }

    /**
     * 退票审核。
     *
     * body 示例：
     * {
     *   "orderId": 123,
     *   "approve": true,
     *   "rejectReason": "不符合退票规则"
     * }
     */
    @PostMapping("/refund/audit")
    @PreAuthorize(AuthExp.AUDIT_MANAGE + " or authentication.name == '1'")
    public Result<String> auditRefund(@RequestBody Map<String, Object> body) {
        orderService.auditRefund(body);
        return Result.success("操作成功");
    }

    /**
     * 管理员强制退款。
     *
     * body:
     * {
     *   "orderId": 123
     * }
     */
    @PostMapping("/refund/force")
    @PreAuthorize(AuthExp.AUDIT_MANAGE + " or authentication.name == '1'")
    public Result<String> forceRefund(@RequestBody Map<String, Object> body) {
        orderService.forceRefund(body);
        return Result.success("强制退款成功");
    }

}
