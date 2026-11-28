import { useEffect, useState, type ReactNode } from 'react';
import { Controller, type UseFormReturn } from 'react-hook-form';
import { ChevronDown, Sparkles } from 'lucide-react';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { Field, FieldContent, FieldDescription, FieldGroup, FieldSet, FieldTitle } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import type { Rules } from '@/schema';
import { Choice, FIELD_GROUP_CLASS, Panel } from './RulePrimitives';

const parseVideoLines = (text: string) =>
  text
    .split('\n')
    .map(s => s.trim())
    .filter(Boolean);

type Props = {
  form: UseFormReturn<Rules>;
  c: Rules;
  supervisionEnabled: boolean;
  statusNode?: ReactNode;
  change: (key: any, value: any) => void;
  toggle: (key: any) => ReactNode;
  number: (key: any, min: number, max: number) => ReactNode;
};

export default function TaskRulesTab({
  form,
  c,
  supervisionEnabled,
  statusNode,
  change,
  toggle,
  number,
}: Props) {
  const [videoText, setVideoText] = useState(() => (c.educationalVideos || []).join('\n'));
  const [videoFocused, setVideoFocused] = useState(false);
  const videosKey = (c.educationalVideos || []).join('\n');
  useEffect(() => {
    if (!videoFocused) setVideoText(videosKey);
  }, [videosKey, videoFocused]);

  const rewardAndQuotaFields = (
    <FieldGroup className={FIELD_GROUP_CLASS}>
      <Field orientation="responsive" className="py-3.5">
        <FieldContent>
          <FieldTitle>按题目难度给不同奖励</FieldTitle>
          <FieldDescription className="text-xs leading-5">
            关闭时：每次过关固定奖励 N 分钟；开启时：1–5 星题目每答对一题按星级奖励不同分钟
          </FieldDescription>
        </FieldContent>
        {toggle('difficultyRewardEnabled')}
      </Field>

      {c.difficultyRewardEnabled ? (
        [1, 2, 3, 4, 5].map(star => (
          <Field key={star} orientation="responsive" className="py-3.5">
            <FieldContent>
              <FieldTitle>{'★'.repeat(star)} 每题奖励（分钟）</FieldTitle>
            </FieldContent>
            <Input
              className="w-24 text-center"
              aria-label={star + '星奖励'}
              type="number"
              min={0.1}
              max={120}
              step={0.1}
              {...form.register(`difficultyRewards.${star}`, { valueAsNumber: true })}
            />
          </Field>
        ))
      ) : (
        <Field orientation="responsive" className="py-3.5">
          <FieldContent>
            <FieldTitle>过关奖励时长（分钟）</FieldTitle>
            <FieldDescription className="text-xs leading-5">每次答题过关后可看电视或玩平板多久</FieldDescription>
          </FieldContent>
          {number('sessionMinutes', 1, 120)}
        </Field>
      )}

      <Field orientation="responsive" className="py-3.5">
        <FieldContent>
          <FieldTitle>每天最多看多久（分钟）</FieldTitle>
          <FieldDescription className="text-xs leading-5">用完后今天不再解锁，凌晨 0 点重置</FieldDescription>
        </FieldContent>
        <div className="flex items-center gap-3">
          {toggle('dailyLimitEnabled')}
          {c.dailyLimitEnabled && number('dailyMinutes', 1, 480)}
        </div>
      </Field>

      <Field orientation="responsive" className="py-3.5">
        <FieldContent>
          <FieldTitle>每天最多过关（次）</FieldTitle>
          <FieldDescription className="text-xs leading-5">达到上限后今天不再解锁</FieldDescription>
        </FieldContent>
        <div className="flex items-center gap-3">
          {toggle('roundsLimitEnabled')}
          {c.roundsLimitEnabled && number('roundsPerDay', 1, 100)}
        </div>
      </Field>
    </FieldGroup>
  );

  return (
    <Panel title="答题与奖励" icon={Sparkles} status={statusNode}>
      <FieldSet className="gap-0">
        <FieldGroup className={FIELD_GROUP_CLASS}>
          <Field orientation="responsive" className="py-3.5">
            <FieldContent>
              <FieldTitle>过关方式</FieldTitle>
              <FieldDescription className="text-xs leading-5">
                {c.playMode === 'streak'
                  ? '中途答错会重新计数，连续答对够数才过关'
                  : c.playMode === 'count'
                    ? '累计答对够数就过关，答错不重新计数'
                    : c.playMode === 'practice'
                      ? '只刷题不计分，也不发解锁时间'
                      : '做完一整组且正确率达标即可过关'}
              </FieldDescription>
            </FieldContent>
            <Controller
              name="playMode"
              control={form.control}
              render={({ field }) => (
                <Choice
                  value={field.value}
                  onChange={field.onChange}
                  label="过关方式"
                  options={[
                    ['round', '答完一组达标'],
                    ['streak', '连续答对 N 题'],
                    ['count', '累计答对 N 题'],
                    ['practice', '自由刷题（不计分）'],
                  ]}
                />
              )}
            />
          </Field>

          <Field orientation="responsive" className="py-3.5">
            <FieldContent>
              <FieldTitle>
                {c.playMode === 'streak'
                  ? '连续答对目标（题）'
                  : c.playMode === 'count'
                    ? '累计答对目标（题）'
                    : '每组题量'}
              </FieldTitle>
            </FieldContent>
            {number('questionsPerRound', 1, 100)}
          </Field>

          <Field orientation="responsive" className="py-3.5">
            <FieldContent>
              <FieldTitle>先背再测</FieldTitle>
              <FieldDescription className="text-xs leading-5">
                做题前先把题目和答案看一遍记住，再打乱顺序考一遍，适合生词、古诗文和新知识
              </FieldDescription>
            </FieldContent>
            {toggle('memorizeFirst')}
          </Field>

          {supervisionEnabled && rewardAndQuotaFields}
        </FieldGroup>
      </FieldSet>

      {!supervisionEnabled && (
        <Collapsible className="border-t border-border/60">
          <CollapsibleTrigger
            type="button"
            className="group flex items-center gap-1.5 py-3.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
          >
            <ChevronDown className="size-3 transition-transform group-data-[state=open]:rotate-180" />
            时长与奖励规则（切换到「先答题，再看电视」后生效）
          </CollapsibleTrigger>
          <CollapsibleContent>{rewardAndQuotaFields}</CollapsibleContent>
        </Collapsible>
      )}

      <Collapsible className="border-t border-border/60">
        <CollapsibleTrigger
          type="button"
          className="group flex items-center gap-1.5 py-3.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
        >
          <ChevronDown className="size-3 transition-transform group-data-[state=open]:rotate-180" />
          更多答题与显示设置
        </CollapsibleTrigger>
        <CollapsibleContent>
          <FieldSet className="gap-0">
            <FieldGroup className={FIELD_GROUP_CLASS}>
              <Field orientation="responsive" className="py-3.5">
                <FieldContent>
                  <FieldTitle>学习内容</FieldTitle>
                </FieldContent>
                <Controller
                  name="learningContent"
                  control={form.control}
                  render={({ field }) => (
                    <Choice
                      label="学习内容"
                      value={field.value}
                      onChange={field.onChange}
                      options={[
                        ['quiz', '答题'],
                        ['video', '公益视频'],
                        ['alternate', '视频与答题交替'],
                      ]}
                    />
                  )}
                />
              </Field>

              {c.learningContent !== 'quiz' && (
                <>
                  <Field orientation="responsive" className="py-3.5">
                    <FieldContent>
                      <FieldTitle>每次视频学习（分钟）</FieldTitle>
                    </FieldContent>
                    {number('videoMinutes', 1, 30)}
                  </Field>
                  <Field className="py-3.5">
                    <FieldContent>
                      <FieldTitle>公益视频直链</FieldTitle>
                      <FieldDescription className="text-xs leading-5">每行一个 HTTP/HTTPS 地址</FieldDescription>
                    </FieldContent>
                    <Textarea
                      aria-label="公益视频直链"
                      rows={4}
                      value={videoText}
                      onFocus={() => setVideoFocused(true)}
                      onChange={e => {
                        const raw = e.target.value;
                        setVideoText(raw);
                        change('educationalVideos', parseVideoLines(raw));
                      }}
                      onBlur={e => {
                        setVideoFocused(false);
                        setVideoText(parseVideoLines(e.target.value).join('\n'));
                      }}
                    />
                  </Field>
                </>
              )}

              {supervisionEnabled && (
                <>
                  <Field orientation="responsive" className="py-3.5">
                    <FieldContent>
                      <FieldTitle>每天免费看多久（分钟）</FieldTitle>
                      <FieldDescription className="text-xs leading-5">每天先免费看几分钟，用完再开始答题；0 为不送</FieldDescription>
                    </FieldContent>
                    {number('dailyFreeMinutes', 0, 120)}
                  </Field>

                  <Field orientation="responsive" className="py-3.5">
                    <FieldContent>
                      <FieldTitle>每次开机免费看多久（分钟）</FieldTitle>
                      <FieldDescription className="text-xs leading-5">
                        开机或唤醒时送几分钟，防止刚开电视就锁屏；0 为不送
                      </FieldDescription>
                    </FieldContent>
                    {number('bootFreeMinutes', 0, 120)}
                  </Field>
                </>
              )}

              {c.bootFreeMinutes > 0 && (
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>开机免费冷却时间（分钟）</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      两次领开机免费时长至少隔多久，防止反复开关机白看；0 为不限
                    </FieldDescription>
                  </FieldContent>
                  {number('bootFreeIntervalMinutes', 0, 1440)}
                </Field>
              )}

              {c.playMode === 'round' && (
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>过关所需正确率（%）</FieldTitle>
                  </FieldContent>
                  <Controller
                    name="passRatio"
                    control={form.control}
                    render={({ field }) => (
                      <Input
                        className="w-24 text-center"
                        type="number"
                        min={1}
                        max={100}
                        aria-label="过关所需正确率"
                        value={Number.isFinite(field.value) ? Math.round(Number(field.value) * 100) : ''}
                        onChange={e =>
                          field.onChange(e.target.value === '' ? NaN : Number(e.target.value) / 100)
                        }
                      />
                    )}
                  />
                </Field>
              )}

              <Field orientation="responsive" className="py-3.5">
                <FieldContent>
                  <FieldTitle>答错后停顿看解析（秒）</FieldTitle>
                  <FieldDescription className="text-xs leading-5">答错后停住几秒让孩子看清解析，防止乱点跳过；0 为不停留</FieldDescription>
                </FieldContent>
                {number('wrongReviewSeconds', 0, 300)}
              </Field>

              <Field orientation="responsive" className="py-3.5">
                <FieldContent>
                  <FieldTitle>打乱选项顺序</FieldTitle>
                  <FieldDescription className="text-xs leading-5">把 A/B/C/D 选项打乱，防止孩子死记选项位置</FieldDescription>
                </FieldContent>
                {toggle('shuffleOptions')}
              </Field>

              {supervisionEnabled && (
                <>
                  <Field orientation="responsive" className="py-3.5">
                    <FieldContent>
                      <FieldTitle>显示剩余时间角标</FieldTitle>
                      <FieldDescription className="text-xs leading-5">
                        看视频时在屏幕角落显示剩余分钟；最后一分钟闪烁
                      </FieldDescription>
                    </FieldContent>
                    {toggle('showCountdown')}
                  </Field>
                  {c.showCountdown && (
                    <Field orientation="responsive" className="py-3.5">
                      <FieldContent>
                        <FieldTitle>倒计时角标位置</FieldTitle>
                        <FieldDescription className="text-xs leading-5">
                          可避开电视右上角默认时间或底部字幕位置
                        </FieldDescription>
                      </FieldContent>
                      <Controller
                        name="countdownPosition"
                        control={form.control}
                        render={({ field }) => (
                          <Choice
                            label="倒计时角标位置"
                            value={field.value}
                            onChange={field.onChange}
                            options={[
                              ['top-right', '右上角'],
                              ['top-left', '左上角'],
                              ['bottom-right', '右下角'],
                              ['bottom-left', '左下角'],
                            ]}
                          />
                        )}
                      />
                    </Field>
                  )}
                </>
              )}

              <Field orientation="responsive" className="py-3.5">
                <FieldContent>
                  <FieldTitle>学习页字号（%）</FieldTitle>
                  <FieldDescription className="text-xs leading-5">75–200</FieldDescription>
                </FieldContent>
                {number('fontScale', 75, 200)}
              </Field>
            </FieldGroup>
          </FieldSet>
        </CollapsibleContent>
      </Collapsible>
    </Panel>
  );
}
