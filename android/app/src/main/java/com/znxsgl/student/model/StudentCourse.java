package com.znxsgl.student.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Map;

public class StudentCourse {
    @SerializedName("courseId")
    private long courseId;

    @SerializedName("courseName")
    private String courseName;

    @SerializedName("teacherName")
    private String teacherName;

    @SerializedName("semester")
    private String semester;

    @SerializedName("courseType")
    private String courseType;

    @SerializedName("description")
    private String description;

    @SerializedName("active")
    private boolean active;

    @SerializedName("published")
    private boolean published;

    @SerializedName("hasSchedule")
    private boolean hasSchedule;
    private boolean hasChapters;

    @SerializedName("scheduleInfo")
    private String scheduleInfo;

    @SerializedName("unreadCount")
    private int unreadCount;

    @SerializedName("lastMessage")
    private String lastMessage;

    @SerializedName("pendingExams")
    private List<Map<String, Object>> pendingExams;

    public long getCourseId() { return courseId; }
    public void setCourseId(long v) { this.courseId = v; }
    public String getCourseName() { return courseName; }
    public void setCourseName(String v) { this.courseName = v; }
    public String getTeacherName() { return teacherName; }
    public void setTeacherName(String v) { this.teacherName = v; }
    public String getSemester() { return semester; }
    public void setSemester(String v) { this.semester = v; }
    public String getCourseType() { return courseType; }
    public void setCourseType(String v) { this.courseType = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean v) { this.published = v; }
    public boolean isHasSchedule() { return hasSchedule; }
    public void setHasSchedule(boolean v) { this.hasSchedule = v; }
    public boolean isHasChapters() { return hasChapters; }
    public void setHasChapters(boolean v) { this.hasChapters = v; }
    public String getScheduleInfo() { return scheduleInfo; }
    public void setScheduleInfo(String v) { this.scheduleInfo = v; }
    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int v) { this.unreadCount = v; }

    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String v) { this.lastMessage = v; }

    public List<Map<String, Object>> getPendingExams() { return pendingExams; }
    public void setPendingExams(List<Map<String, Object>> v) { this.pendingExams = v; }
}
