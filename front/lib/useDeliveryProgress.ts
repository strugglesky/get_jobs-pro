'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { API_BASE, apiFetch } from '@/lib/api'
import { createSSEWithBackoff } from '@/lib/sse'

export interface DeliveryEvent {
  type: string
  message: string
  timestamp: number
  company?: string
  jobTitle?: string
  jobLink?: string
  deliveryStatus?: string
}

export interface DeliverySnapshot {
  instanceId: string
  runId: string
  revision: number
  state: string
  found: number
  matched: number
  filtered: number
  attempted: number
  latest?: DeliveryEvent
  records: DeliveryEvent[]
  events: DeliveryEvent[]
}

type Pending = { action: 'start' | 'stop'; runId?: string; accepted: boolean; deadline: number }

export function useDeliveryProgress(platform: 'boss' | 'liepin' | '51job' | 'zhilian') {
  const [progress, setProgress] = useState<DeliverySnapshot | null>(null)
  const snapshot = useRef<DeliverySnapshot | null>(null)
  const [status, setStatus] = useState({ known: false, running: false, stopping: false })
  const [pending, setPending] = useState<Pending | null>(null)
  const pendingRef = useRef<Pending | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [connectionError, setConnectionError] = useState('')
  const [connected, setConnected] = useState(false)
  const alive = useRef(false)
  const epoch = useRef(0)
  const requests = useRef(new Set<AbortController>())
  const polling = useRef(false)

  const updatePending = useCallback((value: Pending | null) => {
    pendingRef.current = value
    setPending(value)
  }, [])

  const applySnapshot = useCallback((value: DeliverySnapshot) => {
    const previous = snapshot.current
    if (previous?.instanceId === value.instanceId && previous.revision > value.revision) return
    snapshot.current = value
    setProgress(value)
  }, [])

  const request = useCallback(async (path: string, method = 'GET') => {
    const controller = new AbortController()
    requests.current.add(controller)
    const timer = window.setTimeout(() => controller.abort(), 10000)
    try {
      const response = await apiFetch(`${API_BASE}${path}`, { method, cache: 'no-store', signal: controller.signal })
      const data = await response.json()
      if (!response.ok || data.success === false) throw new Error(data.message || `请求失败（${response.status}）`)
      return data
    } finally {
      window.clearTimeout(timer)
      requests.current.delete(controller)
    }
  }, [])

  const refresh = useCallback(async () => {
    if (polling.current) return
    polling.current = true
    const version = epoch.current
    try {
      const data = await request(`/api/jobs/delivery-status?platform=${platform}`)
      if (!alive.current || version !== epoch.current) return
      if (snapshot.current?.instanceId === data.progress.instanceId && snapshot.current!.revision > data.progress.revision) return
      applySnapshot(data.progress)
      setStatus({ known: true, running: data.isRunning, stopping: data.isStopping })
      setConnectionError('')
      const command = pendingRef.current
      if (command?.accepted) {
        if (command.action === 'stop' && !data.isRunning) {
          updatePending(null)
          setNotice(data.progress.state === 'failed' ? '投递任务异常结束，请查看下方进度记录。' : '投递已结束，本次投递汇总已生成。')
        }
        if (command.action === 'start') {
          if (data.isRunning || data.progress.runId !== command.runId) updatePending(null)
          else if (Date.now() > command.deadline) {
            updatePending(null)
            setError('尚未确认任务启动，请检查进度记录后重试。')
          }
        }
      }
    } catch {
      if (alive.current && version === epoch.current) setConnectionError('无法获取任务状态，正在重试，请检查服务器连接。')
    } finally {
      polling.current = false
    }
  }, [applySnapshot, platform, request, updatePending])

  useEffect(() => {
    alive.current = true
    void refresh()
    const timer = window.setInterval(() => void refresh(), 1000)
    const stream = createSSEWithBackoff(`${API_BASE}/api/jobs/delivery-progress/stream?platform=${platform}`, {
      listeners: [{ name: 'snapshot', handler: event => {
        if (!alive.current) return
        try { applySnapshot(JSON.parse(event.data)) } catch { void refresh() }
      } }],
      onOpen: () => { setConnected(true); void refresh() },
      onError: () => setConnected(false),
    })
    window.addEventListener('focus', refresh)
    const controllers = requests.current
    return () => {
      alive.current = false
      window.clearInterval(timer)
      stream.close()
      window.removeEventListener('focus', refresh)
      controllers.forEach(controller => controller.abort())
    }
  }, [applySnapshot, platform, refresh])

  const command = useCallback(async (action: 'start' | 'stop') => {
    if (pendingRef.current) return
    epoch.current++
    const operation: Pending = { action, runId: snapshot.current?.runId, accepted: false, deadline: Date.now() + 15000 }
    updatePending(operation)
    setError('')
    setNotice(action === 'stop' ? '正在结束投递，请等待当前岗位操作完成。' : '')
    try {
      await request(`/api/${platform}/${action}`, 'POST')
      if (!alive.current) return
      updatePending({ ...operation, accepted: true })
      if (action === 'stop') setNotice('结束投递请求已发送，正在等待任务收尾。')
    } catch (failure) {
      if (!alive.current) return
      updatePending(null)
      setStatus(value => ({ ...value, known: false }))
      setError(failure instanceof Error ? failure.message : '操作失败，请检查服务器连接。')
      if (action === 'stop') setNotice('结束投递失败，任务状态尚未确认，请稍后重试。')
    }
    if (alive.current) void refresh()
  }, [platform, refresh, request, updatePending])

  return {
    progress, error: error || connectionError, notice, connected, refresh,
    isDelivering: status.running || pending !== null,
    isStarting: pending?.action === 'start',
    isStopping: status.stopping || pending?.action === 'stop',
    statusKnown: status.known && !connectionError,
    start: () => command('start'),
    stop: () => command('stop'),
  }
}

export type DeliveryProgress = ReturnType<typeof useDeliveryProgress>
