import type { ReactNode } from 'react';
import { BookOpen } from 'lucide-react';
import { toast } from 'sonner';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Field, FieldContent, FieldDescription, FieldGroup, FieldLabel, FieldSet, FieldTitle } from '@/components/ui/field';
import { Switch } from '@/components/ui/switch';
import type { Rules } from '@/schema';
import { Choice, FIELD_GROUP_CLASS, Panel } from './RulePrimitives';
import {
  QUESTION_ORDER_OPTIONS,
  questionOrderPatch,
  resolveQuestionOrderMode,
  type QuestionOrderMode,
} from './question-order';

type Props = {
  c: Rules;
  scopeSubjects: string[];
  scopeLevels: string[];
  scopeBanks: string[];
  gradeSubjectMode: boolean;
  setGradeSubjectMode: (value: boolean) => void;
  statusNode?: ReactNode;
  change: (key: any, value: any) => void;
  toggle: (key: any) => ReactNode;
};

export default function ScopeRulesTab({
  c,
  scopeSubjects,
  scopeLevels,
  scopeBanks,
  gradeSubjectMode,
  setGradeSubjectMode,
  statusNode,
  change,
  toggle,
}: Props) {
  const inScope = (key: any, value: string) => Array.isArray((c as any)[key]) && (c as any)[key].includes(value);
  const toggleScope = (key: any, value: string) => {
    const list = Array.isArray((c as any)[key]) ? (c as any)[key] : [];
    change(key, list.includes(value) ? list.filter((v: string) => v !== value) : [...list, value]);
  };

  const gradeSubjects = (c.gradeSubjects || {}) as Record<string, string[]>;
  const gradeSubjectEntries = Object.entries(gradeSubjects);
  const allScopeLevels = Array.from(new Set([...scopeLevels, ...Object.keys(gradeSubjects)]));
  const gradeSubjectSummary = gradeSubjectEntries.length
    ? `已设置 ${gradeSubjectEntries.length} 个年级：${gradeSubjectEntries
        .map(([g, subs]) => `${g}（${subs.length ? subs.join('、') : '全部科目'}）`)
        .join('、')}`
    : '请勾选下方年级，或点右侧按钮把上面的年级和科目复制进来';

  const orderMode = resolveQuestionOrderMode(c);
  const currentOrderInfo =
    QUESTION_ORDER_OPTIONS.find(item => item.value === orderMode) || QUESTION_ORDER_OPTIONS[0];

  const applyOrderMode = (mode: QuestionOrderMode) => {
    const patch = questionOrderPatch(mode);
    change('wrongFirst', patch.wrongFirst);
    change('newFirst', patch.newFirst);
    change('coverageFirst', patch.coverageFirst);
  };

  const toggleGradeInMap = (level: string) => {
    const next = { ...gradeSubjects };
    if (level in next) delete next[level];
    else next[level] = [];
    change('gradeSubjects', next);
  };

  const toggleSubjectInGrade = (level: string, subject: string) => {
    const current = Array.isArray(gradeSubjects[level]) ? gradeSubjects[level] : [];
    const nextSubs = current.includes(subject)
      ? current.filter(s => s !== subject)
      : [...current, subject];
    change('gradeSubjects', { ...gradeSubjects, [level]: nextSubs });
  };

  const importFromFlatScope = () => {
    const targetLevels = (c.quizLevels?.length ? c.quizLevels : scopeLevels).filter(Boolean);
    if (!targetLevels.length) {
      toast.error('题库里还没有年级可导入');
      return;
    }
    const targetSubjects = (c.quizSubjects || []).filter(Boolean);
    const next: Record<string, string[]> = {};
    targetLevels.forEach(lvl => {
      next[lvl] = [...targetSubjects];
    });
    change('gradeSubjects', next);
    toast.success(`已从当前设置导入 ${targetLevels.length} 个年级`);
  };

  return (
    <Panel title="出题范围与顺序" icon={BookOpen} description="都不勾选时默认从全部题目里出题" status={statusNode}>
      <FieldSet className="gap-0">
        <FieldGroup className={FIELD_GROUP_CLASS}>
          <Field orientation="responsive" className="py-3.5">
            <FieldContent>
              <FieldTitle>出题顺序</FieldTitle>
              <FieldDescription className="text-xs leading-5">
                {currentOrderInfo.preview}
                {c.spacedRepetitionEnabled ? '（已开启「到点自动复习」：到复习时间的旧题会优先穿插进来）' : ''}
              </FieldDescription>
            </FieldContent>
            <Choice
              label="出题顺序"
              value={orderMode}
              onChange={v => applyOrderMode(v as QuestionOrderMode)}
              options={QUESTION_ORDER_OPTIONS.map(item => [item.value, item.label])}
            />
          </Field>
          <Field orientation="responsive" className="py-3.5">
            <FieldContent>
              <FieldTitle>允许孩子自己选年级</FieldTitle>
              <FieldDescription className="text-xs leading-5">
                答题开始前可从允许的年级里自选，答题中途不能切换
              </FieldDescription>
            </FieldContent>
            {toggle('childSelectLevel')}
          </Field>
          <Field orientation="responsive" className="py-3.5">
            <FieldContent>
              <FieldTitle>选择题库</FieldTitle>
              <FieldDescription className="text-xs leading-5">只从勾选的题库里抽题，不选表示全部</FieldDescription>
            </FieldContent>
            <div className="flex min-w-0 flex-wrap gap-2">
              {scopeBanks.length ? (
                scopeBanks.map(v => (
                  <FieldLabel
                    key={v}
                    className="cursor-pointer rounded-lg border border-border/70 px-3 py-2 font-normal transition-colors hover:bg-muted/40 has-data-[state=checked]:border-primary/40 has-data-[state=checked]:bg-primary/5"
                  >
                    <Checkbox checked={inScope('quizBanks', v)} onCheckedChange={() => toggleScope('quizBanks', v)} />
                    <span>{v}</span>
                  </FieldLabel>
                ))
              ) : (
                <FieldDescription className="text-xs">题库里还没有题目</FieldDescription>
              )}
            </div>
          </Field>
          <Field orientation="responsive" className="py-3.5">
            <FieldContent>
              <FieldTitle>不同年级练不同科目</FieldTitle>
              <FieldDescription className="text-xs leading-5">
                例如：三年级只练英语，四年级练语数英；关闭则所有年级共用同一组科目
              </FieldDescription>
            </FieldContent>
            <Switch
              aria-label="不同年级练不同科目"
              className="w-8! shrink-0"
              checked={gradeSubjectMode}
              onCheckedChange={checked => {
                setGradeSubjectMode(checked);
                if (!checked && Object.keys(gradeSubjects).length > 0) {
                  change('gradeSubjects', {});
                }
              }}
            />
          </Field>
          {gradeSubjectMode ? (
            <Field className="py-3.5">
              <div className="space-y-3">
                <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border/70 bg-muted/35 px-3 py-2">
                  <p className="min-w-0 flex-1 text-xs leading-5 text-muted-foreground">{gradeSubjectSummary}</p>
                  {(scopeLevels.length > 0 || (c.quizLevels?.length || 0) > 0) && (
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      className="h-7 px-2 text-xs"
                      onClick={importFromFlatScope}
                    >
                      复制上面的年级和科目
                    </Button>
                  )}
                </div>
                {allScopeLevels.length ? (
                  <div className="space-y-2">
                    {allScopeLevels.map(lvl => {
                      const enabled = lvl in gradeSubjects;
                      const chosenSubs = Array.isArray(gradeSubjects[lvl]) ? gradeSubjects[lvl] : [];
                      const levelSubjects = Array.from(new Set([...scopeSubjects, ...chosenSubs]));
                      return (
                        <div
                          key={lvl}
                          className={`rounded-lg border p-3 transition-colors ${
                            enabled ? 'border-primary/40 bg-primary/[0.03]' : 'border-border/70 bg-background'
                          }`}
                        >
                          <div className="flex flex-wrap items-center justify-between gap-2">
                            <FieldLabel className="cursor-pointer font-medium">
                              <Checkbox checked={enabled} onCheckedChange={() => toggleGradeInMap(lvl)} />
                              <span>{lvl}</span>
                            </FieldLabel>
                            {enabled && (
                              <Badge variant="secondary" className="text-[11px] font-normal">
                                {chosenSubs.length ? `已选 ${chosenSubs.length} 科` : '全部科目'}
                              </Badge>
                            )}
                          </div>
                          {enabled && (
                            <div className="mt-2.5 space-y-1.5 border-t border-border/50 pt-2.5">
                              <p className="text-[11px] text-muted-foreground">
                                选择该年级要练习的科目（不勾选表示该年级全部科目）：
                              </p>
                              {levelSubjects.length ? (
                                <div className="flex flex-wrap gap-2">
                                  {levelSubjects.map(sub => (
                                    <FieldLabel
                                      key={sub}
                                      className="cursor-pointer rounded-md border border-border/70 bg-background px-2.5 py-1.5 text-xs font-normal transition-colors hover:bg-muted/40 has-data-[state=checked]:border-primary/40 has-data-[state=checked]:bg-primary/10"
                                    >
                                      <Checkbox
                                        checked={chosenSubs.includes(sub)}
                                        onCheckedChange={() => toggleSubjectInGrade(lvl, sub)}
                                      />
                                      <span>{sub}</span>
                                    </FieldLabel>
                                  ))}
                                </div>
                              ) : (
                                <p className="text-xs text-muted-foreground">
                                  题库里还没有科目，将练习该年级全部题目
                                </p>
                              )}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <FieldDescription className="text-xs">题库里还没有年级</FieldDescription>
                )}
              </div>
            </Field>
          ) : (
            <>
              <Field orientation="responsive" className="py-3.5">
                <FieldContent>
                  <FieldTitle>科目</FieldTitle>
                  <FieldDescription className="text-xs leading-5">只从选中的科目抽题</FieldDescription>
                </FieldContent>
                <div className="flex min-w-0 flex-wrap gap-2">
                  {scopeSubjects.length ? (
                    scopeSubjects.map(v => (
                      <FieldLabel
                        key={v}
                        className="cursor-pointer rounded-lg border border-border/70 px-3 py-2 font-normal transition-colors hover:bg-muted/40 has-data-[state=checked]:border-primary/40 has-data-[state=checked]:bg-primary/5"
                      >
                        <Checkbox
                          checked={inScope('quizSubjects', v)}
                          onCheckedChange={() => toggleScope('quizSubjects', v)}
                        />
                        <span>{v}</span>
                      </FieldLabel>
                    ))
                  ) : (
                    <FieldDescription className="text-xs">题库里还没有科目</FieldDescription>
                  )}
                </div>
              </Field>
              <Field orientation="responsive" className="py-3.5">
                <FieldContent>
                  <FieldTitle>年级</FieldTitle>
                </FieldContent>
                <div className="flex min-w-0 flex-wrap gap-2">
                  {scopeLevels.length ? (
                    scopeLevels.map(v => (
                      <FieldLabel
                        key={v}
                        className="cursor-pointer rounded-lg border border-border/70 px-3 py-2 font-normal transition-colors hover:bg-muted/40 has-data-[state=checked]:border-primary/40 has-data-[state=checked]:bg-primary/5"
                      >
                        <Checkbox
                          checked={inScope('quizLevels', v)}
                          onCheckedChange={() => toggleScope('quizLevels', v)}
                        />
                        <span>{v}</span>
                      </FieldLabel>
                    ))
                  ) : (
                    <FieldDescription className="text-xs">题库里还没有年级</FieldDescription>
                  )}
                </div>
              </Field>
            </>
          )}
        </FieldGroup>
      </FieldSet>
    </Panel>
  );
}
