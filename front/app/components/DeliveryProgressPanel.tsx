'use client'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import type { DeliveryEvent, DeliveryProgress } from '@/lib/useDeliveryProgress'

const states: Record<string, string> = {
  idle: '等待开始', starting: '正在准备', running: '正在投递', stopping: '正在结束',
  stopped: '已结束', completed: '已完成', failed: '异常结束',
}
const eventStatus = (event: DeliveryEvent) => event.deliveryStatus || ({
  delivering: '正在投递', matched: '通过筛选', filtered: '已过滤', skipped: '未投递', error: '异常',
}[event.type] || '进行中')
const time = (timestamp: number) => new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
const safeLink = (link?: string) => link && /^https?:\/\//i.test(link) ? link : undefined

export function DeliveryTaskButton({ task, loggedIn }: { task: DeliveryProgress; loggedIn: boolean }) {
  if (task.isDelivering) return (
    <Button size="sm" onClick={task.stop} disabled={task.isStarting || task.isStopping}
      className="rounded-full bg-red-600 hover:bg-red-700 text-white px-4" aria-busy={task.isStarting || task.isStopping}>
      {task.isStarting ? '正在启动…' : task.isStopping ? '正在结束…' : '结束投递'}
    </Button>
  )
  return (
    <Button size="sm" onClick={task.start} disabled={!loggedIn || !task.statusKnown}
      className="rounded-full bg-teal-600 hover:bg-teal-700 text-white px-4">
      {!task.statusKnown ? '正在同步状态…' : !loggedIn ? '请先登录' : '开始投递'}
    </Button>
  )
}

export default function DeliveryProgressPanel({ task }: { task: DeliveryProgress }) {
  const { progress } = task
  const label = task.isStopping ? '正在结束' : task.isStarting ? '正在启动' : states[progress?.state || 'idle'] || '正在同步'
  const current = progress?.records[0]
  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle className="text-lg">本次投递进度</CardTitle>
          <span role="status" className="rounded-full bg-teal-500/10 px-3 py-1 text-sm">{label}</span>
        </div>
        <p className="text-xs text-muted-foreground">{task.connected ? '实时更新已连接' : '实时连接重连中，状态每秒自动同步'}</p>
      </CardHeader>
      <CardContent className="space-y-4">
        {task.notice && <div role="alert" aria-live="assertive" className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950/30 dark:text-amber-100">{task.notice}</div>}
        {task.error && <p role="alert" className="text-sm text-red-600">{task.error}</p>}
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[
            ['已发现岗位', progress?.found || 0], ['符合筛选条件', progress?.matched || 0],
            ['筛选排除', progress?.filtered || 0], ['已有投递结果', progress?.attempted || 0],
          ].map(([name, count]) => <div key={name} className="rounded-xl bg-muted/50 p-3">
            <p className="text-xs text-muted-foreground">{name}</p><p className="mt-1 text-2xl font-semibold tabular-nums">{count}</p>
          </div>)}
        </div>
        <p className="text-xs text-muted-foreground">岗位数量随搜索逐步累计，不同关键词可能重复。符合筛选条件表示通过岗位名称、工作内容规则；投递结果以平台实际反馈为准。</p>
        <div role="status" aria-live="polite" className="rounded-xl border p-3 text-sm space-y-1">
          <p>{task.isStopping ? '结束请求已发送，正在等待当前操作收尾并生成汇总。' : progress?.latest?.message || '开始投递后，这里会自动显示搜索与投递进度。'}</p>
          {current && <p className="text-muted-foreground">最近岗位：{current.company || '未知公司'} · {current.jobTitle || '未知岗位'} · {eventStatus(current)}</p>}
        </div>
        <div className="max-h-80 overflow-auto rounded-xl border">
          <table className="w-full text-left text-sm">
            <caption className="p-3 text-left font-medium">最近投递记录（最多100条）</caption>
            <thead className="sticky top-0 bg-background"><tr>{['时间', '公司', '岗位', '投递状态', '链接'].map(name => <th key={name} className="whitespace-nowrap px-3 py-2 font-medium">{name}</th>)}</tr></thead>
            <tbody>{progress?.records.map((record, index) => <tr key={`${record.jobLink}-${index}`} className="border-t">
              <td className="whitespace-nowrap px-3 py-2">{time(record.timestamp)}</td>
              <td className="px-3 py-2">{record.company || '—'}</td><td className="px-3 py-2">{record.jobTitle || '—'}</td>
              <td className="px-3 py-2"><p>{eventStatus(record)}</p><p className="text-xs text-muted-foreground">{record.message}</p></td>
              <td className="px-3 py-2">{safeLink(record.jobLink) ? <a className="text-teal-600 underline whitespace-nowrap" href={safeLink(record.jobLink)} target="_blank" rel="noopener noreferrer">查看岗位</a> : '—'}</td>
            </tr>)}</tbody>
          </table>
          {!progress?.records.length && <p className="p-4 text-sm text-muted-foreground">暂无投递记录</p>}
        </div>
        {!!progress?.events.length && <details>
          <summary className="cursor-pointer text-sm text-muted-foreground">搜索与筛选动态</summary>
          <ul className="mt-2 max-h-40 overflow-auto space-y-2 text-xs">{progress.events.map((event, index) => <li key={`${event.timestamp}-${index}`}>
            <span className="text-muted-foreground">{time(event.timestamp)}</span> {event.company} {event.jobTitle} {event.message}
          </li>)}</ul>
        </details>}
      </CardContent>
    </Card>
  )
}
