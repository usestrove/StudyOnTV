import {
  AlertTriangle,
  CalendarClock,
  Flame,
  GraduationCap,
  ListChecks,
  RefreshCw,
  Sparkles,
  Target,
  TrendingDown,
  TrendingUp,
} from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { accuracyTone } from './record-metrics';

type Props = {
  pending: boolean;
  refreshing: boolean;
  streakDays: number;
  totalAnsweredAll: number;
  overallAccuracy: number | null;
  accuracyDelta: number | null;
  dueCount: number;
  wrongCount: number;
  onRefresh: () => void;
  onPracticeWrong: () => void;
};

export default function RecordsOverviewCard({
  pending,
  refreshing,
  streakDays,
  totalAnsweredAll,
  overallAccuracy,
  accuracyDelta,
  dueCount,
  wrongCount,
  onRefresh,
  onPracticeWrong,
}: Props) {
  return (
    <Card className="gap-0 overflow-hidden rounded-md py-0 shadow-sm" aria-labelledby="records-summary-title">
      <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
        <CardTitle id="records-summary-title" className="flex items-center gap-2 text-sm">
          <GraduationCap className="size-4 text-primary" />
          学情提分总览
        </CardTitle>
        <CardAction className="flex items-center gap-1.5">
          {streakDays > 0 && (
            <Badge variant="secondary" className="gap-1 font-normal">
              <Flame className="size-3.5 text-amber-500" />
              连学 {streakDays} 天
            </Badge>
          )}
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="size-7"
            onClick={onRefresh}
            disabled={refreshing}
            aria-label="刷新成长记录"
          >
            <RefreshCw className={refreshing ? 'animate-spin' : ''} />
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="p-0">
        <dl className="grid grid-cols-2 divide-x divide-y divide-border/70 sm:grid-cols-4 sm:divide-y-0">
          <div className="min-w-0 px-4 py-4 sm:px-5">
            <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <ListChecks className="size-3.5 shrink-0" />
              <span>累计答题</span>
            </dt>
            <dd className="mt-2.5 flex items-end gap-1 tabular-nums">
              {pending ? (
                <Skeleton className="h-7 w-16" />
              ) : (
                <>
                  <span className="text-2xl font-semibold leading-none tracking-tight">
                    {Math.round(totalAnsweredAll)}
                  </span>
                  <span className="pb-0.5 text-xs text-muted-foreground">题</span>
                </>
              )}
            </dd>
          </div>

          <div className="min-w-0 px-4 py-4 sm:px-5">
            <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <Target className="size-3.5 shrink-0" />
              <span>总正确率</span>
            </dt>
            <dd className="mt-2.5 flex flex-wrap items-end gap-1.5 tabular-nums">
              {pending ? (
                <Skeleton className="h-7 w-20" />
              ) : overallAccuracy === null ? (
                <span className="text-2xl font-semibold leading-none text-muted-foreground">—</span>
              ) : (
                <>
                  <span
                    className={`text-2xl font-semibold leading-none tracking-tight ${accuracyTone(overallAccuracy, totalAnsweredAll > 0).text}`}
                  >
                    {overallAccuracy}%
                  </span>
                  {accuracyDelta !== null && accuracyDelta !== 0 && (
                    <span
                      title="近 7 天对比前 7 天正确率变化"
                      className={`inline-flex items-center gap-0.5 rounded px-1.5 py-0.5 text-[11px] font-medium ${
                        accuracyDelta > 0
                          ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400'
                          : 'bg-rose-500/10 text-rose-600 dark:text-rose-400'
                      }`}
                    >
                      {accuracyDelta > 0 ? <TrendingUp className="size-3" /> : <TrendingDown className="size-3" />}
                      {accuracyDelta > 0 ? `+${accuracyDelta}%` : `${accuracyDelta}%`}
                    </span>
                  )}
                </>
              )}
            </dd>
          </div>

          <div className="min-w-0 px-4 py-4 sm:px-5">
            <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <CalendarClock className="size-3.5 shrink-0" />
              <span>今日待复习</span>
            </dt>
            <dd className="mt-2.5 flex items-end gap-1 tabular-nums">
              {pending ? (
                <Skeleton className="h-7 w-14" />
              ) : (
                <>
                  <span className="text-2xl font-semibold leading-none tracking-tight">{dueCount}</span>
                  <span className="pb-0.5 text-xs text-muted-foreground">题到期</span>
                </>
              )}
            </dd>
          </div>

          <div className="min-w-0 px-4 py-4 sm:px-5">
            <dt className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <AlertTriangle className="size-3.5 shrink-0" />
              <span>待攻克错题</span>
            </dt>
            <dd className="mt-2.5 flex items-center justify-between gap-2 tabular-nums">
              <div className="flex items-end gap-1">
                {pending ? (
                  <Skeleton className="h-7 w-14" />
                ) : (
                  <>
                    <span
                      className={`text-2xl font-semibold leading-none tracking-tight ${
                        wrongCount > 0 ? 'text-rose-600 dark:text-rose-400' : 'text-emerald-600 dark:text-emerald-400'
                      }`}
                    >
                      {wrongCount}
                    </span>
                    <span className="pb-0.5 text-xs text-muted-foreground">道</span>
                  </>
                )}
              </div>
              {wrongCount > 0 && (
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="h-7 px-2 text-xs"
                  onClick={onPracticeWrong}
                >
                  <Sparkles className="size-3 text-primary" />
                  变式巩固
                </Button>
              )}
            </dd>
          </div>
        </dl>
      </CardContent>
    </Card>
  );
}
