import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft,
  Building2,
  GraduationCap,
  RotateCcw,
} from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { getUserList } from '@/api/staff'
import {
  getStaffRelations,
  type StaffRelations,
  type StaffRelationTeacher,
} from '@/api/teacher-duty'
import type { UserListItem } from '@/types'
import { StageGraph, type GraphLink, type GraphNode } from '@/components/star-map/stage-graph'
import { seedOf, type NetLink, type NetNode, type NetworkData } from '@/components/star-map/engine'

export type RelationRole = 'students' | 'teachers'

interface StaffRelationGraphProps {
  role: RelationRole
  majors: { name: string; count: number }[]
  classes: { id: number; name: string; major: string; count: number }[]
  deptMembers?: { dept: string; realName: string; username: string; status: number }[]
}

const EMPTY_DEPT_MEMBERS: NonNullable<StaffRelationGraphProps['deptMembers']> = []

const CAT_BRANCH = 1
const CAT_CLASS = 2
const CAT_ROLE = 3
const CAT_TEACHER = 4
const CAT_STUDENT = 5

const LEGEND_COLORS = {
  blue: '#0a84ff',
  green: '#34c759',
  purple: '#6d66ff',
  orange: '#ff9500',
  teal: '#0fb5ae',
}

interface ClassRow {
  id: number
  name: string
  major: string
  count: number
  department: string
  headTeacherId: number | null
  headTeacherName: string | null
}

interface BranchRow {
  name: string
  count: number
  classCount: number
  members: StaffRelationTeacher[]
}

type Branch = { kind: 'major' | 'dept'; name: string }
type Focus =
  | { kind: 'class'; id: number; name: string }
  | { kind: 'teacher'; id: number; name: string }

const stagger = (i: number, n: number, base = 0) => base + i * Math.min(0.08, 1.4 / Math.max(n, 1))

/** 竖排一列节点：按容器高度自动压缩间距 */
function columnNodes<T>(
  items: T[],
  x: number,
  height: number,
  make: (item: T, y: number, index: number) => GraphNode,
  top = 70,
  bottom = 54,
) {
  const span = Math.max(120, height - top - bottom)
  const gap = items.length > 1 ? Math.min(46, span / (items.length - 1)) : 0
  const y0 = top + span / 2 - (gap * (items.length - 1)) / 2
  return items.map((item, index) => make(item, y0 + index * gap, index))
}

/** 人员关系星图：中心珍珠球 + 玻璃刻度表盘 + 轨道 + 三层下钻 */
export function StaffRelationGraph({
  role,
  majors,
  classes,
  deptMembers = EMPTY_DEPT_MEMBERS,
}: StaffRelationGraphProps) {
  const [relations, setRelations] = useState<StaffRelations | null>(null)
  const [branch, setBranch] = useState<Branch | null>(null)
  const [focus, setFocus] = useState<Focus | null>(null)
  const [students, setStudents] = useState<UserListItem[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [size, setSize] = useState({ w: 0, h: 0 })
  const studentCache = useRef(new Map<number, UserListItem[]>())

  useEffect(() => {
    let alive = true
    getStaffRelations()
      .then((data) => {
        if (alive) setRelations(data)
      })
      .catch(() => {
        if (alive) setRelations(null)
      })
    return () => {
      alive = false
    }
  }, [])

  const handleSize = useCallback((next: { w: number; h: number }) => setSize(next), [])

  const teacherRows: StaffRelationTeacher[] = useMemo(
    () =>
      relations?.teachers?.length
        ? relations.teachers
        : deptMembers.map((member, index) => ({
            id: -(index + 1),
            name: member.realName,
            dept: member.dept,
            status: member.status,
            roles: [],
            roleNames: [],
            canTeach: true,
          })),
    [relations, deptMembers],
  )

  const classRows: ClassRow[] = useMemo(
    () =>
      relations?.classes?.length
        ? relations.classes.map((row) => ({
            id: row.id,
            name: row.className,
            major: row.major || '未设置专业',
            count: row.studentCount,
            department: row.department || '',
            headTeacherId: row.headTeacherId ?? null,
            headTeacherName: row.headTeacherName ?? null,
          }))
        : classes.map((row) => ({
            id: row.id,
            name: row.name,
            major: row.major || '未设置专业',
            count: row.count,
            department: '',
            headTeacherId: null,
            headTeacherName: null,
          })),
    [relations, classes],
  )

  const majorRows: BranchRow[] = useMemo(() => {
    const map = new Map<string, BranchRow>()
    majors.forEach((major) => {
      if (!major.name) return
      map.set(major.name, { name: major.name, count: major.count, classCount: 0, members: [] })
    })
    classRows.forEach((row) => {
      const entry = map.get(row.major) || { name: row.major, count: 0, classCount: 0, members: [] }
      entry.classCount += 1
      if (!majors.some((m) => m.name === row.major)) entry.count += row.count
      map.set(row.major, entry)
    })
    return Array.from(map.values()).sort((a, b) => b.count - a.count)
  }, [majors, classRows])

  const deptRows: BranchRow[] = useMemo(() => {
    const map = new Map<string, BranchRow>()
    teacherRows.forEach((teacher) => {
      const name = teacher.dept || '未设置院系'
      const entry = map.get(name) || { name, count: 0, classCount: 0, members: [] }
      entry.count += 1
      entry.members.push(teacher)
      map.set(name, entry)
    })
    map.forEach((entry) => {
      entry.members.sort((a, b) => (b.status === 0 ? -1 : 1) - (a.status === 0 ? -1 : 1))
    })
    return Array.from(map.values()).sort((a, b) => b.count - a.count)
  }, [teacherRows])

  const branches = role === 'teachers' ? deptRows : majorRows
  const totalPeople =
    role === 'teachers' ? teacherRows.length : branches.reduce((sum, row) => sum + row.count, 0)

  /* 角色切换时回到全景 */
  useEffect(() => {
    setBranch(null)
    setFocus(null)
    setStudents([])
    setError('')
  }, [role])

  /* 数据刷新后失效的选中态回退 */
  useEffect(() => {
    if (branch && !branches.some((row) => row.name === branch.name)) {
      setBranch(null)
      setFocus(null)
    }
  }, [branch, branches])

  /* 聚焦班级时按需加载学生 */
  useEffect(() => {
    if (focus?.kind !== 'class') return
    const cached = studentCache.current.get(focus.id)
    if (cached) {
      setStudents(cached)
      setError('')
      return
    }
    let alive = true
    setStudents([])
    setLoading(true)
    setError('')
    getUserList({ role: 1, classId: focus.id, pageNum: 1, pageSize: 300 })
      .then((res) => {
        const list = res?.list || []
        studentCache.current.set(focus.id, list)
        if (alive) setStudents(list)
      })
      .catch(() => {
        if (alive) setError('该班级学生加载失败，请稍后重试')
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [focus])

  /** 班级的班主任与任课教师 */
  const teachersOfClass = useCallback(
    (classId: number) => {
      const cls = classRows.find((row) => row.id === classId)
      const map = new Map<number, { teacher: StaffRelationTeacher; courses: string[] }>()
      for (const row of relations?.teachings || []) {
        if (row.classId !== classId) continue
        const teacher = teacherRows.find((item) => item.id === row.teacherId)
        if (!teacher) continue
        const entry = map.get(teacher.id) || { teacher, courses: [] }
        if (row.courseName && !entry.courses.includes(row.courseName)) entry.courses.push(row.courseName)
        map.set(teacher.id, entry)
      }
      const headId =
        cls?.headTeacherId ??
        teacherRows.find((item) => item.name === cls?.headTeacherName)?.id ??
        null
      if (headId && !map.has(headId)) {
        const teacher = teacherRows.find((item) => item.id === headId)
        if (teacher) map.set(headId, { teacher, courses: [] })
      }
      return Array.from(map.values()).sort((a, b) => {
        const aHead = headId === a.teacher.id ? 0 : 1
        const bHead = headId === b.teacher.id ? 0 : 1
        return aHead - bHead
      })
    },
    [classRows, relations, teacherRows],
  )

  /** 教师任教班级 */
  const classesOfTeacher = useCallback(
    (teacherId: number) => {
      const map = new Map<number, { id: number; name: string; courses: string[] }>()
      for (const row of relations?.teachings || []) {
        if (row.teacherId !== teacherId) continue
        const entry = map.get(row.classId) || { id: row.classId, name: row.className, courses: [] }
        if (row.courseName && !entry.courses.includes(row.courseName)) entry.courses.push(row.courseName)
        map.set(row.classId, entry)
      }
      return Array.from(map.values())
    },
    [relations],
  )

  const layer: 'hub' | 'branch' | 'page' = focus ? 'page' : branch ? 'branch' : 'hub'

  const scene = useMemo<{ nodes: GraphNode[]; links: GraphLink[]; network: NetworkData | null }>(() => {
    const nodes: GraphNode[] = []
    const links: GraphLink[] = []
    const { w, h } = size
    if (!w || !h) return { nodes, links, network: null }

    if (layer === 'hub') {
      const cx = w / 2
      const cy = h * 0.5
      const dialSize = branches.length > 18 ? 52 : branches.length > 12 ? 64 : branches.length > 8 ? 86 : 96
      const compact = branches.length > 12
      const rx = Math.max(150, w / 2 - dialSize - 46)
      const ry = Math.max(120, h / 2 - dialSize - 26)
      const max = Math.max(1, ...branches.map((row) => row.count))
      nodes.push({
        id: 'root',
        x: cx,
        y: cy,
        size: 96,
        kind: 'core',
        label: '人员',
        sub: `${totalPeople} 人`,
        interactive: false,
        float: true,
      })
      const netNodes: NetNode[] = [{ id: 'net:center', x: cx, y: cy, cat: 0, r: 2.4, depth: 1 }]
      const netLinks: NetLink[] = []
      branches.forEach((row, index) => {
        const angle = -Math.PI / 2 + (index / Math.max(branches.length, 1)) * Math.PI * 2
        const x = cx + Math.cos(angle) * rx
        const y = cy + Math.sin(angle) * ry
        const cat = CAT_BRANCH
        nodes.push({
          id: `b:${row.name}`,
          x,
          y,
          size: dialSize,
          kind: 'hub',
          label: row.name,
          sub: role === 'teachers' ? `${row.count} 位教师` : `${row.classCount} 个班级`,
          icon: role === 'teachers' ? <Building2 /> : <GraduationCap />,
          cat,
          count: row.count,
          ratio: row.count / max,
          compact,
          float: true,
          delay: 0.5 + index * 0.08,
        })
        links.push({ a: 'root', b: `b:${row.name}`, flow: 'both', shape: 'arc', cat })
        netNodes.push({ id: `net:b:${row.name}`, x, y, cat, r: 3, depth: 1 })
        netLinks.push({ a: 'net:center', b: `net:b:${row.name}`, cat })

        // 背景网络：真实数据点位围绕所属分支蔓延
        const members: { id: string; label: string }[] =
          role === 'teachers'
            ? row.members.slice(0, 14).map((member) => ({
                id: `net:t:${member.id}`,
                label: member.name,
              }))
            : classRows
                .filter((cls) => cls.major === row.name)
                .slice(0, 14)
                .map((cls) => ({ id: `net:c:${cls.id}`, label: cls.name }))
        members.forEach((member, order) => {
          const seed = seedOf(member.id)
          const spread = (order - (members.length - 1) / 2) * 0.1
          const memberAngle = angle + spread + (seed - 0.5) * 0.12
          const distance = 54 + seed * 46
          netNodes.push({
            id: member.id,
            x: x + Math.cos(memberAngle) * distance,
            y: y + Math.sin(memberAngle) * distance,
            cat: role === 'teachers' ? CAT_TEACHER : CAT_CLASS,
            r: 1.8,
            depth: 2,
          })
          netLinks.push({ a: `net:b:${row.name}`, b: member.id, cat })
        })
      })
      return { nodes, links, network: { nodes: netNodes, links: netLinks } }
    }

    const current = branch
    if (!current) return { nodes, links, network: null }
    const selected = branches.find((row) => row.name === current.name)
    const max = Math.max(1, ...branches.map((row) => row.count))
    const cy = 70 + (h - 70 - 54) / 2

    if (layer === 'branch') {
      const coreX = Math.max(86, w * 0.09)
      const colX = coreX + 128
      nodes.push({
        id: 'root',
        x: coreX,
        y: cy,
        size: 56,
        kind: 'core',
        label: '人员',
        sub: `${totalPeople} 人`,
        interactive: false,
      })
      // 一级项竖排：选中居中放大，其余上下排开并退暗
      const others = branches.filter((row) => row.name !== current.name).slice(0, 4)
      const half = Math.ceil(others.length / 2)
      const order = [...others.slice(0, half), selected ?? { name: current.name, count: 0, classCount: 0, members: [] }, ...others.slice(half)]
      const gap = Math.min(112, (h - 150) / Math.max(order.length - 1, 1))
      const selectedIndex = Math.floor(order.length / 2)
      order.forEach((row, index) => {
        const isSelected = row.name === current.name
        nodes.push({
          id: `b:${row.name}`,
          x: colX,
          y: cy + (index - selectedIndex) * gap,
          size: isSelected ? 80 : 50,
          kind: 'hub',
          label: row.name,
          icon: role === 'teachers' ? <Building2 /> : <GraduationCap />,
          cat: CAT_BRANCH,
          count: isSelected ? row.count : undefined,
          ratio: row.count / max,
          float: true,
          delay: index * 0.05,
        })
        links.push({
          a: 'root',
          b: `b:${row.name}`,
          flow: isSelected ? 'both' : 'none',
          shape: 'h',
          cat: CAT_BRANCH,
        })
      })

      // 分支成员：学生视角为班级，教师视角为教师
      const items =
        role === 'teachers'
          ? (selected?.members ?? []).map((member) => ({
              id: `t:${member.id}`,
              label: member.name,
              sub: member.dept || member.roleNames.join('、') || '教师',
              tone: member.status === 0 ? ('off' as const) : undefined,
              cat: CAT_TEACHER,
              count: undefined as number | undefined,
            }))
          : classRows
              .filter((cls) => cls.major === current.name)
              .sort((a, b) => b.count - a.count)
              .map((cls) => ({
                id: `c:${cls.id}`,
                label: cls.name,
                sub: cls.headTeacherName ? `班主任 ${cls.headTeacherName}` : '未设置班主任',
                tone: undefined,
                cat: CAT_CLASS,
                count: cls.count,
              }))
      const shown = items.slice(0, 24)
      const perColumn = 12
      const fanX = Math.min(Math.max(colX + 150, w * 0.38), w - 300)
      const columnGap = Math.min(190, Math.max(130, w - fanX - 160))
      shown.forEach((item, index) => {
        const column = Math.floor(index / perColumn)
        const rowIndex = index % perColumn
        const rowsInColumn = Math.min(perColumn, shown.length - column * perColumn)
        const span = Math.max(120, h - 140)
        const rowGap = rowsInColumn > 1 ? Math.min(42, span / (rowsInColumn - 1)) : 0
        const y0 = 70 + span / 2 - (rowGap * (rowsInColumn - 1)) / 2
        const id = item.id
        nodes.push({
          id,
          x: fanX + column * columnGap,
          y: y0 + rowIndex * rowGap,
          kind: 'item',
          align: 'left',
          label: item.label,
          sub: item.sub,
          cat: item.cat,
          tone: item.tone,
          count: item.count,
          from: `b:${current.name}`,
          delay: 0.1 + stagger(index, shown.length),
        })
        links.push({
          a: `b:${current.name}`,
          b: id,
          flow: 'forward',
          shape: 'h',
          cat: item.cat,
        })
      })
      if (items.length > shown.length) {
        nodes.push({
          id: 'more',
          x: fanX,
          y: h - 30,
          kind: 'item',
          align: 'left',
          label: `还有 ${items.length - shown.length} 项`,
          interactive: false,
          cat: CAT_BRANCH,
          from: `b:${current.name}`,
          delay: 0.3,
        })
        links.push({ a: `b:${current.name}`, b: 'more', flow: 'none', shape: 'h', dashed: true, cat: CAT_BRANCH })
      }
      return { nodes, links, network: null }
    }

    // 页面层：聚焦班级 / 教师
    if (focus?.kind === 'class') {
      const cls = classRows.find((row) => row.id === focus.id)
      const classId = `c:${focus.id}`
      const centerX = w * 0.45
      const centerY = h * 0.5
      const teacherList = teachersOfClass(focus.id).slice(0, 8)
      const teacherNodes = columnNodes(
        teacherList,
        w * 0.16,
        h,
        ({ teacher, courses }, y, index) => ({
          id: `t:${teacher.id}`,
          x: w * 0.16,
          y,
          kind: 'item',
          align: 'right',
          label: teacher.name,
          sub: courses.length ? courses.join('、') : teacher.roleNames.join('、') || '教师',
          cat: CAT_TEACHER,
          tone: teacher.status === 0 ? 'off' : undefined,
          interactive: false,
          from: classId,
          delay: stagger(index, teacherList.length, 0.15),
        }),
      )
      const shownStudents = students.slice(0, 24)
      const perColumn = 12
      const columns = Math.min(2, Math.max(1, Math.ceil(shownStudents.length / perColumn)))
      const studentNodes: GraphNode[] = []
      shownStudents.forEach((student, index) => {
        const column = Math.floor(index / perColumn)
        const rowIndex = index % perColumn
        const rowsInColumn = Math.min(perColumn, shownStudents.length - column * perColumn)
        const span = Math.max(120, h - 140)
        const rowGap = rowsInColumn > 1 ? Math.min(38, span / (rowsInColumn - 1)) : 0
        const y0 = 70 + span / 2 - (rowGap * (rowsInColumn - 1)) / 2
        studentNodes.push({
          id: `s:${student.id}`,
          x: w * (columns > 1 ? 0.7 + column * 0.14 : 0.74),
          y: y0 + rowIndex * rowGap,
          kind: 'item',
          align: 'left',
          label: student.realName || student.username,
          sub: student.studentNo || undefined,
          cat: CAT_STUDENT,
          interactive: false,
          from: classId,
          delay: stagger(index, shownStudents.length, 0.2),
        })
      })
      nodes.push(...teacherNodes)
      nodes.push({
        id: classId,
        x: centerX,
        y: centerY,
        kind: 'item',
        label: focus.name,
        sub: `${cls?.major ?? current.name} · ${cls?.count ?? students.length} 人`,
        cat: CAT_CLASS,
        interactive: false,
      })
      nodes.push(...studentNodes)
      teacherNodes.forEach((node) => links.push({ a: node.id, b: classId, flow: 'forward', shape: 'h', cat: CAT_TEACHER }))
      studentNodes.forEach((node) => links.push({ a: classId, b: node.id, flow: 'forward', shape: 'h', cat: CAT_STUDENT }))
      if (students.length > shownStudents.length) {
        nodes.push({
          id: 'more',
          x: w * 0.9,
          y: h - 40,
          kind: 'item',
          align: 'left',
          label: `还有 ${students.length - shownStudents.length} 名学生`,
          interactive: false,
          cat: CAT_STUDENT,
          from: classId,
          delay: 0.3,
        })
        links.push({ a: classId, b: 'more', flow: 'none', shape: 'h', dashed: true, cat: CAT_STUDENT })
      }
      return {
        nodes,
        links,
        network: null,
      }
    }

    // 教师：身份 + 院系在左，授课班级在右
    if (focus?.kind === 'teacher') {
      const teacher = teacherRows.find((row) => row.id === focus.id)
      const teacherId = `t:${focus.id}`
      const centerX = w * 0.45
      const centerY = h * 0.5
      const leftItems: GraphNode[] = []
      if (teacher?.dept) {
        leftItems.push({
          id: `dept:${teacher.dept}`,
          x: w * 0.17,
          y: centerY - 34,
          kind: 'item',
          align: 'right',
          label: teacher.dept,
          sub: '所属院系',
          cat: CAT_BRANCH,
          interactive: false,
          from: teacherId,
          delay: 0.1,
        })
      }
      ;(teacher?.roles ?? []).slice(0, 5).forEach((roleItem, index) => {
        leftItems.push({
          id: `role:${roleItem.code}`,
          x: w * 0.17,
          y: centerY + 34 + index * 40,
          kind: 'item',
          align: 'right',
          label: roleItem.name,
          sub: '身份',
          cat: CAT_ROLE,
          interactive: false,
          from: teacherId,
          delay: 0.16 + index * 0.06,
        })
      })
      const classList = classesOfTeacher(focus.id).slice(0, 12)
      const classNodes = columnNodes(
        classList,
        w * 0.74,
        h,
        ({ id, name, courses }, y, index) => ({
          id: `c:${id}`,
          x: w * 0.74,
          y,
          kind: 'item',
          align: 'left',
          label: name,
          sub: courses.length ? courses.join('、') : '授课班级',
          count: classRows.find((row) => row.id === id)?.count,
          cat: CAT_CLASS,
          interactive: false,
          from: teacherId,
          delay: stagger(index, classList.length, 0.15),
        }),
      )
      nodes.push(...leftItems)
      nodes.push({
        id: teacherId,
        x: centerX,
        y: centerY,
        kind: 'item',
        label: focus.name,
        sub: teacher?.duty || teacher?.title || teacher?.dept || '教师',
        cat: CAT_TEACHER,
        tone: teacher?.status === 0 ? 'off' : undefined,
        interactive: false,
      })
      nodes.push(...classNodes)
      leftItems.forEach((node) => links.push({ a: node.id, b: teacherId, flow: 'forward', shape: 'h', cat: node.cat }))
      classNodes.forEach((node) => links.push({ a: teacherId, b: node.id, flow: 'forward', shape: 'h', cat: CAT_CLASS }))
      return { nodes, links, network: null }
    }

    return { nodes, links, network: null }
  }, [
    size,
    layer,
    branch,
    focus,
    role,
    branches,
    classRows,
    students,
    totalPeople,
    teachersOfClass,
    classesOfTeacher,
    teacherRows,
  ])

  const onSelect = (node: GraphNode) => {
    if (node.id.startsWith('b:')) {
      const name = node.id.slice(2)
      setBranch({ kind: role === 'teachers' ? 'dept' : 'major', name })
      setFocus(null)
      setStudents([])
      setError('')
      return
    }
    if (node.id.startsWith('c:')) {
      const id = Number(node.id.slice(2))
      const cls = classRows.find((row) => row.id === id)
      setFocus({ kind: 'class', id, name: cls?.name ?? node.label })
      return
    }
    if (node.id.startsWith('t:')) {
      const id = Number(node.id.slice(2))
      const teacher = teacherRows.find((row) => row.id === id)
      setFocus({ kind: 'teacher', id, name: teacher?.name ?? node.label })
    }
  }

  const home = () => {
    setBranch(null)
    setFocus(null)
    setStudents([])
    setError('')
  }

  const back = () => {
    if (focus) {
      setFocus(null)
      return
    }
    if (branch) setBranch(null)
  }

  const crumbs = useMemo(() => {
    const list: { label: string; current?: boolean; onClick?: () => void }[] = [
      { label: '人员', current: layer === 'hub', onClick: home },
    ]
    if (branch) {
      list.push({
        label: branch.name,
        current: layer === 'branch',
        onClick: () => {
          setFocus(null)
          setStudents([])
        },
      })
    }
    if (focus) list.push({ label: focus.name, current: true })
    return list
  }, [branch, focus, layer])

  const legend =
    role === 'students'
      ? [
          { label: '专业', color: LEGEND_COLORS.blue },
          { label: '班级', color: LEGEND_COLORS.green },
          { label: '教师', color: LEGEND_COLORS.orange },
          { label: '学生', color: LEGEND_COLORS.teal },
        ]
      : [
          { label: '院系', color: LEGEND_COLORS.blue },
          { label: '教师', color: LEGEND_COLORS.orange },
          { label: '身份', color: LEGEND_COLORS.purple },
          { label: '班级', color: LEGEND_COLORS.green },
        ]

  const hint =
    layer === 'hub'
      ? '点击表盘展开分支 · 刻度为人员数量占比'
      : layer === 'branch'
        ? role === 'teachers'
          ? '点击教师查看身份与授课班级'
          : '点击班级查看学生与任课教师'
        : '点击面包屑或「返回上一层」回到总览'

  const empty =
    role === 'students' ? branches.length === 0 && classRows.length === 0 : teacherRows.length === 0

  return (
    <Card className="overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3 px-5 pb-3 pt-4">
        <div>
          <div className="text-sm font-medium text-ink-primary">人员关系星图</div>
          <div className="mt-0.5 text-xs text-ink-tertiary">
            {layer === 'hub'
              ? role === 'students'
                ? '专业 → 班级 → 学生，点击表盘逐层展开'
                : '院系 → 教师 → 授课，点击表盘逐层展开'
              : '悬停节点高亮关联 · 右上角可重播开场与暂停动效'}
          </div>
        </div>
        <div className="flex items-center gap-2">
          {layer !== 'hub' && (
            <Button variant="outline" size="sm" className="h-7 gap-1 px-2 text-xs" onClick={back}>
              <ArrowLeft className="h-3.5 w-3.5" />
              返回上一层
            </Button>
          )}
          {layer !== 'hub' && (
            <Button variant="ghost" size="sm" className="h-7 gap-1 px-2 text-xs" onClick={home}>
              <RotateCcw className="h-3.5 w-3.5" />
              全景
            </Button>
          )}
        </div>
      </div>

      <div className="relative">
        <StageGraph
          nodes={scene.nodes}
          links={scene.links}
          network={scene.network}
          orbits={[
            { rx: Math.max(150, size.w / 2 - 180), ry: Math.max(120, size.h / 2 - 150) },
            { rx: Math.max(190, size.w / 2 - 120), ry: Math.max(150, size.h / 2 - 100) },
            { rx: Math.max(230, size.w / 2 - 66), ry: Math.max(180, size.h / 2 - 52) },
          ].filter((orbit) => orbit.rx > 0 && orbit.ry > 0 && layer === 'hub')}
          activeId={focus ? (focus.kind === 'class' ? `c:${focus.id}` : `t:${focus.id}`) : null}
          label="人员关系星图"
          hint={hint}
          onSelect={onSelect}
          onBack={back}
          onSize={handleSize}
          topLeft={
            <nav className="sm-crumbs" aria-label="星图路径">
              {crumbs.map((crumb, index) => (
                <span key={`${crumb.label}-${index}`} className="flex items-center gap-1.5">
                  {index > 0 && (
                    <span className="sm-sep" aria-hidden="true">
                      /
                    </span>
                  )}
                  {crumb.current ? (
                    <b className="sm-crumb is-current">{crumb.label}</b>
                  ) : (
                    <button type="button" className="sm-crumb" onClick={crumb.onClick}>
                      {crumb.label}
                    </button>
                  )}
                </span>
              ))}
            </nav>
          }
          topRight={
            <div className="sm-legend">
              {legend.map((item) => (
                <span key={item.label}>
                  <i style={{ backgroundColor: item.color }} />
                  {item.label}
                </span>
              ))}
            </div>
          }
        />

        {empty && (
          <div className="pointer-events-none absolute inset-0 grid place-items-center text-sm text-neutral-400">
            暂无可展示的人员关系数据
          </div>
        )}
        {loading && (
          <div className="pointer-events-none absolute inset-x-0 bottom-3 flex justify-center">
            <span className="rounded-full bg-neutral-900/75 px-3 py-1 text-xs text-white">
              正在加载关系…
            </span>
          </div>
        )}
        {error && (
          <div className="pointer-events-none absolute inset-x-0 bottom-3 flex justify-center">
            <span className="rounded-full bg-rose-500/90 px-3 py-1 text-xs text-white">{error}</span>
          </div>
        )}
      </div>
    </Card>
  )
}
