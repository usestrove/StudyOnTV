import { CheckCircle2, ChevronDown, Flame, PlusCircle, Sparkles } from 'lucide-react';
import { MathText, labels } from '@/components/bank/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';

export interface WrongQuestionItem {
  id: string;
  q: string;
  type: string;
  subject?: string;
  level?: string;
  bank?: string;
  options?: string[];
  answer?: string;
  explain?: string;
  answered: number;
  correct: number;
  wrongCount: number;
  lastAt?: number;
  lastCorrect?: number;
}

function formatLastAt(ts?: number): string {
  if (!ts) return '—';
  const date = new Date(ts);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

type Props = {
  item: WrongQuestionItem;
  checked: boolean;
  inQueue: boolean;
  busy: boolean;
  onToggle: (id: string) => void;
  onAddExtra: (id: string) => void;
  onMarkMastered: (id: string) => void;
};

export default function WrongQuestionCard({
  item,
  checked,
  inQueue,
  busy,
  onToggle,
  onAddExtra,
  onMarkMastered,
}: Props) {
  return (
    <Card
      className={`gap-0 py-0 shadow-xs transition-colors ${
        checked ? 'border-primary/50 bg-primary/[0.02]' : ''
      }`}
    >
      <CardContent className="p-4 sm:px-5">
        <div className="flex items-start gap-3">
          <Checkbox
            className="mt-1 shrink-0"
            checked={checked}
            onCheckedChange={() => onToggle(item.id)}
            aria-label={`选择错题 ${item.id}`}
          />
          <div className="min-w-0 flex-1 space-y-2.5">
            <div className="flex flex-wrap items-center gap-1.5">
              <Badge variant="outline" className="text-[11px]">
                {labels[item.type] || item.type || '题目'}
              </Badge>
              {item.subject && (
                <Badge variant="secondary" className="text-[11px]">
                  {item.subject}
                </Badge>
              )}
              {item.level && (
                <Badge variant="secondary" className="text-[11px]">
                  {item.level}
                </Badge>
              )}
              {item.bank && item.bank !== '默认题库' && (
                <Badge variant="outline" className="text-[11px] text-muted-foreground">
                  {item.bank}
                </Badge>
              )}
              {inQueue && (
                <Badge className="bg-primary/15 text-primary hover:bg-primary/20 text-[11px]">
                  <Sparkles className="me-1 size-3" />
                  已在加练池
                </Badge>
              )}
              <span className="ms-auto flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                <span className="inline-flex items-center gap-1 font-medium text-rose-600 dark:text-rose-400">
                  <Flame className="size-3.5" />
                  错 {item.wrongCount} 次
                </span>
                <span>共答 {item.answered} 次</span>
                <span className="hidden sm:inline">上次：{formatLastAt(item.lastAt)}</span>
              </span>
            </div>

            <MathText
              text={item.q || '（无题干）'}
              className="text-sm font-medium leading-relaxed text-foreground break-words"
            />

            <div className="text-[11px] text-muted-foreground sm:hidden">
              上次作答：{formatLastAt(item.lastAt)}
            </div>

            <Collapsible>
              <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
                <CollapsibleTrigger
                  type="button"
                  className="group inline-flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
                >
                  <ChevronDown className="size-3.5 transition-transform group-data-[state=open]:rotate-180" />
                  查看答案与解析
                </CollapsibleTrigger>
                <div className="flex flex-wrap items-center gap-1.5">
                  <Button
                    type="button"
                    size="sm"
                    variant={inQueue ? 'secondary' : 'outline'}
                    className="h-7 px-2 text-xs"
                    disabled={busy || inQueue}
                    onClick={() => onAddExtra(item.id)}
                  >
                    <PlusCircle className="size-3.5" />
                    {inQueue ? '已在加练池' : '加入加练池'}
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    className="h-7 px-2 text-xs"
                    disabled={busy}
                    onClick={() => onMarkMastered(item.id)}
                  >
                    <CheckCircle2 className="size-3.5" />
                    标为已掌握
                  </Button>
                </div>
              </div>

              <CollapsibleContent className="mt-3 space-y-2 rounded-lg border border-border/60 bg-muted/35 p-3 text-xs">
                {Array.isArray(item.options) && item.options.length > 0 && (
                  <div className="space-y-1">
                    <div className="font-medium text-muted-foreground">选项</div>
                    <div className="grid gap-1 sm:grid-cols-2">
                      {item.options.map((opt, idx) => (
                        <div key={idx} className="flex items-start gap-1.5">
                          <span className="shrink-0 font-mono text-muted-foreground">
                            {String.fromCharCode(65 + idx)}.
                          </span>
                          <MathText text={String(opt || '')} className="min-w-0 break-words" />
                        </div>
                      ))}
                    </div>
                  </div>
                )}
                <div className="space-y-1">
                  <div className="font-medium text-emerald-700 dark:text-emerald-400">参考答案</div>
                  <MathText
                    text={item.answer || '暂无参考答案'}
                    className="leading-relaxed text-foreground break-words"
                  />
                </div>
                {item.explain && (
                  <div className="space-y-1 border-t border-border/50 pt-2">
                    <div className="font-medium text-muted-foreground">解析</div>
                    <MathText
                      text={item.explain}
                      className="leading-relaxed text-muted-foreground break-words"
                    />
                  </div>
                )}
              </CollapsibleContent>
            </Collapsible>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
