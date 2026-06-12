package com.avemonica.ticket.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FavoriteToggleDTO {

    @NotNull(message = "目标ID不能为空")
    private Long targetId;

    @NotNull(message = "收藏类型不能为空")
    private Integer type; // 1: 演出(想看), 2: 艺人(关注)
}