package com.avemonica.ticket.mapper;

import com.avemonica.ticket.entity.Artist;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ArtistMapper extends BaseMapper<Artist> {
    @Select("SELECT a.id, a.name, a.audit_status AS auditStatus, a.avatar_url AS avatarUrl " +
            "FROM tb_artist a " +
            "JOIN tb_event_artist ea ON a.id = ea.artist_id " +
            "WHERE ea.event_id = #{eventId}")
    List<Map<String, Object>> selectArtistMapsByEventId(@Param("eventId") Long eventId);
}