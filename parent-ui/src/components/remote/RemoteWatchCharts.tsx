export function MiniWatchBars({ days }: { days: any[] }) {
  const ordered = days
    .slice()
    .reverse()
    .map(d => ({
      label: String(d?.label || '—'),
      watch: Math.max(0, Number(d?.watch) || 0),
    }));
  const max = Math.max(10, ...ordered.map(d => d.watch));
  if (!ordered.length) return null;
  return (
    <div className="flex h-full items-end gap-1.5" role="img" aria-label="近 7 天使用时长缩略柱状图">
      {ordered.map((d, i) => (
        <div
          key={`${d.label}-${i}`}
          className="group relative flex h-full min-w-0 flex-1 items-end justify-center"
          title={`${d.label}：${d.watch.toFixed(1)} 分钟`}
        >
          <span
            className="block w-full max-w-4 rounded-t-[5px] rounded-b-[2px] bg-[var(--chart-3)] transition-opacity group-hover:opacity-80"
            style={{
              height: `${Math.max(d.watch > 0 ? 10 : 3, (d.watch / max) * 100)}%`,
              opacity: d.watch > 0 ? 1 : 0.25,
            }}
          />
        </div>
      ))}
    </div>
  );
}

export function SevenDayWatchChart({ days }: { days: any[] }) {
  const chartDays = days
    .slice()
    .reverse()
    .map(d => ({
      label: String(d?.label || '—'),
      watch: Math.max(0, Number(d?.watch) || 0),
      answered: Math.max(0, Number(d?.answered) || 0),
      accuracy: Math.min(100, Math.max(0, Math.round(Number(d?.accuracy) || 0))),
    }));
  const chartMax = Math.max(
    40,
    Math.ceil(Math.max(1, ...chartDays.map(d => d.watch)) / 10) * 10,
  );
  const ticks = Array.from({ length: 5 }, (_, index) =>
    Math.round(chartMax - (chartMax / 4) * index),
  );
  return (
    <div
      className="grid h-64 grid-cols-[30px_minmax(0,1fr)] gap-2"
      role="img"
      aria-label="最近 7 天每日使用时长柱状图"
    >
      <div className="flex flex-col justify-between pb-7 pt-1 text-right text-xs tabular-nums text-muted-foreground">
        {ticks.map(t => (
          <span key={t}>{t}</span>
        ))}
      </div>
      <div className="relative min-w-0 pb-7">
        <div className="pointer-events-none absolute inset-x-0 bottom-7 top-1 flex flex-col justify-between">
          {ticks.map(t => (
            <span key={t} className="border-t border-dashed border-border/70" />
          ))}
        </div>
        <div className="relative z-10 flex h-[calc(100%-1.75rem)] items-end justify-around gap-2">
          {chartDays.map((day, index) => (
            <div
              key={`${day.label}-${index}`}
              className="group relative flex h-full min-w-0 flex-1 flex-col items-center justify-end"
              title={`${day.label}：使用 ${day.watch.toFixed(1)} 分钟，答题 ${day.answered} 题`}
            >
              <div className="pointer-events-none absolute -top-7 z-20 hidden whitespace-nowrap rounded-md border bg-popover px-2 py-0.5 text-[11px] text-popover-foreground shadow-sm group-hover:block">
                {day.watch.toFixed(1)} 分钟
              </div>
              <div className="relative flex w-full max-w-7 flex-1 items-end overflow-hidden rounded-t-md bg-muted/55">
                <span
                  className="block w-full rounded-t-[5px] bg-[var(--chart-1)] transition-[height] duration-300 group-hover:opacity-85"
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
  );
}
