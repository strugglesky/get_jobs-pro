'use client'

import { useEffect, useState } from 'react'
import { BiFilter, BiSave } from 'react-icons/bi'
import PageHeader from '@/app/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { API_BASE } from '@/lib/api'

type Filters = {
  titleIncludes: string
  titleExcludes: string
  contentIncludes: string
  contentExcludes: string
}

const emptyFilters: Filters = {
  titleIncludes: '',
  titleExcludes: '',
  contentIncludes: '',
  contentExcludes: '',
}

const fields: { key: keyof Filters; label: string; placeholder: string }[] = [
  { key: 'titleIncludes', label: '岗位名称包含', placeholder: '例如：Java, 后端, 数据分析' },
  { key: 'titleExcludes', label: '岗位名称排除', placeholder: '例如：销售, 实习, 外包' },
  { key: 'contentIncludes', label: '工作内容包含', placeholder: '例如：Spring Boot, 机器学习, 数据建模' },
  { key: 'contentExcludes', label: '工作内容排除', placeholder: '例如：电话销售, 地推, 驻场' },
]

export default function DeliveryFilterPage() {
  const [filters, setFilters] = useState<Filters>(emptyFilters)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [reload, setReload] = useState(0)
  const [result, setResult] = useState<{ success: boolean; message: string } | null>(null)

  useEffect(() => {
    let active = true
    fetch(`${API_BASE}/api/delivery-filter`)
      .then(async response => {
        if (!response.ok) throw new Error('读取筛选配置失败')
        return response.json()
      })
      .then(data => {
        if (active) {
          setFilters(Object.fromEntries(fields.map(field => [field.key, data[field.key] ?? ''])) as Filters)
          setLoaded(true)
          setResult(null)
        }
      })
      .catch(error => { if (active) setResult({ success: false, message: error.message || '无法连接后端服务' }) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [reload])

  const save = async () => {
    setSaving(true)
    setResult(null)
    try {
      const response = await fetch(`${API_BASE}/api/delivery-filter`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(filters),
      })
      const data = await response.json()
      if (!response.ok || !data.success) throw new Error(data.message || '保存失败')
      setResult({ success: true, message: '筛选规则已保存，下一次投递任务生效。' })
    } catch (error) {
      setResult({ success: false, message: error instanceof Error ? error.message : '保存失败，请检查服务器连接。' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiFilter className="text-2xl" />}
        title="简历投递筛选"
        subtitle="为 Boss 直聘、猎聘、前程无忧和智联招聘设置共用规则"
        actions={<Button onClick={save} disabled={loading || saving || !loaded} size="sm"><BiSave className="mr-2" />{saving ? '正在保存' : '保存规则'}</Button>}
      />

      <Card>
        <CardHeader>
          <CardTitle>筛选规则</CardTitle>
          <CardDescription>各输入框可填写多个词，以逗号或换行分隔。包含规则配置多个词时命中任意一个即可；排除规则命中任意一个就跳过岗位。规则忽略英文大小写。</CardDescription>
          <p className="text-sm text-muted-foreground">名称与工作内容的规则需同时满足；留空表示不限制该项。内容规则启用后会读取岗位详情，读取失败时跳过该岗位。保存后下一次投递任务生效。</p>
        </CardHeader>
        <CardContent>
          {loading ? <p className="text-sm text-muted-foreground">正在读取筛选规则…</p> : (
            <div className="grid grid-cols-1 gap-5 md:grid-cols-2">
              {fields.map(field => (
                <div className="space-y-2" key={field.key}>
                  <Label htmlFor={field.key}>{field.label}</Label>
                  <Textarea
                    id={field.key}
                    rows={4}
                    disabled={saving || !loaded}
                    value={filters[field.key]}
                    placeholder={field.placeholder}
                    onChange={event => setFilters(current => ({ ...current, [field.key]: event.target.value }))}
                  />
                </div>
              ))}
            </div>
          )}
          {result && <p role="status" className={`mt-4 text-sm ${result.success ? 'text-green-600' : 'text-red-600'}`}>{result.message}</p>}
          {!loading && !loaded && <Button className="mt-3" variant="outline" onClick={() => { setLoading(true); setReload(value => value + 1) }}>重新加载</Button>}
        </CardContent>
      </Card>
    </div>
  )
}
