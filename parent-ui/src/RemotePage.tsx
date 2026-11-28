import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ChevronDown, ChevronRight, LockKeyhole, LockKeyholeOpen } from 'lucide-react';
import { toast } from 'sonner';
import { api } from './api';
import DeviceControlCard from './components/remote/DeviceControlCard';
import OperationModeCard from './components/remote/OperationModeCard';
import RemoteActionDialog, { type CustomRemoteAction } from './components/remote/RemoteActionDialog';
import RemoteStatusCards from './components/remote/RemoteStatusCards';
import { SevenDayWatchChart } from './components/remote/RemoteWatchCharts';
import { defaults } from './schema';
import { Button } from '@/components/ui/button';
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { FieldDescription } from '@/components/ui/field';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Toolbar } from '@/components/ui/toolbar';

const fmt = (seconds: number) => {
  const minutes = Math.ceil(seconds / 60);
  if (minutes >= 60) {
    return `${Math.floor(minutes / 60)} 小时${minutes % 60 ? ` ${minutes % 60} 分` : ''}`;
  }
  return `${minutes} 分钟`;
};

const reasonText: Record<string, string> = {
  quota: '今天的使用时间已用完',
  rounds: '今天的任务已全部完成',
  schedule: '不在可用时段',
  timeup: '需要先完成学习任务',
  rest: '强制休息中',
  feedback: '请先查看答题解析',
  '': '可以使用',
};

export default function RemotePage({ state, sessionId }: { state: any; sessionId: number }) {
  const client = useQueryClient();
  const reviews = useQuery({
    queryKey: ['reviews', sessionId],
    queryFn: () => api('/api/reviews'),
    refetchInterval: 15000,
  });
  const pendingCount = reviews.data?.items?.length || 0;
  const history = useQuery({
    queryKey: ['history', sessionId],
    queryFn: () => api('/api/history?days=7'),
  });
  const days = history.data?.items || [];
  const learning = state?.learning;
  const study = state?.study || {};
  const supervisionEnabled = state?.config?.supervisionEnabled !== false;
  const override = learning?.override;

  const [now, setNow] = useState(Date.now());
  const [base, setBase] = useState({ at: Date.now(), left: 0 });
  useEffect(() => {
    setBase({ at: Date.now(), left: override?.remainingSeconds || 0 });
  }, [override?.remainingSeconds, override?.at]);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const left = Math.max(0, base.left - Math.max(0, Math.floor((now - base.at) / 1000)));

  const switchMode = useMutation({
    mutationFn: (patch: Record<string, any>) =>
      api('/api/config', 'PUT', { ...defaults, ...(state?.config || {}), ...patch }),
    onSuccess: (result, patch) => {
      client.setQueryData(['state', sessionId], (old: any) =>
        old ? { ...old, config: result.config } : old,
      );
      client.invalidateQueries({ queryKey: ['state', sessionId] });
      if ('supervisionEnabled' in patch) {
        toast.success(
          patch.supervisionEnabled
            ? '已切换为「先答题，再看电视」'
            : '已切换为「只学习，不锁屏」',
        );
      } else {
        toast.success('学习配置已更新');
      }
    },
    onError: (error: any) => toast.error(error.message),
  });

  const run = useMutation({
    mutationFn: (body: { action: string; minutes?: number; label: string; untilShutdown?: boolean }) =>
      api('/api/remote', 'POST', {
        action: body.action,
        minutes: body.minutes || 0,
        untilShutdown: body.untilShutdown || false,
      }),
    onSuccess: (result, body) => {
      client.setQueryData(['state', sessionId], (old: any) =>
        old ? { ...old, learning: result.learning } : old,
      );
      client.invalidateQueries({ queryKey: ['state', sessionId] });
      toast.success(body.label);
    },
    onError: (error: any) => toast.error(error.message),
  });
  const go = (action: string, minutes: number, label: string) =>
    run.mutate({ action, minutes, label });
  const mode = override?.mode as 'unlock' | 'lock' | 'indefinite' | undefined;
  const untilShutdown = mode === 'unlock' && override?.untilShutdown === true;
  const title = !supervisionEnabled
    ? '只学习，不锁屏'
    : mode === 'indefinite'
      ? '管控已关闭（无限时）'
      : mode === 'unlock'
        ? '已临时解锁'
        : mode === 'lock'
          ? '已锁定'
          : learning?.free
            ? '自由时段'
            : learning?.reason
              ? `管控中 · ${reasonText[learning.reason] || '暂不可用'}`
              : '管控中 · 可以使用';

  const [adjustSign, setAdjustSign] = useState(1);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [showChart, setShowChart] = useState(false);
  const [custom, setCustom] = useState<CustomRemoteAction | null>(null);

  function submitCustom() {
    const minutes = Number(custom?.minutes);
    if (!custom) return;
    if (custom.mode === 'unlock' && custom.untilShutdown) {
      run.mutate({ action: 'unlock', minutes: 0, untilShutdown: true, label: '已临时解锁到本次关机' });
      setCustom(null);
      return;
    }
    if (custom.mode === 'credit') {
      if (!Number.isInteger(minutes) || minutes < 1 || minutes > 480) {
        return toast.error('请输入 1–480 分钟');
      }
      go(
        'credit',
        minutes * adjustSign,
        adjustSign > 0 ? `已加时 ${minutes} 分钟` : `已收走 ${minutes} 分钟`,
      );
      setCustom(null);
      return;
    }
    if (
      custom.mode !== 'indefinite' &&
      (!Number.isInteger(minutes) ||
        minutes < 1 ||
        minutes > (custom.mode === 'unlock' ? 1440 : 10080))
    ) {
      return toast.error(
        custom.mode === 'unlock' ? '请输入 1–1440 分钟' : '请输入 1–10080 分钟',
      );
    }
    go(
      custom.mode,
      minutes,
      custom.mode === 'unlock' ? `已解锁 ${fmt(minutes * 60)}` : `已锁定 ${fmt(minutes * 60)}`,
    );
    setCustom(null);
  }

  if (!learning) return <FieldDescription className="py-16 text-center">正在连接设备…</FieldDescription>;
  const used = Number(learning.usedMinutes || 0);
  const limit =
    learning.todayLimitMinutes === null
      ? null
      : Number(learning.todayLimitMinutes ?? state.config?.dailyMinutes ?? 40);
  const blocked =
    supervisionEnabled && (mode === 'lock' || (!mode && !learning.free && !!learning.reason));
  const unlimited = !supervisionEnabled || mode === 'indefinite';
  const remaining = mode ? left : (learning.remainingSeconds || 0) + (learning.freeQuotaSeconds || 0);
  const open = (action: 'unlock' | 'lock') => setCustom({ mode: action, minutes: '30' });
  const quickStudy = !!(state.config?.memorizeFirst || state.config?.spacedRepetitionEnabled);

  return (
    <div className="space-y-4 pb-6">
      <Toolbar aria-label="首页工具栏" className="flex-wrap items-center justify-between gap-3 px-0 py-1">
        <div className="min-w-0">
          <p className="text-xs font-medium tracking-wide text-muted-foreground">设备管理</p>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">今日概览</h1>
        </div>
        {supervisionEnabled && (
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={run.isPending}
              onClick={() => {
                setAdjustSign(1);
                setCustom({ mode: 'credit', minutes: '15' });
              }}
            >
              调整剩余时间
            </Button>
            {!mode && (
              <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
                <DialogTrigger asChild>
                  <Button variant="destructive" size="sm" disabled={run.isPending}>
                    <LockKeyholeOpen />
                    关闭管控
                  </Button>
                </DialogTrigger>
                <DialogContent className="max-w-md">
                  <DialogHeader>
                    <DialogTitle>确认关闭管控？</DialogTitle>
                    <DialogDescription>
                      关闭后孩子可以无限使用，直到您手动恢复。如果只想当刷题背诵软件用，建议直接切换上方的「只学习，不锁屏」。
                    </DialogDescription>
                  </DialogHeader>
                  <DialogFooter>
                    <Button variant="outline" onClick={() => setConfirmOpen(false)}>
                      取消
                    </Button>
                    <Button
                      variant="destructive"
                      disabled={run.isPending}
                      onClick={() => {
                        go('indefinite', 0, '管控已关闭');
                        setConfirmOpen(false);
                      }}
                    >
                      确认关闭
                    </Button>
                  </DialogFooter>
                </DialogContent>
              </Dialog>
            )}
            {mode === 'indefinite' && (
              <Button size="sm" disabled={run.isPending} onClick={() => go('cancel', 0, '管控已恢复')}>
                <LockKeyhole />
                恢复管控
              </Button>
            )}
          </div>
        )}
      </Toolbar>

      <OperationModeCard
        supervisionEnabled={supervisionEnabled}
        quickStudy={quickStudy}
        study={study}
        questionCount={state?.questions}
        pending={switchMode.isPending}
        onPatchConfig={patch => switchMode.mutate(patch)}
      />

      <RemoteStatusCards
        state={state}
        learning={learning}
        study={study}
        days={days}
        pendingCount={pendingCount}
        supervisionEnabled={supervisionEnabled}
        mode={mode}
        untilShutdown={untilShutdown}
        title={title}
        used={used}
        limit={limit}
        blocked={blocked}
        unlimited={unlimited}
        remaining={remaining}
        pending={run.isPending}
        onCancelOverride={() => go('cancel', 0, '已恢复学习规则')}
        onOpenLock={() => open('lock')}
      />

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="shadow-sm lg:col-span-2">
          <CardHeader>
            <CardTitle className="text-sm">使用时间</CardTitle>
            <CardAction>
              <Button variant="ghost" size="sm" aria-expanded={showChart} onClick={() => setShowChart(v => !v)}>
                <ChevronDown className={`transition-transform ${showChart ? 'rotate-180' : ''}`} />
                {showChart ? '收起' : '查看 7 天记录'}
              </Button>
            </CardAction>
          </CardHeader>
          {showChart && (
            <CardContent>
              <div className="min-w-0">
                {days.length ? (
                  <SevenDayWatchChart days={days} />
                ) : (
                  <FieldDescription className="py-16 text-center">暂无使用记录</FieldDescription>
                )}
              </div>
            </CardContent>
          )}
        </Card>

        <DeviceControlCard
          sessionId={sessionId}
          supervisionEnabled={supervisionEnabled}
          config={state.config}
          learning={learning}
          study={study}
          pending={run.isPending}
          onOpenUnlock={() => open('unlock')}
          onOpenLock={() => open('lock')}
          onOpenCredit={() => {
            setAdjustSign(1);
            setCustom({ mode: 'credit', minutes: '15' });
          }}
        />
      </div>

      <Card className="gap-0 overflow-hidden shadow-sm">
        <CardHeader>
          <CardTitle className="text-sm">学习记录</CardTitle>
          <CardAction>
            <Button variant="ghost" size="sm" onClick={() => (location.hash = '/records')}>
              查看全部
              <ChevronRight />
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent className="px-0">
          {days.length ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="ps-6">日期</TableHead>
                  <TableHead>答题数量</TableHead>
                  <TableHead>正确率</TableHead>
                  <TableHead>完成任务</TableHead>
                  <TableHead className="pe-6 text-end">使用时间</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {days.slice(0, 5).map((day: any) => (
                  <TableRow key={day.label}>
                    <TableCell className="ps-6">{day.label}</TableCell>
                    <TableCell className="text-muted-foreground">{day.answered} 题</TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <span className="text-xs tabular-nums text-muted-foreground">
                          <b className="font-medium">{day.accuracy}</b>%
                        </span>
                        <div className="h-1.5 w-14 overflow-hidden rounded-full bg-muted">
                          <div className="h-full rounded-full bg-emerald-500" style={{ width: `${day.accuracy}%` }} />
                        </div>
                      </div>
                    </TableCell>
                    <TableCell className="text-muted-foreground">{day.rounds} 组</TableCell>
                    <TableCell className="pe-6 text-end text-muted-foreground">
                      {Number(day.watch).toFixed(1)} 分钟
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <FieldDescription className="py-10 text-center">暂无学习记录</FieldDescription>
          )}
        </CardContent>
      </Card>

      <RemoteActionDialog
        custom={custom}
        adjustSign={adjustSign}
        balanceMinutes={Math.ceil(((learning.remainingSeconds || 0) + (learning.freeQuotaSeconds || 0)) / 60)}
        pending={run.isPending}
        onChangeCustom={setCustom}
        onChangeAdjustSign={setAdjustSign}
        onSubmit={submitCustom}
      />
    </div>
  );
}
