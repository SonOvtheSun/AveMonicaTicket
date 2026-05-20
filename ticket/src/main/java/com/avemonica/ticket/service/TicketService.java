package com.avemonica.ticket.service;

import com.avemonica.ticket.entity.TicketCategory;
import com.baomidou.mybatisplus.extension.service.IService;

public interface TicketService extends IService<TicketCategory> {
    // 预留接口，以后如果要写“高并发扣减库存”的复杂业务，就定义在这里
}