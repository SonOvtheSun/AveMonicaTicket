package com.avemonica.ticket.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("tb_event_collection")
public class EventCollection {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private LocalDateTime createTime;
    @TableField(exist = false) private List<Event> events;
}