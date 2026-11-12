package com.znxsgl.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师身份目录：职称、校内岗位角色、校级/中层领导、教辅岗位。
 *
 * teaches 表示该身份是否承担课堂教学任务：
 * - 教师职称全部上课；
 * - 校级领导中校长/书记/副校长通常兼课，纪委书记/工会主席一般不排常规课；
 * - 中层领导中教务/政教/教研室/团委/年级主任通常兼课，总务/办公室不上课；
 * - 校医、图书管理员、实验员为教辅岗，不承担课堂教学。
 */
public final class TeacherRoleCatalog {

    public static final String CAT_TITLE = "title";
    public static final String CAT_POSITION = "position";
    public static final String CAT_LEADER = "leader";
    public static final String CAT_MIDDLE = "middle";
    public static final String CAT_SUPPORT = "support";

    public static final class Role {
        private final String code;
        private final String name;
        private final String category;
        private final boolean teaches;

        public Role(String code, String name, String category, boolean teaches) {
            this.code = code;
            this.name = name;
            this.category = category;
            this.teaches = teaches;
        }

        public String getCode() { return code; }
        public String getName() { return name; }
        public String getCategory() { return category; }
        public boolean isTeaches() { return teaches; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", name);
            m.put("category", category);
            m.put("teaches", teaches);
            return m;
        }
    }

    private static final List<Role> ROLES = List.of(
            // 一、教师职称（教学岗）
            new Role("title_principal_senior", "正高级教师", CAT_TITLE, true),
            new Role("title_senior", "高级教师", CAT_TITLE, true),
            new Role("title_first", "一级教师", CAT_TITLE, true),
            new Role("title_second", "二级教师", CAT_TITLE, true),
            new Role("title_third", "三级教师", CAT_TITLE, true),
            // 二、校内教师岗位角色
            new Role("position_head_teacher", "班主任", CAT_POSITION, true),
            new Role("position_grade_leader", "年级组长", CAT_POSITION, true),
            new Role("position_research_leader", "教研组长", CAT_POSITION, true),
            new Role("position_lesson_leader", "备课组长", CAT_POSITION, true),
            new Role("position_subject_leader", "学科带头人", CAT_POSITION, true),
            new Role("position_backbone", "骨干教师", CAT_POSITION, true),
            // 三、校级领导
            new Role("leader_principal", "校长", CAT_LEADER, true),
            new Role("leader_party_secretary", "党委书记（党支部书记）", CAT_LEADER, true),
            new Role("leader_vice_principal", "副校长", CAT_LEADER, true),
            new Role("leader_discipline", "纪委书记", CAT_LEADER, false),
            new Role("leader_union_chair", "工会主席", CAT_LEADER, false),
            // 四、中层领导
            new Role("middle_academic_director", "教务处主任/副主任", CAT_MIDDLE, true),
            new Role("middle_moral_director", "政教处（德育处）主任/副主任", CAT_MIDDLE, true),
            new Role("middle_logistics_director", "总务处主任/副主任", CAT_MIDDLE, false),
            new Role("middle_office_director", "办公室主任", CAT_MIDDLE, false),
            new Role("middle_research_director", "教研室主任", CAT_MIDDLE, true),
            new Role("middle_youth_secretary", "团委书记", CAT_MIDDLE, true),
            new Role("middle_grade_director", "年级主任", CAT_MIDDLE, true),
            // 五、其他教辅岗位
            new Role("support_school_doctor", "校医", CAT_SUPPORT, false),
            new Role("support_librarian", "图书管理员", CAT_SUPPORT, false),
            new Role("support_lab_assistant", "实验员", CAT_SUPPORT, false)
    );

    private TeacherRoleCatalog() {
    }

    public static List<Role> all() {
        return ROLES;
    }

    public static Map<String, Role> byCode() {
        Map<String, Role> map = new LinkedHashMap<>();
        for (Role role : ROLES) map.put(role.getCode(), role);
        return map;
    }

    public static List<Map<String, Object>> asMaps() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Role role : ROLES) list.add(role.toMap());
        return list;
    }

    public static List<Map<String, Object>> categories() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(category("title", "教师职称", false));
        list.add(category("position", "校内教师岗位角色", true));
        list.add(category("leader", "校级领导", true));
        list.add(category("middle", "中层领导", true));
        list.add(category("support", "其他教辅岗位", true));
        return list;
    }

    private static Map<String, Object> category(String key, String label, boolean multi) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("multi", multi);
        return m;
    }

    /** 管理岗：校级领导、中层领导、年级/教研组长 */
    public static boolean isManagement(Role role) {
        if (role == null) return false;
        return CAT_LEADER.equals(role.getCategory())
                || CAT_MIDDLE.equals(role.getCategory())
                || "position_grade_leader".equals(role.getCode())
                || "position_research_leader".equals(role.getCode());
    }
}
