import { lazy, Suspense, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Brain, Clock3 } from 'lucide-react';
import { api } from './api';
import type { AiGeneratePreset } from './components/bank/AiGenerateDialog';
import CoverageCard from './components/records/CoverageCard';
import LearningDiagnosisSection from './components/records/LearningDiagnosisSection';
import LearningTrendsSection, { type DailyPracticePoint } from './components/records/LearningTrendsSection';
import RecordsOverviewCard from './components/records/RecordsOverviewCard';
import UsageDetailsSection from './components/records/UsageDetailsSection';
import { clampPercent, toNumber, type EventItem, type HistoryDay } from './components/records/record-metrics';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

const AiGenerateDialog = lazy(() => import('./components/bank/AiGenerateDialog'));

export default function RecordsPage({ sessionId }: { sessionId: number }) {
  const [aiPreset, setAiPreset] = useState<AiGeneratePreset | null>(null);
  const analytics = useQuery({
    queryKey: ['analytics', sessionId],
    queryFn: () => api('/api/analytics?days=14'),
    refetchInterval: 20000,
  });
  const history = useQuery({
    queryKey: ['history', sessionId],
    queryFn: () => api('/api/history?days=7'),
    refetchInterval: 15000,
  });
  const events = useQuery({
    queryKey: ['events', sessionId],
    queryFn: () => api('/api/events?limit=100'),
    refetchInterval: 10000,
  });

  const days: HistoryDay[] = history.data?.items || [];
  const recentEvents: EventItem[] = events.data?.items?.slice(0, 20) || [];
  const totalWatch = days.reduce((sum, day) => sum + Math.max(0, toNumber(day.watch)), 0);
  const historyAnswered = days.reduce((sum, day) => sum + Math.max(0, toNumber(day.answered)), 0);
  const weightedAccuracy =
    historyAnswered > 0
      ? clampPercent(
          days.reduce(
            (sum, day) => sum + Math.max(0, toNumber(day.answered)) * clampPercent(day.accuracy),
            0,
          ) / historyAnswered,
        )
      : null;

  const aData = analytics.data || {};
  const overview = aData.overview || {};
  const maturity = aData.maturity || {};
  const dailyRaw: any[] = Array.isArray(aData.daily) ? aData.daily : [];
  const bySubject: any[] = Array.isArray(aData.bySubject) ? aData.bySubject : [];
  const hardQuestions: any[] = Array.isArray(aData.hardQuestions) ? aData.hardQuestions : [];
  const forecast: any[] = Array.isArray(aData.forecast) ? aData.forecast : [];

  // 归一化近 14 天每日数据（从远到近排序展示）
  const daily14: DailyPracticePoint[] = (dailyRaw.length ? dailyRaw : days.slice().reverse()).map((d: any) => ({
    label: String(d?.label || d?.date || '—').replace(/^\d{4}-/, ''),
    answered: Math.max(0, toNumber(d?.answered)),
    correct: Math.max(0, toNumber(d?.correct)),
    accuracy: clampPercent(
      d?.accuracy ?? (toNumber(d?.answered) > 0 ? (toNumber(d?.correct) * 100) / toNumber(d?.answered) : 0),
    ),
  }));

  // 计算近 7 天 vs 前 7 天正确率变化趋势（优先使用后端值，否则按 daily14 兜底推算）
  const computeDeltaFallback = (): number | null => {
    if (overview.accuracyDelta !== undefined && overview.accuracyDelta !== null) {
      return Math.round(toNumber(overview.accuracyDelta));
    }
    const hasPrevAnswers = daily14.length >= 8 && daily14.slice(0, daily14.length - 7).some(d => d.answered > 0);
    if (hasPrevAnswers && overview.recent7dAccuracy != null && overview.prev7dAccuracy != null) {
      return Math.round(toNumber(overview.recent7dAccuracy) - toNumber(overview.prev7dAccuracy));
    }
    if (daily14.length >= 8) {
      const prevSlice = daily14.slice(0, Math.max(1, daily14.length - 7));
      const currSlice = daily14.slice(-7);
      const prevAns = prevSlice.reduce((s, x) => s + x.answered, 0);
      const currAns = currSlice.reduce((s, x) => s + x.answered, 0);
      if (prevAns > 0 && currAns > 0) {
        const prevAcc = prevSlice.reduce((s, x) => s + x.answered * x.accuracy, 0) / prevAns;
        const currAcc = currSlice.reduce((s, x) => s + x.answered * x.accuracy, 0) / currAns;
        return Math.round(currAcc - prevAcc);
      }
    }
    return null;
  };
  const accuracyDelta = computeDeltaFallback();

  const totalAnsweredAll = Math.max(historyAnswered, toNumber(overview.totalAnswered ?? overview.answered));
  const rawOverviewAcc = overview.accuracyPercent ?? overview.accuracy;
  const overallAccuracy =
    rawOverviewAcc !== undefined && rawOverviewAcc !== null && totalAnsweredAll > 0
      ? clampPercent(rawOverviewAcc)
      : weightedAccuracy;
  const streakDays = Math.max(0, toNumber(overview.streakDays ?? overview.streak));
  const dueCount = Math.max(0, toNumber(overview.dueNow ?? overview.dueCount ?? overview.due));
  const wrongCount = Math.max(0, toNumber(overview.wrongCount ?? overview.wrong));

  const matUnseen = Math.max(0, toNumber(maturity.unseen));
  const matLearning = Math.max(0, toNumber(maturity.learning));
  const matYoung = Math.max(0, toNumber(maturity.young));
  const matMature = Math.max(0, toNumber(maturity.mature));
  const maturityStats = {
    unseen: matUnseen,
    learning: matLearning,
    young: matYoung,
    mature: matMature,
    total: matUnseen + matLearning + matYoung + matMature,
  };

  const pending = history.isPending && !history.data && analytics.isPending && !analytics.data;
  const refreshing = history.isFetching || events.isFetching || analytics.isFetching;

  function refreshAll() {
    void analytics.refetch();
    void history.refetch();
    void events.refetch();
  }

  return (
    <div className="space-y-4 pb-6">
      <RecordsOverviewCard
        pending={pending}
        refreshing={refreshing}
        streakDays={streakDays}
        totalAnsweredAll={totalAnsweredAll}
        overallAccuracy={overallAccuracy}
        accuracyDelta={accuracyDelta}
        dueCount={dueCount}
        wrongCount={wrongCount}
        onRefresh={refreshAll}
        onPracticeWrong={() => setAiPreset({ fromWrongBook: true })}
      />

      <Tabs defaultValue="learning" className="space-y-4">
        <TabsList className="grid w-full grid-cols-2 sm:w-auto sm:inline-flex">
          <TabsTrigger value="learning">
            <Brain />
            学情诊断与记忆报告
          </TabsTrigger>
          <TabsTrigger value="usage">
            <Clock3 />
            屏幕时长与答题明细
          </TabsTrigger>
        </TabsList>

        <TabsContent value="learning" className="m-0 space-y-4">
          <LearningDiagnosisSection
            maturity={maturityStats}
            bySubject={bySubject}
            hardQuestions={hardQuestions}
            onSelectAiPreset={setAiPreset}
          />
          <LearningTrendsSection daily14={daily14} forecast={forecast} />
          <CoverageCard sessionId={sessionId} />
        </TabsContent>

        <TabsContent value="usage" className="m-0 space-y-4">
          <UsageDetailsSection
            days={days}
            recentEvents={recentEvents}
            totalWatch={totalWatch}
            pending={pending}
            historyPending={history.isPending}
            historyError={history.isError}
            eventsPending={events.isPending}
            eventsError={events.isError}
          />
        </TabsContent>
      </Tabs>

      {aiPreset && (
        <Suspense fallback={null}>
          <AiGenerateDialog
            open={!!aiPreset}
            onOpenChange={open => {
              if (!open) setAiPreset(null);
            }}
            sessionId={sessionId}
            initialPreset={aiPreset}
          />
        </Suspense>
      )}
    </div>
  );
}
