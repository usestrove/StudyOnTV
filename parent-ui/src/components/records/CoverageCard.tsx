import { useQuery } from '@tanstack/react-query';
import { Target } from 'lucide-react';
import { api } from '@/api';
import { MathText } from '@/components/bank/shared';
import { Badge } from '@/components/ui/badge';
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { FieldDescription } from '@/components/ui/field';
import { Skeleton } from '@/components/ui/skeleton';
import { clampPercent, toNumber } from './record-metrics';

type CoverageBank = {
  name?: string;
  total?: number | string;
  covered?: number | string;
  percent?: number | string;
};

type CoverageItem = {
  id?: string;
  q?: string;
  bank?: string;
  level?: string;
  subject?: string;
  presented?: number | string;
  answered?: number | string;
};

export default function CoverageCard({ sessionId }: { sessionId: number }) {
  const coverage = useQuery({
    queryKey: ['coverage', sessionId],
    queryFn: () => api('/api/coverage'),
    refetchInterval: 30000,
  });
  const data = coverage.data || {};
  const total = Math.max(0, toNumber(data.total));
  const covered = Math.max(0, toNumber(data.covered));
  const percent = clampPercent(data.percent);
  const pending = Math.max(0, toNumber(data.pending));
  const untouched = Math.max(0, toNumber(data.untouched));
  const banks: CoverageBank[] = Array.isArray(data.banks) ? data.banks : [];
  const items: CoverageItem[] = Array.isArray(data.items) ? data.items : [];
  const loading = coverage.isPending && !coverage.data;
  const shown = items.slice(0, 6);

  return (
    <Card className="gap-0 overflow-hidden rounded-md py-0 shadow-sm" aria-labelledby="records-coverage-title">
      <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
        <CardTitle id="records-coverage-title" className="flex items-center gap-2 text-sm">
          <Target className="size-4 text-muted-foreground" />
          答题覆盖
        </CardTitle>
        <CardDescription className="col-span-full text-xs">
          答对一次算覆盖；没答对的题会优先出给孩子，直到整库都答对一遍。
        </CardDescription>
        <CardAction>
          <Badge variant="secondary" className="font-normal tabular-nums">
            {loading ? '读取中' : `${covered} / ${total} 题`}
          </Badge>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-5 p-0 px-4 pb-5 pt-5 sm:px-5">
        {loading ? (
          <Skeleton className="h-24 w-full" />
        ) : coverage.isError ? (
          <div className="flex flex-col items-center gap-2 py-10 text-center">
            <Target className="size-5 text-destructive/70" />
            <FieldDescription className="text-destructive">无法读取答题覆盖情况</FieldDescription>
          </div>
        ) : total === 0 ? (
          <div className="flex flex-col items-center gap-2 py-10 text-center">
            <Target className="size-5 text-muted-foreground/70" />
            <FieldDescription>题库里还没有启用的题目</FieldDescription>
          </div>
        ) : (
          <>
            <div>
              <div className="flex items-end justify-between gap-2">
                <span className="flex items-end gap-1 whitespace-nowrap tabular-nums">
                  <span className="text-3xl font-semibold leading-none tracking-tight">{percent}</span>
                  <span className="pb-0.5 text-xs text-muted-foreground">%</span>
                </span>
                <span className="text-xs text-muted-foreground">
                  {pending > 0 ? `还差 ${pending} 题答对就全覆盖` : '所有题都答对过了'}
                </span>
              </div>
              <div
                className="mt-3 h-2 w-full overflow-hidden rounded-full bg-muted/65"
                role="img"
                aria-label={`答题覆盖率 ${percent}%`}
              >
                <span
                  className="block h-full rounded-full bg-primary transition-[width] duration-500"
                  style={{ width: `${percent}%` }}
                />
              </div>
            </div>

            <dl className="grid grid-cols-3 divide-x divide-border/70 rounded-lg border border-border/70">
              {[
                { label: '已答对', value: covered },
                { label: '未答对', value: pending },
                { label: '从未出过', value: untouched },
              ].map(cell => (
                <div key={cell.label} className="min-w-0 px-3 py-3">
                  <dt className="truncate text-xs text-muted-foreground">{cell.label}</dt>
                  <dd className="mt-1.5 whitespace-nowrap text-lg font-semibold leading-none tabular-nums">
                    {cell.value}
                    <span className="ms-0.5 text-[11px] font-normal text-muted-foreground">题</span>
                  </dd>
                </div>
              ))}
            </dl>

            {banks.length > 1 && (
              <div className="space-y-2.5">
                <p className="text-xs text-muted-foreground">按题库</p>
                {banks.slice(0, 5).map(bank => {
                  const bankTotal = Math.max(0, toNumber(bank.total));
                  const bankCovered = Math.max(0, toNumber(bank.covered));
                  const bankPercent = clampPercent(bank.percent);
                  return (
                    <div key={bank.name || '默认题库'} className="space-y-1">
                      <div className="flex items-center justify-between gap-2 text-xs">
                        <span className="truncate">{bank.name || '默认题库'}</span>
                        <span className="shrink-0 tabular-nums text-muted-foreground">
                          {bankCovered} / {bankTotal}
                        </span>
                      </div>
                      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted/65">
                        <span
                          className="block h-full rounded-full bg-primary/80"
                          style={{ width: `${bankPercent}%` }}
                        />
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            {shown.length > 0 && (
              <div className="space-y-2">
                <p className="flex items-baseline justify-between gap-2 text-xs text-muted-foreground">
                  <span>还没答对的题</span>
                  <span className="shrink-0 tabular-nums">共 {pending} 题</span>
                </p>
                <ol className="divide-y divide-border/70 rounded-lg border border-border/70">
                  {shown.map((item, index) => (
                    <li key={item.id || index} className="flex items-start gap-3 px-3 py-2.5">
                      <span className="mt-0.5 shrink-0 text-[11px] tabular-nums text-muted-foreground">
                        {index + 1}
                      </span>
                      <div className="min-w-0 flex-1">
                        <MathText text={item.q || '（无题干）'} className="truncate text-sm" />
                        <p className="mt-0.5 truncate text-[11px] text-muted-foreground">
                          {item.bank || '默认题库'}
                          {item.level ? ` · ${item.level}` : ''}
                          {item.subject ? ` · ${item.subject}` : ''} · 出题 {toNumber(item.presented)} 次 · 作答{' '}
                          {toNumber(item.answered)} 次
                        </p>
                      </div>
                    </li>
                  ))}
                </ol>
                {pending > shown.length && (
                  <p className="text-[11px] text-muted-foreground">
                    还有 {pending - shown.length} 题没答对，可在题库页逐题查看。
                  </p>
                )}
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}
