import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Check, ChevronDown, LoaderCircle, MonitorSmartphone } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/api';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { SettingItem, SettingsCard } from './DeviceSettingsFrame';
import DeviceStatusItems from './DeviceStatusItems';

type Props = {
  sessionId: number;
  deviceData: any;
  onOpenKeepAlive?: () => void;
};

export default function DeviceOverviewSection({ sessionId, deviceData, onOpenKeepAlive }: Props) {
  const client = useQueryClient();
  const localOnly = !!deviceData?.localOnly;
  const [name, setName] = useState('');
  const nameSession = useRef<number | null>(null);
  const submittedName = useRef('');
  const [endpoint, setEndpoint] = useState('');
  const [token, setToken] = useState('');
  const crashSession = useRef<number | null>(null);

  useEffect(() => {
    if (deviceData && nameSession.current !== sessionId) {
      setName(deviceData.name || '');
      nameSession.current = sessionId;
      submittedName.current = '';
    }
  }, [deviceData?.name, sessionId]);

  useEffect(() => {
    if (deviceData?.crashUpload && crashSession.current !== sessionId) {
      setEndpoint(deviceData.crashUpload.endpoint || '');
      crashSession.current = sessionId;
    }
  }, [deviceData?.crashUpload?.endpoint, sessionId]);

  const crash = useMutation({
    mutationFn: (patch: { consent?: boolean; endpoint?: string; token?: string }) =>
      api('/api/device/crash', 'PUT', patch),
    onSuccess: (result: any) => {
      client.setQueryData(['device', sessionId], (old: any) =>
        old ? { ...old, crashUpload: result.crashUpload } : old,
      );
      client.invalidateQueries({ queryKey: ['device', sessionId] });
    },
    onError: (error: any) => toast.error(error.message),
  });

  const rename = useMutation({
    mutationFn: (nextName: string) => api('/api/device', 'PUT', { name: nextName.trim() }),
    onSuccess: (_result, nextName) => {
      const savedName = nextName.trim();
      setName(current => (current === nextName ? savedName : current));
      client.setQueryData(['device', sessionId], (old: any) =>
        old ? { ...old, name: savedName } : old,
      );
      client.invalidateQueries({ queryKey: ['device', sessionId] });
    },
    onError: (error: any) => {
      submittedName.current = '';
      toast.error(error.message);
    },
  });

  const saveName = () => {
    const nextName = name.trim();
    if (!nextName || !deviceData || nextName === (deviceData.name || '').trim() || rename.isPending) {
      return;
    }
    submittedName.current = name;
    rename.mutate(nextName);
  };
  const saveEndpoint = () => {
    const next = endpoint.trim();
    if (!deviceData || crash.isPending) return;
    if (next === (deviceData.crashUpload?.endpoint || '').trim()) return;
    crash.mutate({ endpoint: next });
  };
  const saveToken = () => {
    const next = token.trim();
    // 令牌只写不读：留空表示不改动已保存的值
    if (!next || crash.isPending) return;
    crash.mutate({ token: next }, { onSuccess: () => setToken('') });
  };

  useEffect(() => {
    const nextName = name.trim();
    if (
      !nextName ||
      !deviceData ||
      nextName === (deviceData.name || '').trim() ||
      rename.isPending ||
      submittedName.current === name
    ) {
      return;
    }
    const timer = window.setTimeout(() => {
      if (submittedName.current === name) return;
      submittedName.current = name;
      rename.mutate(nextName);
    }, 700);
    return () => window.clearTimeout(timer);
  }, [name, deviceData?.name, rename.isPending, sessionId]);

  return (
    <SettingsCard
      title="设备"
      icon={MonitorSmartphone}
      description={
        deviceData
          ? `${deviceData.flavor === 'tv' ? '电视版' : '手机 / 平板版'}${deviceData.desktop?.supported ? ' · 内测版' : ''} v${deviceData.version} · ${deviceData.ip || '未连接 WiFi'}:${deviceData.port}`
          : undefined
      }
    >
      <DeviceStatusItems sessionId={sessionId} deviceData={deviceData} onOpenKeepAlive={onOpenKeepAlive} />

      {!localOnly && (
        <>
          <SettingItem
            title="参与软件体验提升计划"
            description="帮助我们持续优化设备兼容性与使用体验，可随时关闭。"
          >
            <Switch
              aria-label="参与软件体验提升计划"
              checked={!!deviceData?.crashUpload?.consent}
              disabled={crash.isPending || !deviceData}
              onCheckedChange={value => crash.mutate({ consent: value })}
            />
          </SettingItem>
          <details className="group py-2">
            <summary className="flex cursor-pointer list-none items-center gap-1.5 py-2 text-xs text-muted-foreground transition-colors hover:text-foreground [&::-webkit-details-marker]:hidden">
              <ChevronDown className="size-3 transition-transform group-open:rotate-180" />
              自定义日志服务器（开发者用）
            </summary>
            <div className="divide-y divide-border/60">
              <SettingItem
                title="上报地址"
                description={
                  deviceData?.crashUpload?.pending
                    ? '有待上传日志，下次启动会自动补传。'
                    : deviceData?.crashUpload?.builtInEndpoint
                      ? '不填就用内置地址；填了会覆盖内置值。支持自定义接收端。'
                      : '自定义接收端地址，留空表示仅本地保留。'
                }
                wideControl
              >
                <Input
                  aria-label="日志上报地址"
                  className="h-9 w-full sm:w-80"
                  placeholder={deviceData?.crashUpload?.builtInEndpoint || 'https://example.com/crash'}
                  autoComplete="off"
                  spellCheck={false}
                  value={endpoint}
                  onChange={event => setEndpoint(event.target.value)}
                  onBlur={saveEndpoint}
                  onKeyDown={event => {
                    if (event.key === 'Enter') event.currentTarget.blur();
                  }}
                />
              </SettingItem>
              <SettingItem
                title="访问令牌"
                description={
                  deviceData?.crashUpload?.builtInToken
                    ? '已内置令牌，留空不改；填了会覆盖它。'
                    : deviceData?.crashUpload?.hasToken
                      ? '已保存令牌。留空表示不修改。'
                      : '用于校验自定义上报接口权限，可留空。'
                }
                wideControl
              >
                <Input
                  aria-label="崩溃上报令牌"
                  type="password"
                  className="h-9 w-full sm:w-80"
                  placeholder={deviceData?.crashUpload?.hasToken ? '已保存，留空不修改' : '输入访问令牌（可选）'}
                  autoComplete="off"
                  spellCheck={false}
                  value={token}
                  onChange={event => setToken(event.target.value)}
                  onBlur={saveToken}
                  onKeyDown={event => {
                    if (event.key === 'Enter') event.currentTarget.blur();
                  }}
                />
              </SettingItem>
            </div>
          </details>
        </>
      )}

      <SettingItem title="设备名称" wideControl>
        <div className="flex w-full items-center gap-2 sm:w-auto">
          <Input
            aria-label="设备名称"
            aria-invalid={!name.trim()}
            className="h-9 w-full sm:w-56"
            maxLength={32}
            autoComplete="off"
            value={name}
            onChange={event => setName(event.target.value)}
            onBlur={saveName}
            onKeyDown={event => {
              if (event.key === 'Enter') event.currentTarget.blur();
            }}
          />
          <span
            className="flex min-w-16 items-center justify-end gap-1 text-xs text-muted-foreground"
            aria-live="polite"
          >
            {rename.isPending ? (
              <>
                <LoaderCircle className="animate-spin" />
                保存中
              </>
            ) : !name.trim() ? (
              '不能为空'
            ) : name.trim() === (deviceData?.name || '').trim() ? (
              <>
                <Check />
                已保存
              </>
            ) : (
              '自动保存'
            )}
          </span>
        </div>
      </SettingItem>
    </SettingsCard>
  );
}
