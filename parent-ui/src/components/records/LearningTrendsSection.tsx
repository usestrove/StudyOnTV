import { CalendarClock, ChartColumn } from 'lucide-react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { FieldDescription } from '@/components/ui/field';
import { toNumber } from './record-metrics';

export type DailyPracticePoint = {
  label: string;
  answered: number;
  correct: number;
  accuracy: number;
};

type Props = {
  daily14: DailyPracticePoint[];
  forecast: any[];
};

export default function LearningTrendsSection({ daily14, forecast }: Props) {
  const dailyMaxAnswered = Math.max(10, ...daily14.map(d => d.answered));
  const forecastMax = Math.max(5, ...forecast.map(f => toNumber(f?.dueCount ?? f?.due ?? f?.count)));

  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <Card className="gap-0 overflow-hidden py-0 shadow-sm lg:col-span-2">
        <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
          <CardTitle className="flex items-center gap-2 text-sm">
            <ChartColumn className="size-4 text-muted-foreground" />
            近 14 天练习量与正确率
          </CardTitle>
          <CardDescription className="col-span-full text-xs">
            柱高代表每日做题数，颜色代表正确率（绿 ≥85% · 黄 60–84% · 红 &lt;60%）
          </CardDescription>
        </CardHeader>
        <CardContent className="px-4 pb-5 pt-5 sm:px-5">
          {daily14.length === 0 ? (
            <FieldDescription className="py-14 text-center">暂无近 14 天做题记录</FieldDescription>
          ) : (
            <div className="flex h-52 items-end justify-between gap-1.5 pb-6 pt-4">
              {daily14.slice(-14).map((d, i) => {
                const barColor =
                  d.answered === 0
                    ? 'bg-muted-foreground/25'
                    : d.accuracy >= 85
                      ? 'bg-emerald-500'
                      : d.accuracy >= 60
                        ? 'bg-amber-500'
                        : 'bg-rose-500';
                return (
                  <div
                    key={`${d.label}-${i}`}
                    className="group relative flex h-full min-w-0 flex-1 flex-col items-center justify-end"
                    title={`${d.label}：作答 ${d.answered} 题，正确率 ${d.accuracy}%`}
                  >
                    <div className="pointer-events-none absolute -top-6 z-20 hidden whitespace-nowrap rounded border bg-popover px-1.5 py-0.5 text-[10px] text-popover-foreground shadow-sm group-hover:block">
                      {d.answered}题 · {d.accuracy}%
                    </div>
                    <div className="flex w-full max-w-6 flex-1 items-end overflow-hidden rounded-t bg-muted/50">
                      <span
                        className={`block w-full rounded-t transition-[height] duration-300 ${barColor}`}
                        style={{
                          height: `${(d.answered / dailyMaxAnswered) * 100}%`,
                          minHeight: d.answered > 0 ? 5 : 2,
                        }}
                      />
                    </div>
                    <span className="absolute -bottom-6 truncate text-[10px] tabular-nums text-muted-foreground">
                      {d.label}
                    </span>
                  </div>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>

      <Card className="gap-0 overflow-hidden py-0 shadow-sm">
        <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
          <CardTitle className="flex items-center gap-2 text-sm">
            <CalendarClock className="size-4 text-muted-foreground" />
            未来 7 天复习预测
          </CardTitle>
          <CardDescription className="col-span-full text-xs">
            预计每日到期的艾宾浩斯复习卡片数
          </CardDescription>
        </CardHeader>
        <CardContent className="px-4 pb-5 pt-5 sm:px-5">
          {forecast.length === 0 ? (
            <FieldDescription className="py-14 text-center">暂无待复习排期</FieldDescription>
          ) : (
            <div className="flex h-52 items-end justify-around gap-2 pb-6 pt-4">
              {forecast.slice(0, 7).map((f: any, i: number) => {
                const count = Math.max(0, toNumber(f?.dueCount ?? f?.due ?? f?.count));
                const label = String(f?.label || f?.date || `+${i}天`).replace(/^\d{4}-/, '');
                return (
                  <div
                    key={`${label}-${i}`}
                    className="group relative flex h-full min-w-0 flex-1 flex-col items-center justify-end"
                    title={`${label}：预计 ${count} 题到期复习`}
                  >
                    <span className="mb-1 text-[10px] tabular-nums text-muted-foreground">
                      {count > 0 ? count : ''}
                    </span>
                    <div className="flex w-full max-w-6 flex-1 items-end overflow-hidden rounded-t bg-muted/50">
                      <span
                        className="block w-full rounded-t bg-sky-500 transition-[height] duration-300"
                        style={{
                          height: `${(count / forecastMax) * 100}%`,
                          minHeight: count > 0 ? 5 : 2,
                        }}
                      />
                    </div>
                    <span className="absolute -bottom-6 whitespace-nowrap text-[10px] text-muted-foreground">
                      {i === 0 ? '今天' : label}
                    </span>
                  </div>
                );
              })}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
