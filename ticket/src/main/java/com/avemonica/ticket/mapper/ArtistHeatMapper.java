package com.avemonica.ticket.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArtistHeatMapper {

    Long calculateArtistHeat(@Param("artistId") Long artistId);

    Integer calculateRecentWeekLikeCount(@Param("artistId") Long artistId);

    Integer countArtistFavorited(@Param("artistId") Long artistId,
                                 @Param("userId") Long userId);
}