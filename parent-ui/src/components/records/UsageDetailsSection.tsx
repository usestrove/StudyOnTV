import { ChartColumn, Clock3, History, ListChecks } from 'lucide-react';
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { FieldDescription } from '@/components/ui/field';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  accuracyTone,
  clampPercent,
  eventLabel,
  eventTone,
  formatDuration,
  formatEventTime,
  toNumber,
  type EventItem,
  type HistoryDay,
} from './record-metrics';

function ListSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div className="divide-y divide-border/70" aria-label="正在读取记录">
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="flex items-center gap-3 px-4 py-3.5 sm:px-5">
          <Skeleton className="size-7 shrink-0 rounded-full" />
          <div className="flex min-w-0 flex-1 items-center gap-3">
            <Skeleton className="h-4 w-28" />
            <Skeleton className="ms-auto h-4 w-16" />
          </div>
        </div>
      ))}
    </div>
  );
}

type Props = {
  days: HistoryDay[];
  recentEvents: EventItem[];
  totalWatch: number;
  pending: boolean;
  historyPending: boolean;
  historyError: boolean;
  eventsPending: boolean;
  eventsError: boolean;
};

export default function UsageDetailsSection({
  days,
  recentEvents,
  totalWatch,
  pending,
  historyPending,
  historyError,
  eventsPending,
  eventsError,
}: Props) {
  const duration = formatDuration(totalWatch);
  const averageWatch = days.length ? totalWatch / days.length : 0;
  const chartDays = days
    .slice()
    .reverse()
    .map(day => ({
      ...day,
      label: day.label || '—',
      watch: Math.max(0, toNumber(day.watch)),
      answered: Math.max(0, toNumber(day.answered)),
      accuracy: clampPercent(day.accuracy),
    }));
  const chartMax = Math.max(40, Math.ceil(Math.max(1, ...chartDays.map(day => day.watch)) / 10) * 10);
  const chartTicks = Array.from({ length: 5 }, (_, index) => Math.round(chartMax - (chartMax / 4) * index));

  return (
    <>
      <Card className="gap-0 overflow-hidden py-0 shadow-sm">
        <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
          <CardTitle className="flex items-center gap-2 text-sm">
            <Clock3 className="size-4 text-muted-foreground" />
            近 7 天屏幕使用趋势
          </CardTitle>
          <CardDescription className="col-span-full text-xs">
            累计使用 {duration.value} {duration.unit}（单位：分钟）
          </CardDescription>
          <CardAction>
            {pending ? (
              <Skeleton className="h-5 w-24" />
            ) : (
              <span className="inline-flex items-baseline gap-1 text-xs text-muted-foreground">
                日均<b className="font-medium tabular-nums">{Math.round(averageWatch)}</b>分钟
              </span>
            )}
          </CardAction>
        </CardHeader>
        <CardContent className="px-4 pb-5 pt-5 sm:px-5">
          {pending ? (
            <div className="flex h-64 items-end gap-3 border-b border-border/70 px-4 pb-1 sm:h-72">
              {Array.from({ length: 7 }, (_, index) => (
                <Skeleton
                  key={index}
                  className="min-h-3 flex-1 rounded-t-md rounded-b-none"
                  style={{ height: `${22 + ((index * 19) % 62)}%` }}
                />
              ))}
            </div>
          ) : chartDays.length ? (
            <div
              className="grid h-64 grid-cols-[30px_minmax(0,1fr)] gap-2 sm:h-72"
              role="img"
              aria-label="最近 7 天每日使用时长柱状图"
            >
              <div className="flex flex-col justify-between pb-7 pt-1 text-right text-xs tabular-nums text-muted-foreground">
                {chartTicks.map(tick => (
                  <span key={tick}>{tick}</span>
                ))}
              </div>
              <div className="relative min-w-0 pb-7">
                <div className="pointer-events-none absolute inset-x-0 bottom-7 top-1 flex flex-col justify-between">
                  {chartTicks.map(tick => (
                    <span key={tick} className="border-t border-dashed border-border/70" />
                  ))}
                </div>
                <div className="relative z-10 flex h-[calc(100%-1.75rem)] items-end justify-around gap-2">
                  {chartDays.map((day, index) => (
                    <div
                      key={`${day.label}-${index}`}
                      className="group relative flex h-full min-w-0 flex-1 flex-col items-center justify-end"
                      title={`${day.label}：${day.watch.toFixed(1)} 分钟，${day.answered} 题，正确率 ${day.accuracy}%`}
                    >
                      <div className="relative flex w-full max-w-9 flex-1 items-end overflow-hidden rounded-t-md bg-muted/65">
                        <span
                          className="block w-full rounded-t-md bg-primary transition-[height] duration-500 group-hover:bg-primary/85"
                          style={{
                            height: `${(day.watch / chartMax) * 100}%`,
                            minHeight: day.watch > 0 ? 5 : 0,
                          }}
                        />
                      </div>
                      <span className="absolute -bottom-7 whitespace-nowrap text-xs text-muted-foreground">
                        {day.label}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          ) : historyError ? (
            <div className="flex flex-col items-center gap-2 py-14 text-center">
              <ChartColumn className="size-5 text-destructive/70" />
              <FieldDescription className="text-destructive">无法读取使用趋势</FieldDescription>
            </div>
          ) : (
            <div className="flex flex-col items-center gap-2 py-14 text-center">
              <ChartColumn className="size-5 text-muted-foreground/70" />
              <FieldDescription>暂无使用趋势</FieldDescription>
            </div>
          )}
        </CardContent>
      </Card>

      <Card className="gap-0 overflow-hidden py-0 shadow-sm">
        <Tabs defaultValue="answers" className="gap-0">
          <CardHeader className="gap-2 border-b border-border/70 px-4 py-4 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center sm:px-5">
            <CardTitle className="flex items-center gap-2 text-sm">
              <History className="size-4 text-muted-foreground" />
              学习明细
            </CardTitle>
            <CardAction className="col-span-full col-start-1 row-start-3 w-full justify-self-stretch sm:col-span-1 sm:col-start-2 sm:row-span-2 sm:row-start-1 sm:w-auto sm:justify-self-end">
              <TabsList className="grid w-full grid-cols-2 sm:inline-flex sm:w-auto">
                <TabsTrigger value="answers">
                  <ListChecks />
                  答题记录
                </TabsTrigger>
                <TabsTrigger value="activity">
                  <History />
                  近期动态
                </TabsTrigger>
              </TabsList>
            </CardAction>
          </CardHeader>
          <CardContent className="p-0">
            <TabsContent value="answers" className="m-0">
              {historyPending ? (
                <ListSkeleton rows={5} />
              ) : days.length ? (
                <Table aria-label="近 7 天答题记录">
                  <TableHeader className="bg-muted/35">
                    <TableRow className="hover:bg-transparent">
                      <TableHead className="ps-4 sm:ps-5">日期</TableHead>
                      <TableHead className="text-end">作答</TableHead>
                      <TableHead className="text-end">正确率</TableHead>
                      <TableHead className="pe-4 text-end sm:pe-5">解锁</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {days.map(day => {
                      const answered = Math.max(0, Math.round(toNumber(day.answered)));
                      const rounds = Math.max(0, Math.round(toNumber(day.rounds)));
                      const accuracy = clampPercent(day.accuracy);
                      const tone = accuracyTone(accuracy, answered > 0);
                      const label = day.label || '—';
                      return (
                        <TableRow key={label}>
                          <TableCell className="ps-4 font-medium sm:ps-5">{label}</TableCell>
                          <TableCell className="text-end tabular-nums text-muted-foreground">{answered} 题</TableCell>
                          <TableCell className="text-end">
                            <span className="inline-flex items-center justify-end gap-1.5 tabular-nums">
                              <span aria-hidden="true" className={`size-1.5 rounded-full ${tone.dot}`} />
                              <span className={tone.text}>{answered ? `${accuracy}%` : '—'}</span>
                            </span>
                          </TableCell>
                          <TableCell className="pe-4 text-end tabular-nums text-muted-foreground sm:pe-5">
                            {rounds} 组
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              ) : historyError ? (
                <div className="flex flex-col items-center gap-2 px-4 py-14 text-center">
                  <ListChecks className="size-5 text-destructive/70" />
                  <FieldDescription className="text-destructive">无法读取答题记录</FieldDescription>
                </div>
              ) : (
                <div className="flex flex-col items-center gap-2 px-4 py-14 text-center">
                  <ListChecks className="size-5 text-muted-foreground/70" />
                  <FieldDescription>还没有答题记录</FieldDescription>
                </div>
              )}
            </TabsContent>

            <TabsContent value="activity" className="m-0">
              {eventsPending ? (
                <ListSkeleton rows={6} />
              ) : recentEvents.length ? (
                <ol className="divide-y divide-border/70">
                  {recentEvents.map((event, index) => (
                    <li
                      key={`${event.ts}-${index}`}
                      className="flex items-start gap-3 px-4 py-3 transition-colors hover:bg-muted/35 sm:px-5"
                    >
                      <span
                        aria-hidden="true"
                        className={`mt-2 size-2 shrink-0 rounded-full ${eventTone(event.type, event.payload)}`}
                      />
                      <div className="min-w-0 flex-1">
                        <p className="break-words text-sm font-medium leading-5">{eventLabel(event)}</p>
                      </div>
                      <time className="mt-0.5 shrink-0 text-xs tabular-nums text-muted-foreground">
                        {formatEventTime(event.ts)}
                      </time>
                    </li>
                  ))}
                </ol>
              ) : eventsError ? (
                <div className="flex flex-col items-center gap-2 px-4 py-14 text-center">
                  <History className="size-5 text-destructive/70" />
                  <FieldDescription className="text-destructive">无法读取学习动态</FieldDescription>
                </div>
              ) : (
                <div className="flex flex-col items-center gap-2 px-4 py-14 text-center">
                  <History className="size-5 text-muted-foreground/70" />
                  <FieldDescription>还没有学习动态</FieldDescription>
                </div>
              )}
            </TabsContent>
          </CardContent>
        </Tabs>
      </Card>
    </>
  );
}
