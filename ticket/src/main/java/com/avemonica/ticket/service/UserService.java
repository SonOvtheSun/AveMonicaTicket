package com.avemonica.ticket.service;
import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.UserRegisterDTO;
import com.avemonica.ticket.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;

public interface UserService extends IService<User> {
    Result<String> register(UserRegisterDTO dto);
}
