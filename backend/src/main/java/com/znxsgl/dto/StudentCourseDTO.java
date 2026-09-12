package com.znxsgl.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 学生端"我的课程"列表 DTO
 */
@Data
public class StudentCourseDTO {
    private Long courseId;
    private String courseName;
    private String teacherName;
    private String semester;
    private String courseType;
    private String description;
    private boolean active;        // 是否在线（任意班级有排课）
    private boolean published;     // 是否已发布/上架（有 status=1 记录，含占位）
    private boolean hasSchedule;   // 本班级是否有排课
    private boolean hasChapters;   // 是否已创建章节内容（前端据此隐藏“章节”入口）
    private String scheduleInfo;   // 排课信息摘要
    private int unreadCount;       // 未读消息数（>0 显示红点）
    private String lastMessage;    // 课程最后一条聊天消息摘要
    private List<Map<String, Object>> pendingExams; // 该课程下待完成的考试/作业
}
