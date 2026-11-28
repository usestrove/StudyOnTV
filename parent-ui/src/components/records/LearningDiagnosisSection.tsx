import { AlertTriangle, Brain, Sparkles } from 'lucide-react';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { FieldDescription } from '@/components/ui/field';
import type { AiGeneratePreset } from '@/components/bank/AiGenerateDialog';
import { answerOf, MathText } from '@/components/bank/shared';
import { accuracyTone, clampPercent, toNumber } from './record-metrics';

type Props = {
  maturity: { unseen: number; learning: number; young: number; mature: number; total: number };
  bySubject: any[];
  hardQuestions: any[];
  onSelectAiPreset: (preset: AiGeneratePreset) => void;
};

export default function LearningDiagnosisSection({
  maturity,
  bySubject,
  hardQuestions,
  onSelectAiPreset,
}: Props) {
  const { unseen: matUnseen, learning: matLearning, young: matYoung, mature: matMature, total: matTotal } = maturity;

  return (
    <>
      <Card className="gap-0 overflow-hidden py-0 shadow-sm">
        <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
          <CardTitle className="flex items-center gap-2 text-sm">
            <Brain className="size-4 text-muted-foreground" />
            题目掌握进度分布
          </CardTitle>
          <CardDescription className="col-span-full text-xs">
            按复习掌握程度划分：未接触 → 初学（1 天内再复习）→ 巩固中（1–20 天后再复习）→ 已熟记（21 天以上）
          </CardDescription>
          <CardAction>
            <Badge variant="outline" className="font-normal tabular-nums">
              共 {matTotal} 题
            </Badge>
          </CardAction>
        </CardHeader>
        <CardContent className="space-y-4 px-4 py-5 sm:px-5">
          {matTotal === 0 ? (
            <FieldDescription className="py-6 text-center">
              题库暂无题目，添加题目并开始练习后将自动生成记忆成熟度图谱
            </FieldDescription>
          ) : (
            <>
              <div
                className="flex h-3.5 w-full overflow-hidden rounded-full bg-muted/60"
                role="img"
                aria-label="记忆成熟度四阶段占比条"
              >
                {[
                  { key: 'mature', count: matMature, cls: 'bg-emerald-500', label: '已熟记' },
                  { key: 'young', count: matYoung, cls: 'bg-sky-500', label: '巩固中' },
                  { key: 'learning', count: matLearning, cls: 'bg-amber-500', label: '初学' },
                  { key: 'unseen', count: matUnseen, cls: 'bg-slate-300 dark:bg-slate-600', label: '未接触' },
                ].map(seg =>
                  seg.count > 0 ? (
                    <span
                      key={seg.key}
                      title={`${seg.label}：${seg.count} 题（${Math.round((seg.count * 100) / matTotal)}%）`}
                      className={`h-full transition-[width] duration-500 ${seg.cls}`}
                      style={{ width: `${(seg.count / matTotal) * 100}%` }}
                    />
                  ) : null,
                )}
              </div>
              <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
                {[
                  { label: '已熟记', desc: '间隔 ≥ 21 天', count: matMature, dot: 'bg-emerald-500' },
                  { label: '巩固中', desc: '间隔 1–20 天', count: matYoung, dot: 'bg-sky-500' },
                  { label: '初学阶段', desc: '近期刚接触/重学', count: matLearning, dot: 'bg-amber-500' },
                  { label: '未接触', desc: '尚未出过题', count: matUnseen, dot: 'bg-slate-400' },
                ].map(item => (
                  <div key={item.label} className="rounded-lg border border-border/70 px-3 py-2.5">
                    <div className="flex items-center gap-1.5 text-xs font-medium">
                      <span className={`size-2 rounded-full ${item.dot}`} />
                      <span>{item.label}</span>
                    </div>
                    <div className="mt-1.5 flex items-baseline justify-between gap-1 tabular-nums">
                      <span className="text-lg font-semibold">{item.count}</span>
                      <span className="text-[11px] text-muted-foreground">
                        {Math.round((item.count * 100) / matTotal)}%
                      </span>
                    </div>
                    <div className="mt-0.5 text-[11px] text-muted-foreground">{item.desc}</div>
                  </div>
                ))}
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <Card className="gap-0 overflow-hidden py-0 shadow-sm">
        <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
          <CardTitle className="flex items-center gap-2 text-sm">
            <Sparkles className="size-4 text-primary" />
            分科目掌握度与薄弱项诊断
          </CardTitle>
          <CardDescription className="col-span-full text-xs">
            自动定位低正确率与高频错题科目，点击「AI 举一反三出巩固题」可针对薄弱点一键生成同考点变式题。
          </CardDescription>
        </CardHeader>
        <CardContent className="divide-y divide-border/70 px-4 sm:px-5">
          {bySubject.length === 0 ? (
            <FieldDescription className="py-10 text-center">
              暂无分科目作答数据，答题后将按学科展示掌握度与薄弱点诊断
            </FieldDescription>
          ) : (
            bySubject.map((item: any, idx: number) => {
              const subj = String(item.subject || item.name || '未分类');
              const total = Math.max(0, toNumber(item.total));
              const answered = Math.max(0, toNumber(item.answered));
              const acc = clampPercent(item.accuracy);
              const mastered = Math.max(0, toNumber(item.mastered ?? item.covered));
              const wrong = Math.max(0, toNumber(item.wrong ?? item.wrongCount));
              const isWeak = wrong > 0 || (answered >= 3 && acc < 80);
              const tone = accuracyTone(acc, answered > 0);
              return (
                <div key={`${subj}-${idx}`} className="space-y-2.5 py-4">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold">{subj}</span>
                      <Badge variant="outline" className="text-[11px] font-normal tabular-nums">
                        已掌握 {mastered}/{total} 题
                      </Badge>
                      {wrong > 0 && (
                        <Badge variant="destructive" className="text-[11px] font-normal tabular-nums">
                          错题 {wrong} 道
                        </Badge>
                      )}
                      {answered > 0 && (
                        <span className={`text-xs font-medium tabular-nums ${tone.text}`}>
                          正确率 {acc}%
                        </span>
                      )}
                    </div>
                    <Button
                      type="button"
                      size="sm"
                      variant={isWeak ? 'default' : 'outline'}
                      className="h-7 gap-1 px-2.5 text-xs"
                      onClick={() =>
                        onSelectAiPreset({
                          fromWrongBook: wrong > 0,
                          subject: subj === '未分类' ? '' : subj,
                          topic: wrong > 0 ? '' : `${subj}核心易错考点巩固练习`,
                          bank: wrong > 0 ? `${subj}错题变式` : '默认题库',
                        })
                      }
                    >
                      <Sparkles className="size-3.5" />
                      ✨ AI 举一反三出巩固题
                    </Button>
                  </div>
                  <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                    <div className="space-y-1">
                      <div className="flex justify-between text-[11px] text-muted-foreground">
                        <span>答题正确率</span>
                        <span className="tabular-nums">
                          {answered > 0 ? `${acc}%（作答 ${answered} 次）` : '尚未作答'}
                        </span>
                      </div>
                      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted/65">
                        <span
                          className={`block h-full rounded-full ${tone.dot}`}
                          style={{ width: `${answered > 0 ? acc : 0}%` }}
                        />
                      </div>
                    </div>
                    <div className="space-y-1">
                      <div className="flex justify-between text-[11px] text-muted-foreground">
                        <span>题库掌握进度</span>
                        <span className="tabular-nums">
                          {total > 0 ? `${Math.round((mastered * 100) / total)}%` : '0%'}
                        </span>
                      </div>
                      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted/65">
                        <span
                          className="block h-full rounded-full bg-primary"
                          style={{ width: `${total > 0 ? Math.min(100, (mastered * 100) / total) : 0}%` }}
                        />
                      </div>
                    </div>
                  </div>
                </div>
              );
            })
          )}
        </CardContent>
      </Card>

      <Card className="gap-0 overflow-hidden py-0 shadow-sm">
        <CardHeader className="border-b border-border/70 px-4 py-3 sm:px-5">
          <CardTitle className="flex items-center gap-2 text-sm">
            <AlertTriangle className="size-4 text-rose-500" />
            顽固错题 TOP 榜
          </CardTitle>
          <CardDescription className="col-span-full text-xs">
            优先展示反复答错的题目；建议让孩子在设备端打开「错题先背再测」，或针对单题生成同考点变式。
          </CardDescription>
        </CardHeader>
        <CardContent className="divide-y divide-border/70 px-4 sm:px-5">
          {hardQuestions.length === 0 ? (
            <FieldDescription className="py-10 text-center">
              🎉 太棒了！目前没有反复出错的顽固错题
            </FieldDescription>
          ) : (
            hardQuestions.slice(0, 10).map((q: any, idx: number) => {
              const wrongTimes = Math.max(
                1,
                toNumber(
                  q.wrongTimes ??
                    q.wrongCount ??
                    q.wrong ??
                    q.incorrect ??
                    toNumber(q.answered) - toNumber(q.correct),
                ),
              );
              const attempts = Math.max(wrongTimes, toNumber(q.answered ?? q.attempts));
              const acc = clampPercent(
                q.accuracy ?? (attempts > 0 ? ((attempts - wrongTimes) * 100) / attempts : 0),
              );
              const ansText = q.answerDisplay || answerOf(q);
              return (
                <div key={q.id || idx} className="py-3.5">
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div className="flex min-w-0 flex-1 items-start gap-2.5">
                      <Badge variant="destructive" className="mt-0.5 shrink-0 tabular-nums">
                        错 {wrongTimes} 次
                      </Badge>
                      <div className="min-w-0 flex-1">
                        <MathText
                          text={q.q || '（题目内容）'}
                          className="whitespace-pre-wrap text-sm font-medium leading-6"
                        />
                        <div className="mt-1 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs text-muted-foreground">
                          {q.subject && <span>{q.subject}</span>}
                          {q.level && <span>· {q.level}</span>}
                          <span>
                            作答 {attempts} 次 · 正确率 {acc}%
                          </span>
                        </div>
                      </div>
                    </div>
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      className="h-7 shrink-0 gap-1 px-2.5 text-xs"
                      onClick={() =>
                        onSelectAiPreset({
                          fromWrongBook: true,
                          subject: q.subject || '',
                          level: q.level || '',
                          bank: q.bank ? `${q.bank}-变式` : '错题巩固题库',
                          topic: `${q.subject || ''}同考点变式巩固`.trim(),
                          extra: `请围绕这道高频错题背后的考点出几道换数字/换问法的举一反三变式题：「${String(q.q || '').slice(0, 120)}」`,
                        })
                      }
                    >
                      <Sparkles className="size-3 text-primary" />
                      生成变式题
                    </Button>
                  </div>
                  <Accordion type="single" collapsible className="mt-1">
                    <AccordionItem value="detail" className="border-0">
                      <AccordionTrigger className="py-1.5 text-xs text-muted-foreground hover:no-underline">
                        查看解析与提示
                      </AccordionTrigger>
                      <AccordionContent className="space-y-1.5 rounded-lg bg-muted/40 p-3 text-xs">
                        {ansText && (
                          <MathText
                            text={'正确答案：' + ansText}
                            className="font-medium text-emerald-700 dark:text-emerald-400"
                          />
                        )}
                        {q.tip ? (
                          <MathText
                            text={'解析：' + q.tip}
                            className="whitespace-pre-wrap leading-5 text-muted-foreground"
                          />
                        ) : (
                          <p className="text-muted-foreground">
                            暂无解析，可在题库页搜索该题补充记忆口诀或点击右上方生成变式题。
                          </p>
                        )}
                      </AccordionContent>
                    </AccordionItem>
                  </Accordion>
                </div>
              );
            })
          )}
        </CardContent>
      </Card>
    </>
  );
}
