import type { CSSProperties } from 'react';
import {
  ArrowUpRight,
  BookOpen,
  ChartColumn,
  ChevronRight,
  ClipboardCheck,
  Clock3,
  Pause,
  RotateCcw,
  Sparkles,
} from 'lucide-react';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  Item,
  ItemActions,
  ItemContent,
  ItemDescription,
  ItemGroup,
  ItemMedia,
  ItemTitle,
} from '@/components/ui/item';
import { MiniWatchBars } from './RemoteWatchCharts';

type Props = {
  state: any;
  learning: any;
  study: any;
  days: any[];
  pendingCount: number;
  supervisionEnabled: boolean;
  mode?: 'unlock' | 'lock' | 'indefinite';
  untilShutdown: boolean;
  title: string;
  used: number;
  limit: number | null;
  blocked: boolean;
  unlimited: boolean;
  remaining: number;
  pending: boolean;
  onCancelOverride: () => void;
  onOpenLock: () => void;
};

export default function RemoteStatusCards({
  state,
  learning,
  study,
  days,
  pendingCount,
  supervisionEnabled,
  mode,
  untilShutdown,
  title,
  used,
  limit,
  blocked,
  unlimited,
  remaining,
  pending,
  onCancelOverride,
  onOpenLock,
}: Props) {
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <Card className="h-full gap-4 rounded-md border-sky-200/70 bg-sky-50/55 shadow-sm sm:col-span-2 xl:col-span-1 dark:border-sky-400/15 dark:bg-sky-400/[0.055]">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <Clock3 className="size-4 text-sky-600 dark:text-sky-400" />
              {supervisionEnabled ? '设备使用状态' : '今日学习进度'}
            </CardTitle>
            <CardDescription>
              <span className={`me-2 inline-block size-2 rounded-full ${blocked ? 'bg-rose-500' : 'bg-emerald-500'}`} />
              {title}
            </CardDescription>
            <CardAction>
              <Badge variant="outline" className="bg-background/70">
                {!supervisionEnabled ? '自主刷题' : mode ? '家庭遥控中' : '学习规则生效'}
              </Badge>
            </CardAction>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col justify-end">
            {supervisionEnabled ? (
              <>
                <p className="text-xs text-muted-foreground">
                  {unlimited
                    ? '管控状态'
                    : mode === 'lock'
                      ? '暂停剩余'
                      : mode === 'unlock'
                        ? untilShutdown
                          ? '临时解锁期限'
                          : '临时解锁剩余'
                        : '剩余可用时间'}
                </p>
                <div className="mt-2 flex items-end gap-2">
                  <strong className="text-3xl font-semibold tracking-tight tabular-nums">
                    {unlimited
                      ? '不限时'
                      : untilShutdown
                        ? '到本次关机'
                        : learning.free && !mode
                          ? '自由'
                          : blocked && !mode
                            ? '暂停'
                            : Math.ceil(remaining / 60)}
                  </strong>
                  {!unlimited && !untilShutdown && !(!mode && (learning.free || blocked)) && (
                    <span className="pb-1 text-sm text-muted-foreground">分钟</span>
                  )}
                </div>
              </>
            ) : (
              <>
                <p className="text-xs text-muted-foreground">今日已练习题目</p>
                <div className="mt-2 flex items-end gap-2">
                  <strong className="text-3xl font-semibold tracking-tight tabular-nums">
                    {study.todayAnswered || 0}
                  </strong>
                  <span className="pb-1 text-sm text-muted-foreground">
                    题
                    {study.todayAnswered > 0
                      ? ` · 正确率 ${Math.round(((study.todayCorrect || 0) * 100) / study.todayAnswered)}%`
                      : ''}
                  </span>
                </div>
              </>
            )}
          </CardContent>
          <CardFooter className="border-t border-sky-200/60 pt-4 dark:border-sky-400/10">
            {supervisionEnabled ? (
              <Button
                variant="outline"
                size="sm"
                className="bg-background/75"
                disabled={pending}
                onClick={() => (mode ? onCancelOverride() : onOpenLock())}
              >
                {mode ? <RotateCcw /> : <Pause />}
                {mode ? '恢复学习规则' : '暂停使用'}
              </Button>
            ) : (
              <Button
                variant="outline"
                size="sm"
                className="bg-background/75"
                onClick={() => (location.hash = '/records')}
              >
                <ChartColumn />
                查看学情提分报告
              </Button>
            )}
          </CardFooter>
        </Card>

        <Card className="h-full gap-4 rounded-md shadow-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <span className="flex size-5 items-center justify-center text-muted-foreground">
                <Clock3 className="size-4" />
              </span>
              今日已用
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col">
            <p className="text-2xl font-semibold tracking-tight tabular-nums">
              {Number(used.toFixed(1))}
              <span className="ms-1 text-sm font-normal text-muted-foreground">分钟</span>
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              {!supervisionEnabled || limit === null ? '每日时长不限' : `今日上限 ${limit} 分钟`}
            </p>
            <div className="mt-auto h-20 pt-3">
              {days.length > 0 && <MiniWatchBars days={days} />}
            </div>
          </CardContent>
        </Card>

        <Card className="h-full gap-4 rounded-md shadow-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <span className="flex size-5 items-center justify-center text-muted-foreground">
                <ClipboardCheck className="size-4" />
              </span>
              今日学习任务
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col items-center justify-between gap-3">
            <p className="self-start text-2xl font-semibold tracking-tight tabular-nums">
              {learning.roundsDoneToday || 0}
              <span className="ms-1 text-sm font-normal text-muted-foreground">
                / {state.config?.roundsPerDay || 0} 组
              </span>
            </p>
            <div
              className="dashboard-ring"
              style={
                {
                  '--completion': `${Math.min(
                    100,
                    ((learning.roundsDoneToday || 0) /
                      Math.max(1, state.config?.roundsPerDay || 1)) *
                      100,
                  )}%`,
                } as CSSProperties
              }
            >
              <div>
                <strong>
                  {Math.max(
                    0,
                    (state.config?.roundsPerDay || 0) - (learning.roundsDoneToday || 0),
                  )}
                </strong>
                <span>组待完成</span>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      <Card className="gap-0 shadow-sm">
        <CardHeader>
          <CardTitle className="text-sm">今日计划</CardTitle>
          <CardAction>
            <Button variant="ghost" size="sm" onClick={() => (location.hash = '/rules')}>
              调整
              <ArrowUpRight />
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent className="border-t p-0">
          <ItemGroup className="grid sm:grid-cols-2 lg:grid-cols-4">
            {[
              { icon: BookOpen, label: '每组题量', value: `${state.config?.questionsPerRound || 0} 题` },
              {
                icon: Clock3,
                label: supervisionEnabled ? '答题奖励' : '记忆模式',
                value: !supervisionEnabled
                  ? state.config?.memorizeFirst
                    ? '先背再测'
                    : '闪卡 + 闯关'
                  : state.config?.difficultyRewardEnabled
                    ? '按星级奖励'
                    : `${state.config?.sessionMinutes || 0} 分钟`,
              },
              {
                icon: ClipboardCheck,
                label: '待批改',
                value: `${pendingCount} 份`,
                href: '/grading',
              },
              {
                icon: Sparkles,
                label: supervisionEnabled ? '待完成加练' : '待攻克错题',
                value: `${supervisionEnabled ? learning.extraPool || 0 : study.wrongCount || 0} 题`,
                href: '/wrong',
              },
            ].map(item => (
              <Item
                key={item.label}
                size="sm"
                className="rounded-none border-b px-4 py-3 last:border-b-0 sm:border-e sm:last:border-e-0 lg:border-b-0"
              >
                <ItemMedia variant="icon">
                  <item.icon />
                </ItemMedia>
                <ItemContent className="min-w-0 gap-0.5">
                  <ItemDescription>{item.label}</ItemDescription>
                  <ItemTitle className="truncate">{item.value}</ItemTitle>
                </ItemContent>
                {item.href && (
                  <ItemActions>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="size-8"
                      aria-label={`查看${item.label}`}
                      onClick={() => (location.hash = item.href!)}
                    >
                      <ChevronRight />
                    </Button>
                  </ItemActions>
                )}
              </Item>
            ))}
          </ItemGroup>
        </CardContent>
      </Card>

      {pendingCount > 0 && (
        <Alert className="border-amber-200 bg-amber-50/55 dark:border-amber-400/20 dark:bg-amber-400/[0.055]">
          <ClipboardCheck className="text-amber-700 dark:text-amber-300" />
          <AlertDescription className="flex flex-wrap items-center gap-3">
            <span className="font-medium">{pendingCount} 份作业待批改</span>
            <Button
              size="sm"
              variant="outline"
              className="ms-auto bg-background/70"
              onClick={() => (location.hash = '/grading')}
            >
              去批改
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {!state?.questions && (
        <Alert className="border-amber-200 bg-amber-50/55 dark:border-amber-400/20 dark:bg-amber-400/[0.055]">
          <BookOpen className="text-amber-700 dark:text-amber-300" />
          <AlertDescription className="flex flex-wrap items-center gap-3">
            <span className="font-medium">题库还没有题目</span>
            <Button
              size="sm"
              variant="outline"
              className="ms-auto bg-background/70"
              onClick={() => (location.hash = '/bank')}
            >
              添加题目
            </Button>
          </AlertDescription>
        </Alert>
      )}
    </>
  );
}
