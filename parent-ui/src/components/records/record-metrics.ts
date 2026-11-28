export type HistoryDay = {
  label?: string;
  watch?: number | string;
  answered?: number | string;
  accuracy?: number | string;
  rounds?: number | string;
};

export type EventItem = {
  type?: string;
  payload?: Record<string, any>;
  ts?: number | string;
};

export function toNumber(value: unknown) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

export function clampPercent(value: unknown) {
  return Math.min(100, Math.max(0, Math.round(toNumber(value))));
}

export function formatDuration(minutes: number) {
  if (minutes >= 60) {
    const hours = minutes / 60;
    return {
      value: String(hours >= 10 ? Math.round(hours) : Math.round(hours * 10) / 10),
      unit: '小时',
    };
  }
  return { value: String(Math.round(minutes)), unit: '分钟' };
}

export function eventLabel(event: EventItem) {
  const payload = event.payload || {};
  if (event.type === 'answer') return payload.correct ? '答题正确' : '答题错误';
  const labels: Record<string, string> = {
    quiz: '完成任务',
    quiz_pass: `解锁 ${payload.minutes || 0} 分钟`,
    extra_added: `加练池增加 ${payload.count || 0} 题`,
    review_submitted: '提交作答，等待批改',
    review_graded: `作答评分 ${payload.score || 0} 分`,
    question_added: '新增题目',
  };
  return labels[event.type || ''] || event.type || '学习动态';
}

export function formatEventTime(value: unknown) {
  const timestamp = Number(value);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '时间未知';
  return new Date(timestamp).toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function accuracyTone(value: number, hasAnswers: boolean) {
  if (!hasAnswers) {
    return {
      text: 'text-muted-foreground',
      dot: 'bg-muted-foreground/35',
    };
  }
  if (value >= 85) {
    return {
      text: 'text-emerald-600 dark:text-emerald-400',
      dot: 'bg-emerald-500',
    };
  }
  if (value >= 60) {
    return {
      text: 'text-amber-600 dark:text-amber-400',
      dot: 'bg-amber-500',
    };
  }
  return {
    text: 'text-rose-600 dark:text-rose-400',
    dot: 'bg-rose-500',
  };
}

export function eventTone(type: string | undefined, payload: Record<string, any> | undefined) {
  if (type === 'answer') return payload?.correct ? 'bg-emerald-500' : 'bg-rose-500';
  if (type === 'quiz_pass') return 'bg-amber-500';
  if (type === 'quiz') return 'bg-sky-500';
  return 'bg-slate-400';
}
