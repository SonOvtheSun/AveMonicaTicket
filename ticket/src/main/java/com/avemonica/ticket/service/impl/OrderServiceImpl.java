package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.dto.EventAddDTO;
import com.avemonica.ticket.dto.TicketCategoryDTO;
import com.avemonica.ticket.entity.*;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.*;
import com.avemonica.ticket.service.EventArtistService;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.OrderService;
import com.avemonica.ticket.service.UserService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.core.context.SecurityContextHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.util.StringUtils;

@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

}
