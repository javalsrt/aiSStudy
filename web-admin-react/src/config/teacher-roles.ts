import type { TeacherRole, TeacherRoleCategory } from '@/api/teacher-duty'

/** 与后端 TeacherRoleCatalog 保持一致；接口不可用时作为兜底 */
export const TEACHER_ROLE_CATEGORIES: TeacherRoleCategory[] = [
  { key: 'title', label: '教师职称', multi: false },
  { key: 'position', label: '校内教师岗位角色', multi: true },
  { key: 'leader', label: '校级领导', multi: true },
  { key: 'middle', label: '中层领导', multi: true },
  { key: 'support', label: '其他教辅岗位', multi: true },
]

export const TEACHER_ROLES: TeacherRole[] = [
  { code: 'title_principal_senior', name: '正高级教师', category: 'title', teaches: true },
  { code: 'title_senior', name: '高级教师', category: 'title', teaches: true },
  { code: 'title_first', name: '一级教师', category: 'title', teaches: true },
  { code: 'title_second', name: '二级教师', category: 'title', teaches: true },
  { code: 'title_third', name: '三级教师', category: 'title', teaches: true },

  { code: 'position_head_teacher', name: '班主任', category: 'position', teaches: true },
  { code: 'position_grade_leader', name: '年级组长', category: 'position', teaches: true },
  { code: 'position_research_leader', name: '教研组长', category: 'position', teaches: true },
  { code: 'position_lesson_leader', name: '备课组长', category: 'position', teaches: true },
  { code: 'position_subject_leader', name: '学科带头人', category: 'position', teaches: true },
  { code: 'position_backbone', name: '骨干教师', category: 'position', teaches: true },

  { code: 'leader_principal', name: '校长', category: 'leader', teaches: true },
  { code: 'leader_party_secretary', name: '党委书记（党支部书记）', category: 'leader', teaches: true },
  { code: 'leader_vice_principal', name: '副校长', category: 'leader', teaches: true },
  { code: 'leader_discipline', name: '纪委书记', category: 'leader', teaches: false },
  { code: 'leader_union_chair', name: '工会主席', category: 'leader', teaches: false },

  { code: 'middle_academic_director', name: '教务处主任/副主任', category: 'middle', teaches: true },
  { code: 'middle_moral_director', name: '政教处（德育处）主任/副主任', category: 'middle', teaches: true },
  { code: 'middle_logistics_director', name: '总务处主任/副主任', category: 'middle', teaches: false },
  { code: 'middle_office_director', name: '办公室主任', category: 'middle', teaches: false },
  { code: 'middle_research_director', name: '教研室主任', category: 'middle', teaches: true },
  { code: 'middle_youth_secretary', name: '团委书记', category: 'middle', teaches: true },
  { code: 'middle_grade_director', name: '年级主任', category: 'middle', teaches: true },

  { code: 'support_school_doctor', name: '校医', category: 'support', teaches: false },
  { code: 'support_librarian', name: '图书管理员', category: 'support', teaches: false },
  { code: 'support_lab_assistant', name: '实验员', category: 'support', teaches: false },
]
