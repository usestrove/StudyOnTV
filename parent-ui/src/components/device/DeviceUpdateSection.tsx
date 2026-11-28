import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, CloudDownload, Copy, Download, LoaderCircle, RefreshCw } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/api';
import { copyText } from '@/lib/clipboard';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Switch } from '@/components/ui/switch';
import { SettingItem, SettingsCard } from './DeviceSettingsFrame';

const formatTime = (value: number) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未检查';

type UpdateInfo = {
  hasUpdate?: boolean;
  latest?: string;
  notes?: string;
  url?: string;
  checking?: boolean;
  checkedAt?: number;
  logAt?: number;
  log?: any[];
  installError?: string;
  installState?: 'idle' | 'downloading' | 'installing' | 'error';
  installProgress?: number;
  installMessage?: string;
  autoDownload?: boolean;
  apkReady?: boolean;
  downloadedVersion?: string;
  silentCapability?: 'device_owner' | 'self_update' | 'confirm';
};

export default function DeviceUpdateSection({
  sessionId,
  deviceData,
}: {
  sessionId: number;
  deviceData: any;
}) {
  const client = useQueryClient();
  const [polling, setPolling] = useState(false);
  const pollTimer = useRef<number | null>(null);
  useEffect(
    () => () => {
      if (pollTimer.current !== null) window.clearTimeout(pollTimer.current);
    },
    [],
  );

  const localOnly = !!deviceData?.localOnly;
  const update = useQuery({
    queryKey: ['update', sessionId],
    queryFn: () => api('/api/update'),
    enabled: !!deviceData && !deviceData.localOnly,
    refetchInterval: query => {
      const data = query.state.data as any;
      if (data?.installState === 'downloading' || data?.installState === 'installing') {
        return 1500;
      }
      return polling ? 2000 : false;
    },
  });

  useEffect(() => {
    if (polling && update.data && !update.data.checking) {
      setPolling(false);
      if (update.data.hasUpdate && update.data.latest) {
        toast.message(
          update.data.apkReady
            ? `新版本 v${update.data.latest} 已下载好，可直接安装`
            : update.data.autoDownload && update.data.installState === 'downloading'
              ? `发现新版本 v${update.data.latest}，正在后台自动下载，下好后再提醒`
              : `发现新版本 v${update.data.latest}，请升级`,
        );
      } else if (
        update.data.log &&
        update.data.log.length &&
        update.data.log.every((item: any) => !item.ok)
      ) {
        toast.error(`检查更新失败：${update.data.log[0]?.message || '网络不通，请稍后再试'}`);
      } else {
        toast.success('当前已是最新版本');
      }
    }
  }, [polling, update.data?.checking, update.data?.hasUpdate]);

  const check = useMutation({
    mutationFn: () => api('/api/update/check', 'POST', {}),
    onSuccess: result => {
      client.setQueryData(['update', sessionId], result);
      setPolling(true);
      toast.message('正在检查更新…');
      if (pollTimer.current !== null) window.clearTimeout(pollTimer.current);
      pollTimer.current = window.setTimeout(() => {
        pollTimer.current = null;
        setPolling(false);
      }, 70000);
    },
    onError: (error: any) => toast.error(error.message),
  });

  const installUpdate = useMutation({
    mutationFn: () => api('/api/update/install', 'POST', {}),
    onSuccess: (result: any) => {
      client.setQueryData(['update', sessionId], result);
      client.invalidateQueries({ queryKey: ['update', sessionId] });
      const cap = result?.silentCapability ?? update.data?.silentCapability;
      const silent = cap === 'device_owner' || cap === 'self_update';
      if (result?.installState === 'installing' || update.data?.apkReady) {
        toast.message(
          silent ? '安装包已就绪，正在后台静默安装…' : '安装包已就绪，正在设备上拉起安装…',
        );
      } else {
        toast.message('已开始在设备上下载更新包…');
      }
    },
    onError: (error: any) => toast.error(error?.message || '触发在线更新失败'),
  });

  const updateSettings = useMutation({
    mutationFn: (body: { autoDownload: boolean }) => api('/api/update', 'PUT', body),
    onSuccess: (result: any, variables) => {
      client.setQueryData(['update', sessionId], (old: any) =>
        result && typeof result === 'object'
          ? { ...old, ...result }
          : old
            ? { ...old, ...variables }
            : result,
      );
      client.invalidateQueries({ queryKey: ['update', sessionId] });
      const enabled =
        typeof result?.autoDownload === 'boolean' ? result.autoDownload : variables.autoDownload;
      toast.success(enabled ? '已开启后台自动下载安装包' : '已关闭后台自动下载安装包');
    },
    onError: (error: any) => toast.error(error?.message || '保存更新设置失败'),
  });

  const copy = async (value: string) => {
    const result = await copyText(value);
    if (result === 'ok') toast.success('已复制');
    else toast.error('复制失败，请手动选中文字复制');
  };

  const updateInfo = update.data as UpdateInfo | undefined;
  const startedAt = updateInfo?.logAt || updateInfo?.checkedAt || 0;
  const installState = updateInfo?.installState;
  const installProgress = Math.min(100, Math.max(0, Math.round(Number(updateInfo?.installProgress) || 0)));
  const installMessage = String(updateInfo?.installMessage || '');
  const installingNow = installState === 'downloading' || installState === 'installing';
  const silentCapability = updateInfo?.silentCapability;
  const supportsSilentUpdate = silentCapability === 'device_owner' || silentCapability === 'self_update';
  const apkReady = Boolean(updateInfo?.hasUpdate && updateInfo?.apkReady);
  const readyVersion = String(updateInfo?.downloadedVersion || updateInfo?.latest || '');
  const failedSources = ((updateInfo?.log || []) as any[]).filter(
    item => item && !item.ok && !String(item.message || '').includes('已是最新版本'),
  );
  const latestVer = updateInfo?.latest || '新版';
  const isMobileOrMiui =
    deviceData?.flavor === 'mobile' ||
    /xiaomi|redmi|poco/i.test(`${deviceData?.manufacturer || ''} ${deviceData?.model || ''}`);
  const apkName =
    deviceData?.flavor === 'tv'
      ? `StudyOnTV-Android-TV-v${latestVer}.apk`
      : `StudyOnTV-Mobile-v${latestVer}.apk`;
  const adbScript = `adb connect ${deviceData?.ip || '电视IP'}:5555\nadb install -r ${apkName}`;

  return (
    <SettingsCard title="版本更新" icon={CloudDownload}>
      {localOnly ? (
        <SettingItem
          title="纯本地版本"
          description="当前为纯本地版本，不包含在线检查更新或数据上报功能。"
        >
          <Badge variant="secondary">仅本地运行</Badge>
        </SettingItem>
      ) : (
        <>
          <SettingItem
            title={updateInfo?.hasUpdate ? `有新版本 v${updateInfo.latest}` : '当前已是最新版本'}
            description={
              <span className="inline-flex flex-wrap items-center gap-2">
                <span>上次检查：{formatTime(startedAt)}</span>
                {apkReady && readyVersion && (
                  <Badge
                    variant="outline"
                    className="bg-emerald-50 text-emerald-700 dark:bg-emerald-400/10 dark:text-emerald-300"
                  >
                    <Check />
                    已预下载 v{readyVersion}
                  </Badge>
                )}
              </span>
            }
            wideControl
          >
            <div className="flex flex-wrap items-center justify-end gap-2">
              {updateInfo?.hasUpdate && (
                <Button
                  disabled={installUpdate.isPending || installingNow}
                  onClick={() => installUpdate.mutate()}
                >
                  {installUpdate.isPending || installingNow ? (
                    <LoaderCircle className="animate-spin" />
                  ) : (
                    <Download />
                  )}
                  {installState === 'downloading'
                    ? `下载中 ${installProgress}%`
                    : installState === 'installing'
                      ? supportsSilentUpdate
                        ? '正在静默安装…'
                        : '正在拉起安装…'
                      : installState === 'error'
                        ? '重试在线更新'
                        : apkReady
                          ? '立即安装（安装包已就绪）'
                          : '立即在线更新'}
                </Button>
              )}
              <Button
                variant="outline"
                disabled={check.isPending || polling || installingNow}
                onClick={() => check.mutate()}
              >
                <RefreshCw className={polling ? 'animate-spin' : ''} />
                {polling ? '检查中…' : '检查更新'}
              </Button>
            </div>
          </SettingItem>
          <SettingItem
            title="后台自动下载安装包"
            description="检测到新版本时先在后台静默下载并校验，下载完成后再弹更新提示，点安装无需等待。"
          >
            <Switch
              aria-label="后台自动下载安装包"
              checked={!!updateInfo?.autoDownload}
              disabled={updateSettings.isPending || !updateInfo}
              onCheckedChange={autoDownload => updateSettings.mutate({ autoDownload })}
            />
          </SettingItem>
          {silentCapability && (
            <SettingItem
              title="后台升级能力"
              description={
                supportsSilentUpdate
                  ? '后台自动完成安装，装完自动恢复服务。'
                  : isMobileOrMiui
                    ? '支持 Web 端一键下载，下载完成后请在设备确认安装；小米/澎湃平板若未弹出，请下拉通知栏点击更新提示或开启「后台弹出界面」。覆盖升级后若悬浮窗被系统重置，请重新授予悬浮窗权限。'
                    : '支持 Web 端一键下载，下载完成后请在设备屏幕上确认安装。'
              }
            >
              {supportsSilentUpdate ? (
                <Badge
                  variant="outline"
                  className="bg-emerald-50 text-emerald-700 dark:bg-emerald-400/10 dark:text-emerald-300"
                >
                  <Check />
                  支持官方静默更新
                </Badge>
              ) : (
                <Badge variant="secondary" className="text-muted-foreground">
                  需屏幕确认安装
                </Badge>
              )}
            </SettingItem>
          )}
          {installState && installState !== 'idle' && (
            <div className="space-y-2 py-4">
              <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                <span
                  className={
                    installState === 'error'
                      ? 'font-medium text-destructive'
                      : installState === 'installing'
                        ? 'font-medium text-emerald-600 dark:text-emerald-400'
                        : 'font-medium text-foreground'
                  }
                >
                  {installMessage ||
                    (installState === 'downloading'
                      ? '正在设备上下载新版本安装包…'
                      : installState === 'installing'
                        ? supportsSilentUpdate
                          ? '安装包已就绪，正在后台静默安装，完成后将自动恢复服务'
                          : '安装包已下载完成，请在设备屏幕上确认安装'
                        : '在线更新失败，请检查设备网络后重试')}
                </span>
                {installState === 'downloading' && (
                  <span className="tabular-nums text-muted-foreground">{installProgress}%</span>
                )}
                {installState === 'error' && updateInfo?.hasUpdate && (
                  <Button
                    size="sm"
                    variant="outline"
                    className="h-7 px-2 text-xs"
                    disabled={installUpdate.isPending}
                    onClick={() => installUpdate.mutate()}
                  >
                    重试
                  </Button>
                )}
              </div>
              {(installState === 'downloading' || installState === 'installing') && (
                <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                  <div
                    className="h-full rounded-full bg-primary transition-all duration-300"
                    style={{ width: `${installState === 'installing' ? 100 : installProgress}%` }}
                  />
                </div>
              )}
            </div>
          )}
          {updateInfo?.hasUpdate && (
            <SettingItem
              title="更新说明"
              description={updateInfo.notes?.replace(/\[force\]/gi, '').trim()}
            >
              <span className="text-xs text-muted-foreground">
                可点击上方「{apkReady ? '立即安装（安装包已就绪）' : '立即在线更新'}」推送到设备，或在设备学习页右上角选择「更新」安装。
              </span>
            </SettingItem>
          )}
          {updateInfo?.installError && (
            <SettingItem
              title="上次安装失败"
              description={`${updateInfo.installError}。电视上点「立即更新」会再试一次；系统不给装的话，需要到 设置 → 应用 → 安装未知应用 里允许「题了个题」。`}
            >
              {null}
            </SettingItem>
          )}
          {failedSources.length > 0 && (
            <SettingItem
              title="上次检查没成功"
              description={
                <>
                  {failedSources.map(item => `${item.source}：${item.message}`).join('；')}。
                  常见原因是电视没联网、路由器拦了 DNS，或者发布服务器暂时不可用，过一会儿再点「检查更新」即可。
                </>
              }
            >
              {null}
            </SettingItem>
          )}
          {(updateInfo?.hasUpdate || updateInfo?.installError) && (
            <>
              <SettingItem
                wideControl
                title="电视上装不上时，用电脑安装"
                description="在电视上打开 设置 → 系统 → 开发者选项，开启「USB 调试」和「网络调试」，然后在电脑终端里依次执行："
              >
                <div className="flex w-full flex-wrap justify-end gap-2 sm:w-auto">
                  {updateInfo?.url && (
                    <Button variant="outline" asChild>
                      <a href={updateInfo.url} target="_blank" rel="noreferrer">
                        下载安装包
                      </a>
                    </Button>
                  )}
                  <Button variant="outline" onClick={() => copy(adbScript)}>
                    <Copy />
                    复制命令
                  </Button>
                </div>
              </SettingItem>
              <div className="pb-4">
                <pre className="overflow-x-auto rounded-lg border border-border/70 bg-muted/40 p-3 text-xs leading-5">
                  {adbScript}
                </pre>
              </div>
            </>
          )}
        </>
      )}
    </SettingsCard>
  );
}
