package com.znxsgl.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 年级单双周作息映射：奇数周套用哪套作息（1=单周表 2=双周表）。
 * 例：2026级 oddWeekParity=1（奇数周用单周作息）；2025级 oddWeekParity=2（奇数周用双周作息）。
 */
@Data
@TableName("grade_week_parity")
public class GradeWeekParity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 年级，如 2026级 */
    private String grade;

    /** 奇数周套用的作息：1=单周表 2=双周表 */
    private Integer oddWeekParity;

    private String remark;
}
