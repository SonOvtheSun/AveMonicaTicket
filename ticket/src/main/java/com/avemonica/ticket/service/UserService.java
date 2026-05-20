package com.avemonica.ticket.service;
import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.UserLoginDTO;
import com.avemonica.ticket.dto.UserRegisterDTO;
import com.avemonica.ticket.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;

public interface UserService extends IService<User> {
    void register(UserRegisterDTO dto);
    String login(UserLoginDTO dto);

    boolean isUsernameAvailable(String username);

    String generateUniqueUsername();


    boolean checkUserExistsByPhone(String phone);
}
