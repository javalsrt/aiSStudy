package com.znxsgl.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalTime;

/**
 * 作息时间表（节次起止时间），支持按 年级/周次奇偶 配置多套。
 * week_parity：0=通用（不分单双周），1=单周（奇数周），2=双周（偶数周）
 */
@Data
@TableName("bell_schedule")
public class BellSchedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学期，空=通用 */
    private String semester;

    /** 年级（如 2026级），空=默认作息 */
    private String grade;

    /** 0=通用 1=单周(奇数周) 2=双周(偶数周) */
    private Integer weekParity;

    /** 节次，从 1 开始 */
    private Integer node;

    private LocalTime startTime;
    private LocalTime endTime;
}
