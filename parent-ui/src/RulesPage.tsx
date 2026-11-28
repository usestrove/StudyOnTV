import { useEffect, useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, GraduationCap } from 'lucide-react';
import { toast } from 'sonner';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { FieldError } from '@/components/ui/field';
import { SaveStatus, type SaveState } from '@/components/ui/save-status';
import { Switch } from '@/components/ui/switch';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { api } from './api';
import MemoryRulesTab from './components/rules/MemoryRulesTab';
import {
  Choice,
  NumberStepper,
  Panel,
  Row,
  collectErrorMessages,
  fieldLabels,
} from './components/rules/RulePrimitives';
import ScopeRulesTab from './components/rules/ScopeRulesTab';
import TaskRulesTab from './components/rules/TaskRulesTab';
import TimeRulesTab from './components/rules/TimeRulesTab';
import { normalizeRules, ruleSchema, type Rules } from './schema';

export { Choice, Panel, Row };

export default function RulesPage({ state, sessionId }: { state: any; sessionId: number }) {
  const initial = normalizeRules(state?.config);
  const form = useForm<Rules>({ resolver: zodResolver(ruleSchema), defaultValues: initial });
  const c = form.watch();
  const client = useQueryClient();
  const [tab, setTab] = useState<'task' | 'time' | 'scope' | 'more'>('task');
  const [gradeSubjectMode, setGradeSubjectMode] = useState(
    () => Object.keys(initial.gradeSubjects || {}).length > 0,
  );
  const bankMeta = useQuery({
    queryKey: ['bank-meta', sessionId],
    queryFn: () => api('/api/questions/meta'),
  });
  const device = useQuery({ queryKey: ['device', sessionId], queryFn: () => api('/api/device') });
  const scopeSubjects: string[] = bankMeta.data?.subjects || [];
  const scopeLevels: string[] = bankMeta.data?.levels || [];
  const scopeBanks: string[] = bankMeta.data?.banks || [];
  const change = (key: any, value: any) =>
    form.setValue(key, value, { shouldDirty: true, shouldValidate: true });

  const configSnapshot = state?.config ? JSON.stringify(state.config) : '';
  const activeChildId = state?.child?.id ?? '';
  const lastChildId = useRef(activeChildId);
  useEffect(() => {
    if (state?.config && !form.formState.isDirty) {
      const normalized = normalizeRules(state.config);
      form.reset(normalized);
      const hasRemote = Object.keys(normalized.gradeSubjects || {}).length > 0;
      if (lastChildId.current !== activeChildId) {
        lastChildId.current = activeChildId;
        setGradeSubjectMode(hasRemote);
      } else if (hasRemote) {
        setGradeSubjectMode(true);
      }
    }
  }, [activeChildId, configSnapshot]);

  // 失败后记住失败时的值，避免 isPending 变化触发同一个值反复自动重试（网络断开时每 0.8 秒弹一次错误）。
  const failedSignature = useRef('');
  const validationToast = useRef('');
  const save = useMutation({
    mutationFn: (value: Rules) => api('/api/config', 'PUT', value),
    onSuccess: (r, body) => {
      failedSignature.current = '';
      if (JSON.stringify(form.getValues()) === JSON.stringify(body)) {
        form.reset(normalizeRules(r.config));
      }
      client.invalidateQueries({ queryKey: ['state', sessionId] });
    },
    onError: (e: any, body) => {
      failedSignature.current = JSON.stringify(body);
      toast.error(e.message);
    },
  });

  useEffect(() => {
    if (!form.formState.isDirty || save.isPending) return;
    const signature = JSON.stringify(c);
    if (failedSignature.current === signature) return;
    const timer = window.setTimeout(async () => {
      if (await form.trigger()) {
        validationToast.current = '';
        save.mutate(form.getValues());
      } else {
        const parsed = ruleSchema.safeParse(form.getValues());
        const text =
          (!parsed.success ? parsed.error.issues[0]?.message : undefined) ||
          collectErrorMessages(form.formState.errors)[0]?.text;
        if (text && validationToast.current !== text) {
          validationToast.current = text;
          toast.error('无法自动保存：' + text);
        }
      }
    }, 800);
    return () => window.clearTimeout(timer);
  }, [JSON.stringify(c), form.formState.isDirty, save.isPending]);

  const toggle = (key: any) => (
    <Controller
      name={key}
      control={form.control}
      render={({ field }) => (
        <Switch
          aria-label={fieldLabels[key] || key}
          className="w-8! shrink-0"
          checked={!!field.value}
          onCheckedChange={field.onChange}
        />
      )}
    />
  );

  const number = (key: any, min: number, max: number) => {
    const current = Number((c as any)[key]);
    return (
      <NumberStepper
        key={key}
        value={Number.isFinite(current) ? current : min}
        min={min}
        max={max}
        label={fieldLabels[key] || key}
        onChange={value => change(key, value)}
      />
    );
  };

  const errorMessages = collectErrorMessages(form.formState.errors).map(item => (
    <FieldError key={item.key}>{item.text}</FieldError>
  ));
  const saveState: SaveState = save.isPending
    ? 'saving'
    : save.isError
      ? 'error'
      : form.formState.isDirty
        ? 'pending'
        : 'saved';
  const statusNode = <SaveStatus state={saveState} />;
  const supervisionEnabled = c.supervisionEnabled !== false;

  return (
    <form
      onSubmit={form.handleSubmit(
        v => save.mutate(v),
        () => toast.error('请检查设置中的输入'),
      )}
      className="rules-form space-y-5"
    >
      <Card className="gap-0 border-primary/20 bg-gradient-to-br from-primary/[0.03] via-card to-card py-4 shadow-xs">
        <CardContent className="space-y-3 px-4 sm:px-5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2 text-sm font-semibold">
              <GraduationCap className="size-4 text-primary" />
              运行模式
            </div>
            {statusNode}
          </div>
          <div className="grid gap-2.5 sm:grid-cols-2">
            <button
              type="button"
              onClick={() => change('supervisionEnabled', false)}
              className={`flex flex-col items-start gap-1 rounded-xl border p-3 text-left transition-colors ${
                !supervisionEnabled
                  ? 'border-primary bg-primary/5 ring-1 ring-primary/30'
                  : 'border-border/70 bg-background/60 hover:bg-muted/40'
              }`}
            >
              <div className="flex w-full items-center justify-between gap-2">
                <span className="text-sm font-semibold">📚 只学习，不锁屏</span>
                {!supervisionEnabled && (
                  <Badge variant="default" className="h-5 px-1.5 text-[11px]">
                    <Check className="me-0.5 size-3" />
                    启用中
                  </Badge>
                )}
              </div>
              <span className="text-xs leading-5 text-muted-foreground">
                不限制看电视，孩子随时打开刷题、背诵和复习错题
              </span>
            </button>
            <button
              type="button"
              onClick={() => change('supervisionEnabled', true)}
              className={`flex flex-col items-start gap-1 rounded-xl border p-3 text-left transition-colors ${
                supervisionEnabled
                  ? 'border-primary bg-primary/5 ring-1 ring-primary/30'
                  : 'border-border/70 bg-background/60 hover:bg-muted/40'
              }`}
            >
              <div className="flex w-full items-center justify-between gap-2">
                <span className="text-sm font-semibold">⏳ 先答题，再看电视</span>
                {supervisionEnabled && (
                  <Badge variant="default" className="h-5 px-1.5 text-[11px]">
                    <Check className="me-0.5 size-3" />
                    启用中
                  </Badge>
                )}
              </div>
              <span className="text-xs leading-5 text-muted-foreground">
                答对题赚取观看时间，到点自动弹题并限制每日时长
              </span>
            </button>
          </div>
        </CardContent>
      </Card>

      <Tabs value={tab} onValueChange={value => setTab(value as typeof tab)} className="gap-5">
        <TabsList className="flex w-full max-w-full flex-nowrap justify-start gap-1 overflow-x-auto sm:justify-center">
          {(
            [
              ['task', '答题与奖励'],
              ['scope', '出题范围与顺序'],
              ['more', '复习与加练'],
              ['time', supervisionEnabled ? '时间与防护' : '时间管控（未启用）'],
            ] as const
          ).map(([id, label]) => (
            <TabsTrigger
              key={id}
              type="button"
              value={id}
              className="shrink-0 whitespace-nowrap max-[420px]:px-2 max-[420px]:text-xs"
            >
              {label}
            </TabsTrigger>
          ))}
        </TabsList>

        <TabsContent value="task" className="mt-0">
          <TaskRulesTab
            form={form}
            c={c}
            supervisionEnabled={supervisionEnabled}
            statusNode={tab === 'task' ? statusNode : undefined}
            change={change}
            toggle={toggle}
            number={number}
          />
        </TabsContent>

        <TabsContent value="scope" className="mt-0">
          <ScopeRulesTab
            c={c}
            scopeSubjects={scopeSubjects}
            scopeLevels={scopeLevels}
            scopeBanks={scopeBanks}
            gradeSubjectMode={gradeSubjectMode}
            setGradeSubjectMode={setGradeSubjectMode}
            statusNode={tab === 'scope' ? statusNode : undefined}
            change={change}
            toggle={toggle}
          />
        </TabsContent>

        <TabsContent value="time" className="mt-0 space-y-5">
          <TimeRulesTab
            form={form}
            c={c}
            supervisionEnabled={supervisionEnabled}
            deviceData={device.data}
            statusNode={tab === 'time' ? statusNode : undefined}
            change={change}
            toggle={toggle}
            number={number}
          />
        </TabsContent>

        {/* forceMount：保持子组件状态稳定，按 data-state 隐藏非激活页签 */}
        <TabsContent value="more" forceMount className="mt-0 space-y-5 data-[state=inactive]:hidden">
          <MemoryRulesTab
            form={form}
            c={c}
            state={state}
            sessionId={sessionId}
            statusNode={tab === 'more' ? statusNode : undefined}
            toggle={toggle}
            number={number}
          />
        </TabsContent>
      </Tabs>
      {errorMessages.length > 0 && <div className="space-y-2">{errorMessages}</div>}
    </form>
  );
}
