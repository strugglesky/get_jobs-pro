'use client'

import { ReactNode, useEffect, useState } from 'react'
import { usePathname, useRouter } from 'next/navigation'
import { Loader2, RefreshCw } from 'lucide-react'
import { apiFetch, apiUrl } from '@/lib/api'
import Sidebar from './Sidebar'
import ContentArea from './ContentArea'

export default function AuthGate({ children }: { children: ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()
  const [authenticated, setAuthenticated] = useState(false)
  const [error, setError] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (pathname === '/login') return
    const controller = new AbortController()
    const redirect = () => {
      setAuthenticated(false)
      router.replace(`/login?next=${encodeURIComponent(pathname)}`)
    }
    const check = async () => {
      try {
        const response = await apiFetch(apiUrl('/api/auth/session'), { signal: controller.signal })
        if (!response.ok) throw new Error('Session check failed')
        const session = await response.json()
        if (controller.signal.aborted) return
        setError(false)
        if (session.authenticated) setAuthenticated(true)
        else redirect()
      } catch {
        if (!controller.signal.aborted) {
          setAuthenticated(false)
          setError(true)
        }
      }
    }
    void check()
    const timer = window.setInterval(check, 60_000)
    window.addEventListener('getjobs:unauthorized', redirect)
    window.addEventListener('focus', check)
    return () => {
      controller.abort()
      window.clearInterval(timer)
      window.removeEventListener('getjobs:unauthorized', redirect)
      window.removeEventListener('focus', check)
    }
  }, [pathname, router, attempt])

  if (pathname === '/login') return <>{children}</>
  if (!authenticated) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-slate-50 text-slate-600 dark:bg-slate-950 dark:text-slate-300">
        <div className="flex flex-col items-center gap-4" role="status">
          {error ? <RefreshCw size={28} /> : <Loader2 size={28} className="animate-spin" />}
          <p>{error ? '暂时无法连接服务，请检查网络或后端状态' : '正在确认登录状态…'}</p>
          {error && <button className="rounded-lg bg-blue-600 px-5 py-2 text-white" onClick={() => setAttempt(value => value + 1)}>重新连接</button>}
        </div>
      </main>
    )
  }
  return <div className="flex min-h-screen"><Sidebar /><ContentArea>{children}</ContentArea></div>
}
