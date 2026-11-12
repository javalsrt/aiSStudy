package com.znxsgl.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 课程信息表，存储课程基础资料
 */
@Data
@TableName("course")
public class Course {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String courseName;

    /** 课程编号 */
    private String courseNo;

    /** 任课教师ID */
    private Long teacherId;

    /** 所属院系ID */
    private Long deptId;
    private String semester;

    /** 课程类型 */
    private String courseType;

    /** 学分 */
    private BigDecimal credit;
    private String description;

    /** 课程封面图 URL（/uploads/course/xxx.jpg），由管理员或任课教师上传 */
    private String coverUrl;
}
