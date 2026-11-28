import { BookX, ChartColumn, ChevronRight, Clock3, Pause, Play, Sparkles } from 'lucide-react';
import { ClearExtraPoolButton } from '@/components/ClearExtraPoolButton';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';

type Props = {
  sessionId: number;
  supervisionEnabled: boolean;
  config: any;
  learning: any;
  study: any;
  pending: boolean;
  onOpenUnlock: () => void;
  onOpenLock: () => void;
  onOpenCredit: () => void;
};

export default function DeviceControlCard({
  sessionId,
  supervisionEnabled,
  config,
  learning,
  study,
  pending,
  onOpenUnlock,
  onOpenLock,
  onOpenCredit,
}: Props) {
  return (
    <Card className="shadow-sm">
      <CardHeader>
        <CardTitle className="text-sm">设备控制</CardTitle>
        <CardDescription>{supervisionEnabled ? '临时调整会自动恢复' : '快捷进入学习管理'}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-1 px-3">
        {supervisionEnabled ? (
          <>
            <Button
              variant="ghost"
              className="h-auto w-full justify-start gap-3 px-3 py-3"
              disabled={pending}
              onClick={onOpenUnlock}
            >
              <span className="flex size-5 items-center justify-center text-muted-foreground">
                <Play className="size-4" />
              </span>
              <span className="text-start">
                <span className="block text-sm font-medium">临时解锁</span>
                <span className="block text-xs font-normal text-muted-foreground">到时自动恢复学习规则</span>
              </span>
              <ChevronRight className="ms-auto" />
            </Button>
            <Button
              variant="ghost"
              className="h-auto w-full justify-start gap-3 px-3 py-3"
              disabled={pending}
              onClick={onOpenLock}
            >
              <span className="flex size-5 items-center justify-center text-muted-foreground">
                <Pause className="size-4" />
              </span>
              <span className="text-start">
                <span className="block text-sm font-medium">暂停使用</span>
                <span className="block text-xs font-normal text-muted-foreground">设置设备暂停时长</span>
              </span>
              <ChevronRight className="ms-auto" />
            </Button>
            <Button
              variant="ghost"
              className="h-auto w-full justify-start gap-3 px-3 py-3"
              disabled={pending}
              onClick={onOpenCredit}
            >
              <span className="flex size-5 items-center justify-center text-muted-foreground">
                <Clock3 className="size-4" />
              </span>
              <span className="text-start">
                <span className="block text-sm font-medium">调整剩余时间</span>
                <span className="block text-xs font-normal text-muted-foreground">临时奖励或扣减今日时长</span>
              </span>
              <ChevronRight className="ms-auto" />
            </Button>
          </>
        ) : (
          <>
            <Button
              variant="ghost"
              className="h-auto w-full justify-start gap-3 px-3 py-3"
              onClick={() => (location.hash = '/bank')}
            >
              <span className="flex size-5 items-center justify-center text-muted-foreground">
                <Sparkles className="size-4" />
              </span>
              <span className="text-start">
                <span className="block text-sm font-medium">AI 错题举一反三出题</span>
                <span className="block text-xs font-normal text-muted-foreground">针对薄弱考点一键生成巩固练习</span>
              </span>
              <ChevronRight className="ms-auto" />
            </Button>
            <Button
              variant="ghost"
              className="h-auto w-full justify-start gap-3 px-3 py-3"
              onClick={() => (location.hash = '/wrong')}
            >
              <span className="flex size-5 items-center justify-center text-muted-foreground">
                <BookX className="size-4" />
              </span>
              <span className="text-start">
                <span className="block text-sm font-medium">查看错题库</span>
                <span className="block text-xs font-normal text-muted-foreground">
                  当前 {study.wrongCount || 0} 道错题，可一键加入加练池
                </span>
              </span>
              <ChevronRight className="ms-auto" />
            </Button>
            <Button
              variant="ghost"
              className="h-auto w-full justify-start gap-3 px-3 py-3"
              onClick={() => (location.hash = '/records')}
            >
              <span className="flex size-5 items-center justify-center text-muted-foreground">
                <ChartColumn className="size-4" />
              </span>
              <span className="text-start">
                <span className="block text-sm font-medium">查看学情与记忆曲线</span>
                <span className="block text-xs font-normal text-muted-foreground">掌握度分布、顽固错题与复习预测</span>
              </span>
              <ChevronRight className="ms-auto" />
            </Button>
          </>
        )}
      </CardContent>
      <Separator />
      <CardContent className="space-y-2 py-4 text-sm">
        <div className="flex items-center justify-between gap-4">
          <span className="text-muted-foreground">每组题量</span>
          <span>{config?.questionsPerRound || 0} 题</span>
        </div>
        <div className="flex items-center justify-between gap-4">
          <span className="text-muted-foreground">{supervisionEnabled ? '过关奖励' : '自动复习范围'}</span>
          <span>
            {!supervisionEnabled
              ? config?.spacedScope === 'wrong_only'
                ? '只复习错题'
                : '全部题目'
              : config?.difficultyRewardEnabled
                ? '按难度奖励'
                : `${config?.sessionMinutes || 0} 分钟`}
          </span>
        </div>
        <div className="flex items-center justify-between gap-2 pt-1">
          <span className="text-muted-foreground">
            待加练：<strong className="font-medium text-foreground tabular-nums">{learning.extraPool || 0}</strong> 题
          </span>
          <ClearExtraPoolButton
            sessionId={sessionId}
            count={Number(learning.extraPool) || 0}
            size="sm"
            className="h-7 px-2 text-xs"
          />
        </div>
      </CardContent>
      <CardFooter className="border-t pt-4">
        <Button variant="ghost" size="sm" onClick={() => (location.hash = '/rules')}>
          调整学习计划
          <ChevronRight />
        </Button>
      </CardFooter>
    </Card>
  );
}
