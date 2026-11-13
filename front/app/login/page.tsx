'use client'

import { FormEvent, useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { ArrowRight, BriefcaseBusiness, CheckCircle2, KeyRound, Loader2, LockKeyhole, MessageSquareText, ShieldCheck } from 'lucide-react'
import { apiFetch, apiUrl } from '@/lib/api'

export default function LoginPage() {
  const router = useRouter()
  const [code, setCode] = useState('')
  const [configured, setConfigured] = useState<boolean | null>(null)
  const [sending, setSending] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [remaining, setRemaining] = useState(0)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const input = useRef<HTMLInputElement>(null)

  const destination = () => {
    const next = new URLSearchParams(window.location.search).get('next') || '/'
    return /^\/(?!\/)/.test(next) && !next.includes('\\') && !/[\r\n]/.test(next) && !next.startsWith('/login') ? next : '/'
  }

  useEffect(() => {
    const controller = new AbortController()
    apiFetch(apiUrl('/api/auth/session'), { signal: controller.signal })
      .then(async response => {
        if (!response.ok) throw new Error()
        const session = await response.json()
        if (controller.signal.aborted) return
        setConfigured(session.configured)
        if (session.authenticated) router.replace(destination())
      })
      .catch(() => { if (!controller.signal.aborted) setError('无法连接服务器，请检查服务状态后刷新页面') })
    return () => controller.abort()
  }, [router])

  useEffect(() => {
    if (remaining <= 0) return
    const timer = window.setTimeout(() => setRemaining(value => Math.max(0, value - 1)), 1000)
    return () => window.clearTimeout(timer)
  }, [remaining])

  const sendCode = async () => {
    if (sending || remaining > 0) return
    setSending(true)
    setError('')
    setMessage('')
    try {
      const response = await apiFetch(apiUrl('/api/auth/code'), { method: 'POST' })
      const result = await response.json()
      if (result.retryAfter) setRemaining(result.retryAfter)
      if (!response.ok) throw new Error(result.message || '发送失败，请稍后重试')
      setMessage('验证码已发送，请在企业微信中查看。5 分钟内有效。')
      setCode('')
      input.current?.focus()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '发送失败，请检查网络后重试')
    } finally {
      setSending(false)
    }
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (submitting || !/^[0-9]{6}$/.test(code)) return
    setSubmitting(true)
    setError('')
    try {
      const response = await apiFetch(apiUrl('/api/auth/login'), {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code }),
      })
      const result = await response.json()
      if (!response.ok) throw new Error(result.message || '登录失败，请重试')
      router.replace(destination())
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '登录失败，请检查网络后重试')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden bg-[#f4f7fb] px-5 py-10 text-slate-900 dark:bg-slate-950 dark:text-slate-100">
      <div aria-hidden className="pointer-events-none absolute -left-32 -top-40 h-[38rem] w-[38rem] rounded-full bg-blue-200/40 blur-3xl dark:bg-blue-900/20" />
      <div aria-hidden className="pointer-events-none absolute -bottom-40 -right-32 h-[34rem] w-[34rem] rounded-full bg-teal-100/60 blur-3xl dark:bg-teal-900/10" />
      <div className="relative grid w-full max-w-5xl overflow-hidden rounded-[2rem] border border-white/80 bg-white shadow-[0_24px_100px_-30px_rgba(30,64,175,0.25)] dark:border-slate-800 dark:bg-slate-900 lg:grid-cols-[0.95fr_1.05fr]">
        <section className="relative flex flex-col justify-between overflow-hidden bg-gradient-to-br from-blue-700 via-indigo-700 to-slate-900 p-8 text-white sm:p-12">
          <div aria-hidden className="absolute -bottom-28 -right-28 h-80 w-80 rounded-full border-[48px] border-white/5" />
          <div className="relative flex items-center gap-3"><span className="rounded-2xl bg-white/15 p-3"><BriefcaseBusiness size={26} /></span><span className="text-xl font-semibold tracking-tight">Get Jobs<span className="ml-2 text-sm font-normal text-blue-200">个人工作台</span></span></div>
          <div className="relative py-10 lg:py-20">
            <div className="mb-5 inline-flex items-center gap-2 rounded-full border border-white/20 bg-white/10 px-3 py-1 text-xs text-blue-100"><ShieldCheck size={14} />仅限本人访问</div>
            <h1 className="text-3xl font-semibold leading-tight sm:text-4xl">把机会交给自动化，<br /><span className="text-blue-200">把时间留给自己。</span></h1>
            <p className="mt-5 max-w-xs text-sm leading-7 text-blue-100/80">安全登录你的求职管理后台，继续管理招聘平台、投递任务和工作进度。</p>
          </div>
          <p className="relative flex items-center gap-2 text-xs text-blue-200/80"><LockKeyhole size={14} />独立后台会话 · 不影响招聘平台登录状态</p>
        </section>
        <section className="px-7 py-10 sm:px-12 sm:py-14">
          <div className="mb-7 inline-flex rounded-2xl bg-blue-50 p-3 text-blue-600 dark:bg-blue-950 dark:text-blue-300"><KeyRound size={26} /></div>
          <h2 className="text-2xl font-semibold tracking-tight">欢迎回来</h2>
          <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">使用企业微信验证码，安全进入工作台。</p>
          <form onSubmit={submit} className="mt-9 space-y-6">
            <div>
              <label htmlFor="login-code" className="mb-2 block text-sm font-medium">登录验证码</label>
              <div className="flex gap-3">
                <input ref={input} id="login-code" name="code" type="text" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required value={code} onChange={event => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))} placeholder="6 位数字" className="min-w-0 flex-1 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3.5 text-lg tracking-[0.2em] outline-none transition focus:border-blue-500 focus:ring-4 focus:ring-blue-500/10 dark:border-slate-700 dark:bg-slate-950" />
                <button type="button" onClick={sendCode} disabled={configured !== true || sending || submitting || remaining > 0} className="inline-flex w-32 shrink-0 items-center justify-center rounded-xl border border-blue-200 bg-blue-50 px-3 text-sm font-medium text-blue-700 transition hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-50 dark:border-blue-900 dark:bg-blue-950 dark:text-blue-300">{sending ? <Loader2 size={18} className="animate-spin" /> : remaining > 0 ? `${remaining}s 后重发` : '获取验证码'}</button>
              </div>
              <p className="mt-3 flex items-center gap-1.5 text-xs text-slate-400"><MessageSquareText size={14} />验证码将发送到你配置的企业微信群机器人</p>
            </div>
            {configured === false && <div role="alert" className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-800 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-200">登录服务尚未配置。请在服务器 <code>.env</code> 中填写 <code className="break-all">LOGIN_WECOM_WEBHOOK</code> 并重启后端。</div>}
            {message && <p role="status" className="flex items-start gap-2 text-sm leading-6 text-emerald-600"><CheckCircle2 size={18} className="mt-0.5 shrink-0" />{message}</p>}
            {error && <p role="alert" className="rounded-xl bg-red-50 p-3 text-sm leading-6 text-red-600 dark:bg-red-950 dark:text-red-300">{error}</p>}
            <button type="submit" disabled={configured !== true || submitting || sending || code.length !== 6} className="flex w-full items-center justify-center gap-2 rounded-xl bg-blue-600 py-3.5 font-medium text-white shadow-lg shadow-blue-600/15 transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50">{submitting ? <Loader2 size={18} className="animate-spin" /> : <>安全登录<ArrowRight size={18} /></>}</button>
          </form>
          <div className="mt-9 border-t border-slate-100 pt-6 text-xs leading-6 text-slate-400 dark:border-slate-800"><p>无需注册或设置密码，仅验证码接收者可登录。</p><p>验证码 5 分钟有效，登录会话最长保留 8 小时。</p></div>
        </section>
      </div>
    </main>
  )
}
