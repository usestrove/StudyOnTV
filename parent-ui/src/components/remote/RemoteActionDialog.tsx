import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ToolbarGroup } from '@/components/ui/toolbar';

export type CustomRemoteAction = {
  mode: 'unlock' | 'lock' | 'indefinite' | 'credit';
  minutes: string;
  untilShutdown?: boolean;
};

const minutesToMidnight = () => {
  const now = new Date();
  const end = new Date(now);
  end.setHours(24, 0, 0, 0);
  return Math.max(1, Math.round((Number(end) - Number(now)) / 60000));
};

type Props = {
  custom: CustomRemoteAction | null;
  adjustSign: number;
  balanceMinutes: number;
  pending: boolean;
  onChangeCustom: (next: CustomRemoteAction | null) => void;
  onChangeAdjustSign: (sign: number) => void;
  onSubmit: () => void;
};

export default function RemoteActionDialog({
  custom,
  adjustSign,
  balanceMinutes,
  pending,
  onChangeCustom,
  onChangeAdjustSign,
  onSubmit,
}: Props) {
  return (
    <Dialog
      open={!!custom}
      onOpenChange={open => {
        if (!open) onChangeCustom(null);
      }}
    >
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>
            {custom?.mode === 'credit'
              ? '调整剩余时间'
              : custom?.mode === 'lock'
                ? '暂停使用'
                : '临时解锁'}
          </DialogTitle>
          <DialogDescription>
            {custom?.mode === 'credit'
              ? `当前余额 ${balanceMinutes} 分钟；只调整今天，不改每日规则。`
              : custom?.mode === 'lock'
                ? '锁定期间孩子不能答题，到时间自动恢复；家长可随时解除。'
                : custom?.untilShutdown
                  ? '设备真正关机、重启或断电后恢复规则；待机、熄屏和应用重启仍保持解锁，家长可随时取消。'
                  : '可选择解锁时长或到本次关机；期间不需要答题，也不计入使用时间。'}
          </DialogDescription>
        </DialogHeader>
        {custom?.mode === 'credit' && (
          <ToolbarGroup className="grid grid-cols-2" aria-label="调整方向">
            <Button
              type="button"
              variant={adjustSign === 1 ? 'secondary' : 'outline'}
              onClick={() => onChangeAdjustSign(1)}
            >
              加时
            </Button>
            <Button
              type="button"
              variant={adjustSign === -1 ? 'secondary' : 'outline'}
              onClick={() => onChangeAdjustSign(-1)}
            >
              提前收走
            </Button>
          </ToolbarGroup>
        )}
        <ToolbarGroup className="grid grid-cols-2 gap-2" aria-label="常用时长">
          {(custom?.mode === 'credit'
            ? ([
                [5, '5 分钟'],
                [10, '10 分钟'],
                [15, '15 分钟'],
                [30, '30 分钟'],
              ] as const)
            : ([
                [30, '30 分钟'],
                [60, '1 小时'],
                [120, '2 小时'],
                [minutesToMidnight(), '到今晚 24 点'],
              ] as const)
          ).map(([minutes, label]) => (
            <Button
              key={String(label)}
              type="button"
              variant={!custom?.untilShutdown && custom?.minutes === String(minutes) ? 'secondary' : 'outline'}
              onClick={() =>
                onChangeCustom(custom ? { ...custom, minutes: String(minutes), untilShutdown: false } : custom)
              }
            >
              {label}
            </Button>
          ))}
          {custom?.mode === 'unlock' && (
            <Button
              type="button"
              className="col-span-2"
              variant={custom.untilShutdown ? 'secondary' : 'outline'}
              onClick={() => onChangeCustom(custom ? { ...custom, untilShutdown: true } : custom)}
            >
              到本次关机
            </Button>
          )}
        </ToolbarGroup>
        <div className="space-y-2">
          <Label htmlFor="custom-minutes">分钟</Label>
          <Input
            id="custom-minutes"
            type="number"
            inputMode="numeric"
            min={1}
            max={custom?.mode === 'credit' ? 480 : custom?.mode === 'lock' ? 10080 : 1440}
            disabled={custom?.untilShutdown}
            value={custom?.untilShutdown ? '' : custom?.minutes || ''}
            onChange={event =>
              onChangeCustom(custom ? { ...custom, minutes: event.target.value } : custom)
            }
            placeholder={custom?.mode === 'lock' ? '例如 60' : '例如 45'}
          />
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onChangeCustom(null)}>
            取消
          </Button>
          <Button onClick={onSubmit} disabled={pending}>
            确定
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
