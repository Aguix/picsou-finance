import { useTranslation } from 'react-i18next'
import { Progress } from '@/components/ui/progress'
import type { GoalProgress } from '@/types/api'

interface GoalProgressBarProps {
  goal: GoalProgress
}

export function GoalProgressBar({ goal }: GoalProgressBarProps) {
  const { t } = useTranslation()

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between text-sm">
        <span className="font-medium">{goal.name}</span>
        <span className="text-muted-foreground">{Math.round(goal.percentComplete)}%</span>
      </div>
      <Progress value={goal.percentComplete} className="h-2" />
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span>{goal.isOnTrack ? t('goals.onTrack') : t('goals.behind')}</span>
        <span>
          {goal.monthsLeft} {t('goals.monthsLeft')}
        </span>
      </div>
    </div>
  )
}
