import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Sparkles, Eye, EyeOff, User as UserIcon, Lock } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { SpotlightCard } from '@/components/SpotlightCard'
import { DecryptText, ShinyText, CircularText, GradientText } from '@/components/text-effects'
import { useAuthStore } from '@/store/auth'
import { login, parseLoginResponse } from '@/api/auth'

export function LoginPage() {
  const navigate = useNavigate()
  const { setAuth } = useAuthStore()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  // React 的 setLoading 要等一次渲染才生效，双击时第二次点击仍会通过 loading 判断，
  // 用同步的 ref 做请求锁，保证只发出一次登录请求
  const submittingRef = useRef(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')

    if (!username.trim() || !password) {
      setError('请输入账号和密码')
      return
    }
    if (submittingRef.current) return

    submittingRef.current = true
    setLoading(true)

    try {
      const res = await login({ username, password })
      if (res.token) {
        const { token, user } = parseLoginResponse(res)
        // 教师管理端不允许学生账号登录
        if (user.role === 'student') {
          setError('学生账号请使用移动端或学生端登录')
          setLoading(false)
          return
        }
        setAuth(token, user)
        navigate('/dashboard')
      } else {
        // 后端未重启时仍可能返回旧文案，这里统一成不暴露账号是否存在的提示
        const msg = res.message || '账号或密码错误'
        setError(msg === '账号不存在' || msg === '密码错误' ? '账号或密码错误' : msg)
      }
    } catch (err: any) {
      const msg = err.response?.data?.message || err.response?.data?.error || '账号或密码错误'
      setError(msg)
    } finally {
      submittingRef.current = false
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-neutral-100 flex">
      {/* ===== 左侧品牌区（右缘斜切，右上高左下低） ===== */}
      <div
        className="hidden lg:flex lg:w-1/2 relative overflow-hidden bg-gradient-to-br from-primary-550 via-primary-500 to-cyan-500"
        style={{ clipPath: 'polygon(0 0, 100% 0, calc(100% - 90px) 100%, 0 100%)' }}
      >
        <div className="absolute top-20 -left-20 w-80 h-80 bg-white/10 rounded-full blur-3xl"></div>
        <div className="absolute bottom-20 -right-10 w-96 h-96 bg-cyan-300/20 rounded-full blur-3xl"></div>
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] bg-white/5 rounded-full blur-2xl"></div>

        {/* 动态雨点光效（保留蓝色主色） */}
        <div className="trae-rain" aria-hidden="true"></div>
        <div className="trae-rain-mesh" aria-hidden="true"></div>

        <div className="relative z-10 flex flex-col items-center justify-between px-10 lg:px-14 py-12 w-full h-full">
          {/* 循环文字大圆 + 中心品牌 */}
          <div className="flex items-center justify-center flex-1 my-6 w-full">
            <CircularText
              text="智学职达 · AI 驱动 · 智能学习管理系统 · 专注度分析 · 智能排课 · AI 答疑 · 试点覆盖 60+ 班级 · 2800+ 学生"
              spinDuration={40}
              fontSize={24}
              className="w-[min(42vw,40vh,430px)] aspect-square"
            >
              <div className="flex flex-col items-center gap-2.5">
                <div className="w-14 h-14 rounded-full bg-white/15 backdrop-blur border border-white/30 flex items-center justify-center shadow-[0_8px_32px_rgba(0,0,0,0.25)]">
                  <Sparkles className="w-7 h-7 text-white" />
                </div>
                <GradientText
                  colors={['#ffffff', '#86efac', '#22c55e', '#86efac', '#ffffff']}
                  speed={5}
                  className="text-xl font-bold"
                >
                  智学职达
                </GradientText>
                <div className="text-[10px] tracking-[0.35em] text-white/55 uppercase">
                  AI Powered Learning
                </div>
              </div>
            </CircularText>
          </div>

          {/* 底部品牌标识（解密文字） */}
          <div className="self-start">
            <DecryptText
              text="DeepSeek V4 Flash"
              className="font-mono text-xl font-semibold tracking-widest text-white/85"
              speed={46}
              cycles={6}
            />
          </div>
        </div>
      </div>

      {/* ===== 右侧登录表单区（左缘做互补斜切，与左面板斜线无缝拼接） ===== */}
      <div className="w-full lg:w-[calc(50%+90px)] lg:-ml-[90px] relative flex items-center justify-center p-6 sm:p-12 lg:pl-[135px] bg-gradient-to-br from-[#eef2ff] via-white to-[#ecfeff] lg:[clip-path:polygon(90px_0,100%_0,100%_100%,0_100%)]">
        {/* 交界处蓝色雾化过渡（沿斜线边缘被裁剪，自然贴合左侧面板） */}
        <div
          aria-hidden="true"
          className="hidden lg:block absolute inset-y-0 -left-10 w-72 bg-gradient-to-r from-primary-300/40 via-primary-200/15 to-transparent pointer-events-none"
        ></div>
        <div className="w-full max-w-md relative">
          <div className="lg:hidden flex items-center gap-3 mb-10 justify-center">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-cyan-500 flex items-center justify-center">
              <Sparkles className="w-5 h-5 text-white" />
            </div>
            <ShinyText
              text="智学职达"
              className="text-xl font-bold text-neutral-900"
              color="#1f2937"
              shineColor="#22d3ee"
              speed={2.4}
            />
          </div>

          <SpotlightCard
            className="bg-surface-primary rounded-[24px] shadow-card p-8"
            spotlightColor="rgba(91, 88, 255, 0.15)"
            spotlightSize={320}
          >
            <div className="text-center mb-8">
              <h2 className="text-2xl font-bold text-neutral-900 mb-2">欢迎回来</h2>
              <p className="text-neutral-500 text-sm">登录您的账号以继续使用</p>
            </div>

            <form onSubmit={handleSubmit} className="space-y-5">
              {error && (
                <div className="bg-danger/10 text-danger text-sm px-4 py-3 rounded-xl">
                  {error}
                </div>
              )}

              <div className="space-y-2">
                <Label htmlFor="username">账号</Label>
                <div className="relative">
                  <UserIcon className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400" />
                  <Input
                    id="username"
                    type="text"
                    placeholder="请输入用户名或学号"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    className="pl-12 h-12 border-sky-200 focus-visible:border-sky-300 focus-visible:shadow-[0_0_0_1px_#7dd3fc,0_0_0_4px_rgb(125_211_252/0.24)]"
                    autoComplete="off"
                    name="account"
                  />
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex justify-between items-center">
                  <Label htmlFor="password">密码</Label>
                </div>
                <div className="relative">
                  <Lock className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400" />
                  <Input
                    id="password"
                    type={showPassword ? 'text' : 'password'}
                    placeholder="请输入密码"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="pl-12 pr-12 h-12 border-sky-200 focus-visible:border-sky-300 focus-visible:shadow-[0_0_0_1px_#7dd3fc,0_0_0_4px_rgb(125_211_252/0.24)]"
                    autoComplete="new-password"
                    name="access-key"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="absolute right-4 top-1/2 -translate-y-1/2 text-neutral-400 hover:text-neutral-600 transition-colors"
                  >
                    {showPassword ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
                  </button>
                </div>
              </div>

              <Button
                type="submit"
                variant="default"
                size="lg"
                className="w-full h-12 text-base"
                disabled={loading}
              >
                {loading ? '登录中...' : '登 录'}
              </Button>
            </form>
          </SpotlightCard>

          <div className="text-center mt-8">
            <DecryptText
              text="DeepSeek V4 Flash"
              className="font-mono text-xs font-semibold tracking-widest text-neutral-400"
              speed={46}
              cycles={6}
            />
          </div>

          {/* 底部合规信息：ICP备案 · 公安备案 · 举报邮箱 */}
          <div className="mt-7 flex flex-col items-center gap-1 text-[11px] leading-5 text-neutral-400">
            <a
              href="https://beian.miit.gov.cn/"
              target="_blank"
              rel="noopener noreferrer"
              className="hover:text-neutral-600 transition-colors"
            >
              桂ICP备2026021376号
            </a>
            <a
              href="https://beian.mps.gov.cn/#/query/webSearch?code=45070202002848"
              rel="noreferrer"
              target="_blank"
              className="inline-flex items-center gap-1 hover:text-neutral-600 transition-colors"
            >
              <img
                src="/gongan.png"
                alt="公安备案图标"
                width={14}
                height={14}
                className="block"
              />
              桂公网安备45070202002848号
            </a>
            <span>
              举报邮箱：
              <a
                href="mailto:j2647046805@163.com"
                className="hover:text-neutral-600 transition-colors"
              >
                j2647046805@163.com
              </a>
            </span>
          </div>
        </div>
      </div>
    </div>
  )
}
