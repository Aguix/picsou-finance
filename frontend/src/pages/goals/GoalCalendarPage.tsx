import { useState, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useGoal, useGoalMonths, useSetMonthOverride, useDeleteMonthOverride, useSetManualContribution, useDeleteManualContribution } from '@/features/goals/hooks'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { PageHeader } from '@/components/shared/PageHeader'
import { ErrorState } from '@/components/shared/ErrorState'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Separator } from '@/components/ui/separator'
import { HugeiconsIcon } from '@hugeicons/react'
import { ArrowLeft01Icon, Calendar03Icon, GridViewIcon, TimeManagementIcon, Loading01Icon } from '@hugeicons/core-free-icons'
import type { GoalMonthEntry } from '@/types/api'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const MONTH_ABBR = ['Jan', 'Fév', 'Mar', 'Avr', 'Mai', 'Jun', 'Jul', 'Aoû', 'Sep', 'Oct', 'Nov', 'Déc']

function isPastOrCurrent(ym: string): boolean {
  const now = new Date()
  const [y, m] = ym.split('-').map(Number)
  return y < now.getFullYear() || (y === now.getFullYear() && m <= now.getMonth() + 1)
}

function monthAbbr(ym: string): string {
  return MONTH_ABBR[parseInt(ym.split('-')[1]) - 1]
}

function fullMonthName(ym: string): string {
  const [year, month] = ym.split('-')
  return new Date(parseInt(year), parseInt(month) - 1, 1)
    .toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })
}

function groupByYear(months: GoalMonthEntry[]): { year: number; entries: GoalMonthEntry[] }[] {
  const map = new Map<number, GoalMonthEntry[]>()
  for (const e of months) {
    const year = parseInt(e.yearMonth.split('-')[0])
    if (!map.has(year)) map.set(year, [])
    map.get(year)!.push(e)
  }
  return Array.from(map.entries())
    .sort(([a], [b]) => a - b)
    .map(([year, entries]) => ({ year, entries }))
}

// ---------------------------------------------------------------------------
// Progress Ring
// ---------------------------------------------------------------------------

const COLORS = {
  success: 'hsl(var(--primary))',
  warning: 'hsl(var(--primary) / 0.6)',
  destructive: 'hsl(var(--destructive) / 0.7)',
  muted: 'hsl(var(--muted))',
  mutedFg: 'hsl(var(--muted-foreground))',
} as const

function ProgressRing({ pct, color, size = 60, stroke = 5 }: {
  pct: number; color: string; size?: number; stroke?: number
}) {
  const r = (size - stroke) / 2
  const circ = 2 * Math.PI * r
  const filled = Math.min(1, Math.max(0, pct)) * circ
  return (
    <svg width={size} height={size} style={{ transform: 'rotate(-90deg)' }}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="hsl(var(--border))" strokeWidth={stroke} />
      <circle
        cx={size / 2} cy={size / 2} r={r} fill="none"
        stroke={color} strokeWidth={stroke}
        strokeDasharray={`${filled} ${circ}`}
        strokeLinecap="round"
      />
    </svg>
  )
}

function getProgressColor(entry: GoalMonthEntry, isPast: boolean): { color: string; pct: number; label: string; textColor: string } {
  if (!isPast) return { color: COLORS.muted, pct: 0, label: '···', textColor: COLORS.mutedFg }
  if (entry.effective == null) return { color: COLORS.muted, pct: 0, label: '–', textColor: COLORS.mutedFg }
  const obj = entry.objective ?? 0
  const ratio = obj > 0 ? entry.effective / obj : 1
  if (ratio >= 1) return { color: COLORS.success, pct: ratio, label: `${Math.round(ratio * 100)}%`, textColor: COLORS.success }
  if (ratio >= 0.6) return { color: COLORS.warning, pct: ratio, label: `${Math.round(ratio * 100)}%`, textColor: COLORS.warning }
  return { color: COLORS.destructive, pct: ratio, label: `${Math.round(ratio * 100)}%`, textColor: COLORS.destructive }
}

// ---------------------------------------------------------------------------
// View: Year Grid (default)
// ---------------------------------------------------------------------------

function YearGridView({ months, selectedYm, onSelect }: {
  months: GoalMonthEntry[]; selectedYm: string | null; onSelect: (ym: string) => void
}) {
  const years = useMemo(() => groupByYear((months ?? []).filter(e => isPastOrCurrent(e.yearMonth))), [months])

  return (
    <div className="space-y-4">
      {years.map(({ year, entries }) => (
        <Card key={year}>
          <CardHeader className="pb-2">
            <CardTitle className="text-xs font-bold tracking-wider uppercase text-muted-foreground">{year}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-6 gap-x-3 gap-y-4">
              {entries.map(entry => {
                const isSelected = selectedYm === entry.yearMonth
                const hasOverride = entry.override != null
                const hasManual = entry.manualActual != null
                const { color, pct, label, textColor } = getProgressColor(entry, true)

                return (
                  <button
                    key={entry.yearMonth}
                    onClick={() => onSelect(entry.yearMonth)}
                    className="flex flex-col items-center gap-1 cursor-pointer transition-opacity hover:opacity-80"
                  >
                    <div className="relative">
                      {isSelected && <div className="absolute inset-[-4px] rounded-full ring-2 ring-primary ring-offset-2 ring-offset-background" />}
                      {hasOverride && !hasManual && <div className="absolute -top-0.5 -right-0.5 w-2 h-2 rounded-full bg-violet-500 border border-background" />}
                      {hasManual && <div className="absolute -top-0.5 -right-0.5 w-2 h-2 rounded-full bg-blue-500 border border-background" />}
                      <ProgressRing pct={pct} color={color} size={52} stroke={5} />
                      <div className="absolute inset-0 flex items-center justify-center">
                        <span className="text-[11px] font-bold" style={{ color: textColor }}>{label}</span>
                      </div>
                    </div>
                    <span className="text-xs font-medium">{monthAbbr(entry.yearMonth)}</span>
                    <span className="text-xs text-muted-foreground">
                      {entry.effective != null ? <CurrencyDisplay value={entry.effective} className="text-xs" /> : '—'}
                    </span>
                  </button>
                )
              })}
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// View: Timeline
// ---------------------------------------------------------------------------

function TimelineView({ months, selectedYm, onSelect }: {
  months: GoalMonthEntry[]; selectedYm: string | null; onSelect: (ym: string) => void
}) {
  const sorted = useMemo(() =>
    [...(months ?? [])].filter(e => isPastOrCurrent(e.yearMonth)).reverse(),
    [months]
  )

  return (
    <Card>
      <CardContent className="p-4 space-y-1">
        {sorted.map(entry => {
          const isSelected = selectedYm === entry.yearMonth
          const obj = entry.objective ?? 0
          const eff = entry.effective ?? 0
          const pct = obj > 0 ? Math.min(100, (eff / obj) * 100) : (eff > 0 ? 100 : 0)
          const barClass = pct >= 100 ? 'bg-primary' : pct >= 60 ? 'bg-primary/60' : 'bg-destructive/50'

          return (
            <button
              key={entry.yearMonth}
              onClick={() => onSelect(entry.yearMonth)}
              className={`w-full flex items-center gap-3 rounded-md px-3 py-2.5 text-left transition-colors ${isSelected ? 'bg-accent' : 'hover:bg-accent/50'}`}
            >
              <span className="w-28 shrink-0 text-sm font-medium">{fullMonthName(entry.yearMonth)}</span>
              <div className="flex-1 h-2 rounded-full bg-muted overflow-hidden">
                <div className={`h-full rounded-full transition-all ${barClass}`} style={{ width: `${pct}%` }} />
              </div>
              <span className="w-12 shrink-0 text-sm font-bold text-right">
                {entry.effective != null ? `${Math.round(pct)}%` : '—'}
              </span>
              <span className="w-20 shrink-0 text-right text-sm text-muted-foreground">
                {entry.effective != null ? <CurrencyDisplay value={entry.effective} /> : ''}
              </span>
              {entry.manualActual != null && (
                <Badge variant="secondary" className="text-[10px] px-1.5 py-0">manu.</Badge>
              )}
              {entry.override != null && (
                <Badge variant="outline" className="text-[10px] px-1.5 py-0 text-violet-500 border-violet-300">modif.</Badge>
              )}
            </button>
          )
        })}
      </CardContent>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// View: Calendar Grid
// ---------------------------------------------------------------------------

function CalendarGridView({ months, selectedYm, onSelect }: {
  months: GoalMonthEntry[]; selectedYm: string | null; onSelect: (ym: string) => void
}) {
  const years = useMemo(() => groupByYear(months), [months])

  return (
    <div className="space-y-4">
      {years.map(({ year, entries }) => (
        <Card key={year}>
          <CardHeader className="pb-2">
            <CardTitle className="text-xs font-bold tracking-wider uppercase text-muted-foreground">{year}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-4 gap-2">
              {entries.map(entry => {
                const isSelected = selectedYm === entry.yearMonth
                const isPast = isPastOrCurrent(entry.yearMonth)
                const { pct, label, textColor } = getProgressColor(entry, isPast)

                return (
                  <button
                    key={entry.yearMonth}
                    onClick={() => onSelect(entry.yearMonth)}
                    className={`rounded-lg border p-3 text-left transition-colors ${isSelected ? 'border-primary bg-accent' : 'border-border hover:bg-accent/50'}`}
                  >
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-sm font-medium">{monthAbbr(entry.yearMonth)}</span>
                      <span className="text-xs font-bold" style={{ color: textColor }}>{label}</span>
                    </div>
                    {entry.effective != null ? (
                      <div>
                        <div className="h-1.5 rounded-full bg-muted overflow-hidden mb-1">
                          <div className="h-full rounded-full bg-primary/70 transition-all" style={{ width: `${Math.min(100, pct * 100)}%` }} />
                        </div>
                        <div className="flex justify-between text-[11px] text-muted-foreground">
                          <span>obj. <CurrencyDisplay value={entry.objective} className="text-[11px]" /></span>
                          <span><CurrencyDisplay value={entry.effective} className="text-[11px]" /></span>
                        </div>
                      </div>
                    ) : (
                      <p className="text-xs text-muted-foreground">—</p>
                    )}
                    {(entry.manualActual != null || entry.override != null) && (
                      <div className="flex gap-1 mt-1.5">
                        {entry.manualActual != null && <Badge variant="secondary" className="text-[9px] px-1 py-0">manu.</Badge>}
                        {entry.override != null && <Badge variant="outline" className="text-[9px] px-1 py-0 text-violet-500 border-violet-300">modif.</Badge>}
                      </div>
                    )}
                  </button>
                )
              })}
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Month Detail Panel
// ---------------------------------------------------------------------------

function MonthDetailPanel({ goalId, entry, onClose }: {
  goalId: number; entry: GoalMonthEntry; onClose: () => void
}) {
  const { t } = useTranslation()
  const setOverride = useSetMonthOverride()
  const deleteOverride = useDeleteMonthOverride()
  const setManual = useSetManualContribution()
  const deleteManual = useDeleteManualContribution()

  const [overrideValue, setOverrideValue] = useState(entry.override?.toString() ?? '')
  const [manualValue, setManualValue] = useState(entry.manualActual?.toString() ?? '')

  const busy = setOverride.isPending || deleteOverride.isPending || setManual.isPending || deleteManual.isPending

  return (
    <Card className="mt-4">
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <CardTitle className="text-base capitalize">{fullMonthName(entry.yearMonth)}</CardTitle>
            {entry.override != null && (
              <Badge variant="outline" className="text-[10px] text-violet-500 border-violet-300">{t('goals.modified')}</Badge>
            )}
            {entry.manualActual != null && (
              <Badge variant="secondary" className="text-[10px]">{t('goals.declared')}</Badge>
            )}
          </div>
          <Button variant="ghost" size="icon" onClick={onClose}>
            &times;
          </Button>
        </div>
      </CardHeader>
      <Separator />
      <CardContent className="pt-4">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* Objective override */}
          <div className="space-y-3">
            <Label>{t('goals.monthlyObjective')}</Label>
            <p className="text-sm text-muted-foreground">
              {t('goals.calculatedObjective')}: <CurrencyDisplay value={entry.objective} />
            </p>
            <div className="relative">
              <Input
                type="number"
                step="0.01"
                min="0"
                value={overrideValue}
                onChange={e => setOverrideValue(e.target.value)}
                placeholder={String(entry.objective)}
                className="pr-8"
              />
              <span className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground pointer-events-none">€</span>
            </div>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setOverrideValue(String(entry.objective))}
              >
                {t('goals.calculatedObjective')}
              </Button>
              <Button
                size="sm"
                disabled={busy}
                onClick={() => {
                  const amount = parseFloat(overrideValue)
                  if (isNaN(amount) || amount < 0) return
                  setOverride.mutate({ id: goalId, ym: entry.yearMonth, amount })
                }}
              >
                {setOverride.isPending && <HugeiconsIcon icon={Loading01Icon} strokeWidth={2} className="size-3 animate-spin" />}
                {t('goals.saveOverride')}
              </Button>
              {entry.override != null && (
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  onClick={() => deleteOverride.mutate({ id: goalId, ym: entry.yearMonth })}
                >
                  {t('goals.resetOverride')}
                </Button>
              )}
            </div>
          </div>

          {/* Manual contribution */}
          <div className="space-y-3">
            <Label>{t('goals.actual')}</Label>
            <p className="text-sm text-muted-foreground">
              {t('goals.fromAccounts')}: {entry.actual != null ? <CurrencyDisplay value={entry.actual} /> : t('goals.noData')}
            </p>
            <div className="relative">
              <Input
                type="number"
                step="0.01"
                min="0"
                value={manualValue}
                onChange={e => setManualValue(e.target.value)}
                placeholder={entry.actual?.toString() ?? ''}
                className="pr-8"
              />
              <span className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground pointer-events-none">€</span>
            </div>
            <div className="flex gap-2">
              {entry.actual != null && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setManualValue(entry.actual!.toString())}
                >
                  {t('goals.fromAccounts')}
                </Button>
              )}
              <Button
                size="sm"
                disabled={busy}
                onClick={() => {
                  const amount = parseFloat(manualValue)
                  if (isNaN(amount) || amount < 0) return
                  setManual.mutate({ id: goalId, ym: entry.yearMonth, amount })
                }}
              >
                {setManual.isPending && <HugeiconsIcon icon={Loading01Icon} strokeWidth={2} className="size-3 animate-spin" />}
                {t('goals.saveManual')}
              </Button>
              {entry.manualActual != null && (
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  onClick={() => deleteManual.mutate({ id: goalId, ym: entry.yearMonth })}
                >
                  {t('goals.resetManual')}
                </Button>
              )}
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// Goal Calendar Page
// ---------------------------------------------------------------------------

export function GoalCalendarPage() {
  const { id } = useParams<{ id: string }>()
  const goalId = Number(id)
  const navigate = useNavigate()
  const { t } = useTranslation()

  const { data: goal, isLoading: goalLoading, error: goalError } = useGoal(goalId)
  const { data: months, isLoading: monthsLoading } = useGoalMonths(goalId)

  const [viewMode, setViewMode] = useState<'grid' | 'timeline' | 'calendar'>('grid')
  const [selectedYm, setSelectedYm] = useState<string | null>(null)

  const selectedEntry = selectedYm ? (months ?? []).find(e => e.yearMonth === selectedYm) ?? null : null

  if (goalLoading || monthsLoading) {
    return (
      <div className="space-y-6">
        <PageHeader title={t('goals.calendar')} />
        <Skeleton className="h-9 w-72" />
        <Card>
          <CardContent className="p-6">
            <div className="grid grid-cols-6 gap-3">
              {Array.from({ length: 12 }).map((_, i) => (
                <div key={i} className="flex flex-col items-center gap-1">
                  <Skeleton className="size-[52px] rounded-full" />
                  <Skeleton className="h-3 w-8" />
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>
    )
  }

  if (goalError || !goal) {
    return (
      <div className="space-y-6">
        <PageHeader title={t('goals.calendar')} />
        <ErrorState message={goalError?.message ?? t('common.notFound')} onRetry={() => window.location.reload()} />
      </div>
    )
  }

  const pastMonths = (months ?? []).filter(e => isPastOrCurrent(e.yearMonth))
  const achievedCount = pastMonths.filter(
    e => e.effective != null && e.objective != null && e.effective >= e.objective
  ).length

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('goals.calendarTitle', { name: goal.name })}
        actions={
          <Button variant="outline" size="sm" onClick={() => navigate('/goals')}>
            <HugeiconsIcon icon={ArrowLeft01Icon} strokeWidth={2} className="size-4" />
            {t('goals.title')}
          </Button>
        }
      />

      {/* Summary bar */}
      <div className="flex items-center gap-3 text-sm">
        <span className="text-muted-foreground">
          {t('goals.monthlyObjective')}: <CurrencyDisplay value={goal.monthlyNeeded} />
        </span>
        {pastMonths.length > 0 && (
          <Badge variant="secondary">
            {achievedCount}/{pastMonths.length} {t('goals.achieved')}
          </Badge>
        )}
      </div>

      {/* View toggle with shadcn Tabs */}
      <Tabs value={viewMode} onValueChange={(v) => setViewMode(v as typeof viewMode)}>
        <TabsList>
          <TabsTrigger value="grid" className="gap-1.5">
            <HugeiconsIcon icon={GridViewIcon} strokeWidth={2} className="size-4" />
            <span className="hidden sm:inline">{t('goals.viewGrid')}</span>
          </TabsTrigger>
          <TabsTrigger value="timeline" className="gap-1.5">
            <HugeiconsIcon icon={TimeManagementIcon} strokeWidth={2} className="size-4" />
            <span className="hidden sm:inline">{t('goals.viewTimeline')}</span>
          </TabsTrigger>
          <TabsTrigger value="calendar" className="gap-1.5">
            <HugeiconsIcon icon={Calendar03Icon} strokeWidth={2} className="size-4" />
            <span className="hidden sm:inline">{t('goals.viewCalendar')}</span>
          </TabsTrigger>
        </TabsList>
      </Tabs>

      {/* Active view */}
      {viewMode === 'grid' && (
        <YearGridView months={months ?? []} selectedYm={selectedYm} onSelect={setSelectedYm} />
      )}
      {viewMode === 'timeline' && (
        <TimelineView months={months ?? []} selectedYm={selectedYm} onSelect={setSelectedYm} />
      )}
      {viewMode === 'calendar' && (
        <CalendarGridView months={months ?? []} selectedYm={selectedYm} onSelect={setSelectedYm} />
      )}

      {/* Month detail panel */}
      {selectedEntry && (
        <MonthDetailPanel goalId={goalId} entry={selectedEntry} onClose={() => setSelectedYm(null)} />
      )}

      {!selectedEntry && (
        <p className="text-center text-sm text-muted-foreground">{t('goals.clickMonthHint')}</p>
      )}
    </div>
  )
}
