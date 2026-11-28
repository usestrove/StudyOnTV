import {
  ArrowLeftRight,
  Bookmark,
  BookmarkCheck,
  ExternalLink,
  Eye,
  EyeOff,
  KeyRound,
  LoaderCircle,
  Radar,
  Smartphone,
  Trash2,
  Tv,
  Wifi,
  WifiOff,
} from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Field, FieldLabel } from '@/components/ui/field';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';
import type { MergedPeer } from '@/peers';

type Props = {
  peer: MergedPeer;
  checked: boolean;
  customPwdExpanded: boolean;
  password: string;
  pwdVisible: boolean;
  probing: boolean;
  onToggleSelect: (host: string) => void;
  onPasswordChange: (host: string, value: string) => void;
  onTogglePwdVisible: (host: string) => void;
  onExpandCustomPwd: (host: string) => void;
  onSwitchHost: (host: string, name?: string) => void;
  onOpenInNewTab: (peer: MergedPeer) => void;
  onReprobe: (peer: MergedPeer) => void;
  onToggleSave: (peer: MergedPeer) => void;
  onRemoveSaved: (peer: MergedPeer) => void;
};

export default function PeerDeviceCard({
  peer,
  checked,
  customPwdExpanded,
  password,
  pwdVisible,
  probing,
  onToggleSelect,
  onPasswordChange,
  onTogglePwdVisible,
  onExpandCustomPwd,
  onSwitchHost,
  onOpenInNewTab,
  onReprobe,
  onToggleSave,
  onRemoveSaved,
}: Props) {
  return (
    <Card className={`transition-colors shadow-xs ${checked ? 'border-primary/50 bg-primary/[0.02]' : ''}`}>
      <CardContent className="space-y-3 p-4">
        <div className="flex items-start gap-3">
          <Checkbox
            id={`peer-select-${peer.host}`}
            checked={checked}
            onCheckedChange={() => onToggleSelect(peer.host)}
            className="mt-1"
            aria-label={`勾选同步目标 ${peer.name}`}
          />
          <div className="min-w-0 flex-1 space-y-1.5">
            <div className="flex flex-wrap items-center gap-1.5">
              <label htmlFor={`peer-select-${peer.host}`} className="cursor-pointer truncate text-sm font-semibold">
                {peer.name}
              </label>
              {typeof peer.isTv === 'boolean' && (
                <Badge variant="outline" className="text-[11px]">
                  {peer.isTv ? (
                    <>
                      <Tv className="mr-1 size-3" />
                      电视
                    </>
                  ) : (
                    <>
                      <Smartphone className="mr-1 size-3" />
                      手机/平板
                    </>
                  )}
                </Badge>
              )}
              {peer.online ? (
                <Badge
                  variant="secondary"
                  className="bg-emerald-50 text-[11px] text-emerald-700 dark:bg-emerald-400/10 dark:text-emerald-300"
                >
                  <Wifi className="mr-1 size-3" />
                  在线
                </Badge>
              ) : (
                <Badge variant="secondary" className="text-[11px] text-muted-foreground">
                  <WifiOff className="mr-1 size-3" />
                  未探测到
                </Badge>
              )}
              {peer.saved && (
                <Badge variant="outline" className="text-[11px] text-muted-foreground">
                  已收藏
                </Badge>
              )}
            </div>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
              <span className="font-mono">{peer.host}</span>
              {peer.version && <span>v{peer.version}</span>}
              {typeof peer.questions === 'number' && <span>{peer.questions} 道题</span>}
            </div>
          </div>
        </div>

        {checked && customPwdExpanded && (
          <div className="rounded-md border bg-muted/30 p-2.5">
            <Field>
              <FieldLabel htmlFor={`pwd-${peer.host}`} className="text-xs">
                该台设备单独管理密码（可选，优先于统一密码）
              </FieldLabel>
              <InputGroup className="h-8 bg-background">
                <InputGroupAddon>
                  <KeyRound className="size-3.5" />
                </InputGroupAddon>
                <InputGroupInput
                  id={`pwd-${peer.host}`}
                  type={pwdVisible ? 'text' : 'password'}
                  autoComplete="off"
                  placeholder="留空则使用下方统一密码"
                  value={password}
                  onChange={e => onPasswordChange(peer.host, e.target.value)}
                />
                <InputGroupAddon align="inline-end">
                  <InputGroupButton
                    size="icon-xs"
                    aria-label={pwdVisible ? '隐藏密码' : '显示密码'}
                    onClick={() => onTogglePwdVisible(peer.host)}
                  >
                    {pwdVisible ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
                  </InputGroupButton>
                </InputGroupAddon>
              </InputGroup>
            </Field>
          </div>
        )}

        <div className="flex flex-wrap items-center gap-1.5 pt-0.5">
          <Button size="sm" variant="default" className="h-8" onClick={() => onSwitchHost(peer.host, peer.name)}>
            <ArrowLeftRight className="size-3.5" />
            切换管理
          </Button>
          <Button
            size="sm"
            variant="outline"
            className="h-8"
            title="在新标签页打开该设备后台"
            onClick={() => onOpenInNewTab(peer)}
          >
            <ExternalLink className="size-3.5" />
            新窗口
          </Button>
          {checked && !customPwdExpanded && (
            <Button
              size="sm"
              variant="ghost"
              className="h-8 text-xs text-muted-foreground"
              onClick={() => onExpandCustomPwd(peer.host)}
            >
              <KeyRound className="size-3.5" />
              单独密码
            </Button>
          )}
          {!peer.online && (
            <Button
              size="sm"
              variant="ghost"
              className="h-8 text-xs"
              disabled={probing}
              onClick={() => onReprobe(peer)}
            >
              {probing ? <LoaderCircle className="size-3.5 animate-spin" /> : <Radar className="size-3.5" />}
              探测
            </Button>
          )}
          <div className="ms-auto flex items-center gap-1">
            {peer.discovered ? (
              <Button
                size="icon"
                variant="ghost"
                className="size-8 text-muted-foreground"
                title={peer.saved ? '取消收藏' : '收藏此设备'}
                aria-label={peer.saved ? '取消收藏' : '收藏此设备'}
                onClick={() => onToggleSave(peer)}
              >
                {peer.saved ? <BookmarkCheck className="size-4 text-primary" /> : <Bookmark className="size-4" />}
              </Button>
            ) : (
              <Button
                size="icon"
                variant="ghost"
                className="size-8 text-muted-foreground hover:text-destructive"
                title="移除收藏"
                aria-label="移除收藏"
                onClick={() => onRemoveSaved(peer)}
              >
                <Trash2 className="size-4" />
              </Button>
            )}
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
