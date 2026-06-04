package com.avemonica.ticket.mapper;

import com.avemonica.ticket.entity.Address;
import com.avemonica.ticket.entity.Artist;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AddressMapper extends BaseMapper<Address> {
}
