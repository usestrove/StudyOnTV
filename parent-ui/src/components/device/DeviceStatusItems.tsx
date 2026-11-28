import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Check } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/api';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Switch } from '@/components/ui/switch';
import { keepAliveSummary } from '@/keepalive';
import { SettingItem } from './DeviceSettingsFrame';

const VIA: Record<string, string> = {
  boot: '开机广播',
  accessibility: '无障碍服务',
  job: '系统定时任务',
  update: '应用更新',
  app: '打开应用',
};

function autostartNote(status: any) {
  if (status.pending) return '刚安装或更新，重启一次设备后可以判断是否生效。';
  if (!status.known) return '这台设备不提供开机次数，建议开启无障碍服务。';
  const via = status.via
    ? `本次开机后${status.startedAfterSeconds >= 0 ? ` ${Math.round(status.startedAfterSeconds / 60)} 分钟` : ''}由${VIA[status.via] || status.via}启动`
    : '本次开机后尚未记录';
  return status.ok
    ? `${via}${status.accessibility ? ' · 无障碍服务已开启' : ''}`
    : `${via}。请在系统自启动管理里允许本应用，并开启无障碍服务。`;
}

function monitorNote(monitor: any) {
  if (!monitor?.service) return '管控服务未运行，请检查自启动和后台运行权限。';
  if (monitor.accessibilityLost) return '无障碍服务已关闭，计时或拦截可能受影响。';
  if (monitor.accessibilityEnabled && !monitor.accessibility) {
    return '无障碍已授权但尚未连接，请重新开启服务。';
  }
  const missing: string[] = [];
  if (!monitor.overlay) missing.push('悬浮窗');
  if (!monitor.usage && !monitor.accessibility) missing.push('使用情况访问或无障碍');
  return missing.length ? `缺少${missing.join('、')}。` : '计时和拦截工作正常。';
}

type Props = {
  sessionId: number;
  deviceData: any;
  onOpenKeepAlive?: () => void;
};

export default function DeviceStatusItems({ sessionId, deviceData, onOpenKeepAlive }: Props) {
  const client = useQueryClient();
  const keepalive = deviceData?.keepalive ? keepAliveSummary(deviceData) : null;

  const enableGuard = useMutation({
    mutationFn: () => api('/api/device/uninstall-guard', 'PUT', {}),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['device', sessionId] });
      toast.success('防卸载保护已恢复');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const disableGuard = useMutation({
    mutationFn: () => api('/api/device/uninstall-guard', 'DELETE'),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['device', sessionId] });
      toast.success('防卸载已关闭');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const launcher = useMutation({
    mutationFn: (hidden: boolean) => api('/api/device/launcher', 'PUT', { hidden }),
    onSuccess: (result: any) => {
      client.invalidateQueries({ queryKey: ['device', sessionId] });
      toast.success(result.launcherHidden ? '桌面图标已隐藏，应用仍在后台运行' : '桌面图标已恢复');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const desktop = useMutation({
    mutationFn: (enabled: boolean) => api('/api/device/desktop', 'PUT', { enabled }),
    onSuccess: (result: any) => {
      client.invalidateQueries({ queryKey: ['device', sessionId] });
      toast.success(
        result.enabled
          ? result.isDefault
            ? '已设为电视桌面'
            : '已启用，请在电视上选择「题了个题」为桌面'
          : '已关闭，电视回到原来的桌面',
      );
    },
    onError: (error: any) => toast.error(error.message),
  });
  const chooseDesktop = useMutation({
    mutationFn: () => api('/api/device/desktop/choose', 'POST', {}),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['device', sessionId] });
      toast.success('电视上已弹出桌面选择，用遥控器选「题了个题」');
    },
    onError: (error: any) => toast.error(error.message),
  });

  return (
    <>
      {deviceData?.autostart && (
        <SettingItem title="开机自启" description={autostartNote(deviceData.autostart)}>
          <span
            className={
              deviceData.autostart.ok
                ? 'text-sm text-emerald-600 dark:text-emerald-400'
                : deviceData.autostart.pending
                  ? 'text-sm text-muted-foreground'
                  : 'text-sm text-amber-600 dark:text-amber-400'
            }
          >
            {!deviceData.autostart.known
              ? '无法判断'
              : deviceData.autostart.ok
                ? '正常'
                : deviceData.autostart.pending
                  ? '待重启确认'
                  : '可能被拦截'}
          </span>
        </SettingItem>
      )}
      <SettingItem title="后台保活" description="逐项检查服务和权限" wideControl>
        <div className="flex items-center gap-2">
          {keepalive && (
            <span
              className={
                keepalive.level === 'ok'
                  ? 'text-sm text-emerald-600 dark:text-emerald-400'
                  : keepalive.level === 'warn'
                    ? 'text-sm text-amber-600 dark:text-amber-400'
                    : 'text-sm text-muted-foreground'
              }
            >
              {keepalive.text}
            </span>
          )}
          {onOpenKeepAlive && (
            <Button size="sm" variant="outline" onClick={onOpenKeepAlive}>
              查看自检
            </Button>
          )}
        </div>
      </SettingItem>
      {deviceData?.monitor && (
        <SettingItem title="权限状态" description={monitorNote(deviceData.monitor)} wideControl>
          <div className="flex flex-wrap gap-1.5 sm:justify-end">
            {[
              ['服务', deviceData.monitor.service],
              ['悬浮窗', deviceData.monitor.overlay],
              ['使用情况', deviceData.monitor.usage],
              ['无障碍', deviceData.monitor.accessibility],
            ].map(([label, available]: any) => (
              <Badge
                key={label}
                variant="outline"
                className={
                  available
                    ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-400/10 dark:text-emerald-300'
                    : 'bg-amber-50 text-amber-700 dark:bg-amber-400/10 dark:text-amber-300'
                }
              >
                {available ? <Check /> : null}
                {label}
              </Badge>
            ))}
          </div>
        </SettingItem>
      )}
      {!!deviceData?.monitor?.lastError && (
        <SettingItem title="最近一次弹窗失败" description={deviceData.monitor.lastError}>
          <span className="text-xs text-muted-foreground">可反馈给开发者</span>
        </SettingItem>
      )}
      <SettingItem
        title="防卸载"
        description={
          deviceData?.deviceOwner
            ? '已开启系统级防卸载保护。'
            : '防从桌面直接卸载；配合无障碍和「防孩子进系统设置」可同时防止从设置卸载。'
        }
      >
        {deviceData?.uninstallProtected ? (
          <Button
            size="sm"
            variant="outline"
            className="border-destructive/40 text-destructive hover:bg-destructive/10"
            onClick={() => {
              if (confirm('关闭后孩子可以直接卸载应用，确认吗？')) disableGuard.mutate();
            }}
          >
            关闭防卸载
          </Button>
        ) : deviceData?.deviceOwner ? (
          <Button size="sm" variant="outline" disabled={enableGuard.isPending} onClick={() => enableGuard.mutate()}>
            恢复防卸载
          </Button>
        ) : (
          <span className="text-sm text-muted-foreground">未开启</span>
        )}
      </SettingItem>
      <SettingItem
        title="隐藏桌面图标"
        description="桌面和电视应用列表不再显示图标，孩子看不到也点不到；管控、后台服务照常运行。要恢复：点通知栏的「使用时间管理」通知打开应用，或在这里关闭。"
        wideControl
      >
        <Switch
          checked={!!deviceData?.launcherHidden}
          disabled={launcher.isPending || !deviceData}
          onCheckedChange={hidden => {
            if (
              hidden &&
              !confirm(
                '隐藏后桌面不再显示图标。恢复方法：点通知栏的「使用时间管理」通知，或在家长面板这里关闭。确认隐藏吗？',
              )
            )
              return;
            launcher.mutate(hidden);
          }}
          aria-label="隐藏桌面图标"
        />
      </SettingItem>
      {/* 默认发的包不带电视桌面，设备会报 supported: false，这时整项隐藏 */}
      {deviceData?.desktop?.supported !== false && (
        <SettingItem
          title="把题了个题设为电视桌面"
          description={
            deviceData?.desktop?.isDefault
              ? '当前就是电视桌面：开机和按 Home 都回到「题了个题」。想换回去，把开关关掉即可。'
              : deviceData?.desktop?.enabled
                ? `已启用，但系统默认桌面还是「${deviceData.desktop.homeLabel || '原桌面'}」。电视上没有弹出选择框的话，请到系统设置 → 应用 → 默认应用里把桌面改成「题了个题」。`
                : '开启后，电视开机和按 Home 都回到「题了个题」，孩子不能再从系统桌面点开任意应用；时间用完时桌面直接显示锁屏和答题。部分电视需要手动改默认桌面，随时可以关掉。'
          }
          wideControl
        >
          <div className="flex flex-wrap items-center justify-end gap-2">
            {deviceData?.desktop?.enabled && !deviceData?.desktop?.isDefault && (
              <Button size="sm" variant="outline" disabled={chooseDesktop.isPending} onClick={() => chooseDesktop.mutate()}>
                去选择桌面
              </Button>
            )}
            <Switch
              checked={!!deviceData?.desktop?.enabled}
              disabled={desktop.isPending || !deviceData}
              onCheckedChange={enabled => {
                if (
                  enabled &&
                  !confirm('开启后，孩子按 Home 也回不到系统桌面，只能从「题了个题」的应用列表进入。确认开启吗？')
                )
                  return;
                if (!enabled && !confirm('关闭后电视会回到原来的桌面，孩子可以按 Home 回到系统桌面。确认关闭吗？'))
                  return;
                desktop.mutate(enabled);
              }}
              aria-label="设为电视桌面"
            />
          </div>
        </SettingItem>
      )}
      <SettingItem title="浏览器练习" description="供 iPad 或旧设备使用，题库独立。">
        <Button variant="link" className="h-auto p-0" asChild>
          <a
            target="_blank"
            rel="noreferrer"
            href={`http://${deviceData?.ip || location.hostname}:${deviceData?.port || 8099}/practice`}
          >
            打开练习页
          </a>
        </Button>
      </SettingItem>
    </>
  );
}
