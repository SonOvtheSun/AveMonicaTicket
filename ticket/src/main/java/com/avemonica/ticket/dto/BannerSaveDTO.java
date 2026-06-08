package com.avemonica.ticket.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BannerSaveDTO {
    private Long id;

    @NotBlank(message = "横幅图片不能为空")
    private String posterUrl;

    private Long eventId;

    @NotNull(message = "开始展示时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startTime;

    @NotNull(message = "结束展示时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime endTime;

    // 前端传入标识：当前编辑的数据是否属于“已过期(归档表)”的数据
    private Boolean isExpiredEdit;
}