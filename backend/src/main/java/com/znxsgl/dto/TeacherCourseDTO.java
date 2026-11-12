package com.znxsgl.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class TeacherCourseDTO {
    private Long courseId;
    private String courseName;
    private String semester;
    private Long teacherId;
    private List<ClazzDTO> classes;
    private String courseType;
    private String description;
    private String coverUrl; // 课程封面图 URL
    private boolean active;
    private BigDecimal credit; // 课时/学分
    private String teacherName; // 教师姓名（管理员查看时使用）

    // 实际排课信息（取课程下第一个已排课班级的记录，调课后会同步更新）
    private Integer dayOfWeek;
    private Integer startNode;
    private Integer step;
    private String startTime;
    private String endTime;
    private String classroom;
    private String weeks;
    // 聚合排课摘要（如：周一 08:10-08:50；周三 09:50-10:30）
    private String scheduleInfo;

    @Data
    public static class ClazzDTO {
        private Long classId;
        private String className;
        private int studentCount;
        private boolean scheduled; // 该班级是否已排课
        private Long scheduleId; // 课表记录ID（用于调课）
    }
}
