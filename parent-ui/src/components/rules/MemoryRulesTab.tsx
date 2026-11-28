import type { ReactNode } from 'react';
import { Controller, type UseFormReturn } from 'react-hook-form';
import { Repeat2 } from 'lucide-react';
import { ClearExtraPoolButton } from '@/components/ClearExtraPoolButton';
import { Field, FieldContent, FieldDescription, FieldGroup, FieldSet, FieldTitle } from '@/components/ui/field';
import type { Rules } from '@/schema';
import { Choice, FIELD_GROUP_CLASS, Panel } from './RulePrimitives';

type Props = {
  form: UseFormReturn<Rules>;
  c: Rules;
  state: any;
  sessionId: number;
  statusNode?: ReactNode;
  toggle: (key: any) => ReactNode;
  number: (key: any, min: number, max: number) => ReactNode;
};

export default function MemoryRulesTab({
  form,
  c,
  state,
  sessionId,
  statusNode,
  toggle,
  number,
}: Props) {
  return (
    <>
      <Panel title="自动复习与错题加练" icon={Repeat2} status={statusNode}>
        <FieldSet className="gap-0">
          <FieldGroup className={FIELD_GROUP_CLASS}>
            <Field orientation="responsive" className="py-3.5">
              <FieldContent>
                <FieldTitle>到点自动复习旧题</FieldTitle>
                <FieldDescription className="text-xs leading-5">
                  按艾宾浩斯遗忘规律，快忘的时候自动安排再考一遍；答对越稳，下次复习间隔越长
                </FieldDescription>
              </FieldContent>
              {toggle('spacedRepetitionEnabled')}
            </Field>
            {c.spacedRepetitionEnabled && (
              <>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>哪些题需要自动复习</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      题库很大、只想专注把错题清零时，选「只复习做错过的题」
                    </FieldDescription>
                  </FieldContent>
                  <Controller
                    name="spacedScope"
                    control={form.control}
                    render={({ field }) => (
                      <Choice
                        label="哪些题需要自动复习"
                        value={field.value || 'all'}
                        onChange={field.onChange}
                        options={[
                          ['all', '全部题目'],
                          ['wrong_only', '只复习做错过的题'],
                        ]}
                      />
                    )}
                  />
                </Field>
                <Field className="py-3.5">
                  <FieldDescription>
                    已进入复习计划 {state?.repetition?.studied || 0} 题，今日到期复习 {state?.repetition?.due || 0} 题。
                  </FieldDescription>
                </Field>
              </>
            )}
            <Field orientation="responsive" className="py-3.5">
              <FieldContent>
                <FieldTitle>答错自动加练</FieldTitle>
                <FieldDescription className="text-xs leading-5">
                  每累计答错几题，就自动多加几题练习，防止孩子随便乱蒙
                </FieldDescription>
              </FieldContent>
              {toggle('extraEnabled')}
            </Field>
            {c.extraEnabled && (
              <>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>每累计答错（题）</FieldTitle>
                  </FieldContent>
                  {number('wrongThreshold', 1, 100)}
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>自动多加练（题）</FieldTitle>
                  </FieldContent>
                  {number('extraQuestions', 1, 100)}
                </Field>
              </>
            )}
            <Field orientation="responsive" className="py-3.5">
              <FieldContent>
                <FieldTitle>当前待完成加练</FieldTitle>
                <FieldDescription className="text-xs leading-5">
                  还有 {Number(state?.learning?.extraPool) || 0} 题待加练；积压过多时可一键清零，不会删原题和错题记录
                </FieldDescription>
              </FieldContent>
              <ClearExtraPoolButton sessionId={sessionId} count={Number(state?.learning?.extraPool) || 0} />
            </Field>
          </FieldGroup>
        </FieldSet>
      </Panel>
      <p className="text-xs leading-5 text-muted-foreground">
        题目朗读设置请前往
        <a className="mx-1 underline underline-offset-4" href="#/settings/speech">
          设置 → 朗读
        </a>
        。
      </p>
    </>
  );
}
