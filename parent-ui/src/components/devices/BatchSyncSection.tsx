import { useState } from 'react';
import { CheckCircle2, Eye, EyeOff, KeyRound, LoaderCircle, Send, XCircle } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Field, FieldDescription, FieldLabel } from '@/components/ui/field';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';
import { SectionHeading } from '@/components/ui/section-heading';
import { Separator } from '@/components/ui/separator';
import type { SyncMode, SyncScope, SyncTargetResult } from '@/peers';

const SYNC_SCOPES: { id: SyncScope; label: string; description: string }[] = [
  { id: 'config', label: '学习计划规则', description: '每组题量、先背再测、可用时段、年级科目与白名单' },
  { id: 'questions', label: '题库题目', description: '同步本机题库题目到目标设备（支持合并或覆盖）' },
  { id: 'speech', label: '朗读与语音识别', description: '自动朗读开关、朗读范围、音色引擎与语速设置' },
  { id: 'grading', label: 'AI 批改与出题配置', description: '模型服务商、接口地址、模型名称与批改参数' },
];

type Props = {
  scopes: SyncScope[];
  mode: SyncMode;
  defaultPassword: string;
  selectedCount: number;
  pending: boolean;
  results: SyncTargetResult[] | null;
  onToggleScope: (scope: SyncScope) => void;
  onModeChange: (mode: SyncMode) => void;
  onDefaultPasswordChange: (value: string) => void;
  onSync: () => void;
  onFillHostPassword: (host: string) => void;
};

export default function BatchSyncSection({
  scopes,
  mode,
  defaultPassword,
  selectedCount,
  pending,
  results,
  onToggleScope,
  onModeChange,
  onDefaultPasswordChange,
  onSync,
  onFillHostPassword,
}: Props) {
  const [showDefaultPassword, setShowDefaultPassword] = useState(false);

  return (
    <section className="space-y-2.5">
      <SectionHeading
        icon={Send}
        title="一键批量同步"
        description="将当前主控设备的学习计划、题库、朗读或 AI 配置同步到勾选的其他设备。"
      />
      <Card className="shadow-xs">
        <CardHeader className="pb-3">
          <CardTitle className="text-base">同步配置</CardTitle>
          <CardDescription>
            仅同步下方勾选的内容，不会覆盖目标设备的观看倒计时、今日学习记录、设备名称或管理密码。
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="space-y-2.5">
            <div className="text-xs font-medium text-muted-foreground">1. 选择要同步的内容</div>
            <div className="grid gap-2.5 sm:grid-cols-2">
              {SYNC_SCOPES.map(item => {
                const checked = scopes.includes(item.id);
                return (
                  <label
                    key={item.id}
                    htmlFor={`scope-${item.id}`}
                    className={`flex cursor-pointer items-start gap-2.5 rounded-lg border p-3 transition-colors ${
                      checked ? 'border-primary/45 bg-primary/[0.03]' : 'hover:bg-accent/50'
                    }`}
                  >
                    <Checkbox
                      id={`scope-${item.id}`}
                      checked={checked}
                      onCheckedChange={() => onToggleScope(item.id)}
                      className="mt-0.5"
                    />
                    <div className="space-y-0.5">
                      <div className="text-sm font-medium leading-none">{item.label}</div>
                      <p className="text-xs leading-5 text-muted-foreground">{item.description}</p>
                    </div>
                  </label>
                );
              })}
            </div>
          </div>

          {scopes.includes('questions') && (
            <div className="space-y-2">
              <div className="text-xs font-medium text-muted-foreground">2. 题库同步方式</div>
              <div className="grid gap-2.5 sm:grid-cols-2">
                <button
                  type="button"
                  onClick={() => onModeChange('merge')}
                  className={`rounded-lg border p-3 text-left transition-colors ${
                    mode === 'merge'
                      ? 'border-primary bg-primary/[0.04] ring-1 ring-primary/30'
                      : 'hover:bg-accent/50'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium">合并（推荐）</span>
                    {mode === 'merge' && <Badge variant="secondary">已选</Badge>}
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">
                    保留目标设备已有题目，只补充新增或更新的题目。
                  </p>
                </button>
                <button
                  type="button"
                  onClick={() => onModeChange('replace')}
                  className={`rounded-lg border p-3 text-left transition-colors ${
                    mode === 'replace'
                      ? 'border-primary bg-primary/[0.04] ring-1 ring-primary/30'
                      : 'hover:bg-accent/50'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium">覆盖</span>
                    {mode === 'replace' && <Badge variant="secondary">已选</Badge>}
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">
                    将目标设备题库完全替换为当前设备的题库内容。
                  </p>
                </button>
              </div>
            </div>
          )}

          <div className="space-y-2">
            <Field>
              <FieldLabel htmlFor="sync-default-password" className="text-xs font-medium text-muted-foreground">
                {scopes.includes('questions') ? '3.' : '2.'} 目标设备统一管理密码
              </FieldLabel>
              <InputGroup className="max-w-md">
                <InputGroupAddon>
                  <KeyRound className="size-4" />
                </InputGroupAddon>
                <InputGroupInput
                  id="sync-default-password"
                  type={showDefaultPassword ? 'text' : 'password'}
                  autoComplete="off"
                  placeholder="请输入目标设备的管理密码，多台相同时填此处即可"
                  value={defaultPassword}
                  onChange={e => onDefaultPasswordChange(e.target.value)}
                />
                <InputGroupAddon align="inline-end">
                  <InputGroupButton
                    size="icon-sm"
                    aria-label={showDefaultPassword ? '隐藏密码' : '显示密码'}
                    onClick={() => setShowDefaultPassword(v => !v)}
                  >
                    {showDefaultPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                  </InputGroupButton>
                </InputGroupAddon>
              </InputGroup>
              <FieldDescription>
                出于安全考虑，密码仅用于本次同步认证，不会保存到本地存储。如某台设备密码不同，可在上方该设备卡片点「单独密码」覆盖。
              </FieldDescription>
            </Field>
          </div>

          <Separator />

          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="text-xs text-muted-foreground">
              {selectedCount > 0 ? (
                <>
                  已选 <span className="font-semibold text-foreground">{selectedCount}</span> 台设备 ·{' '}
                  <span className="font-semibold text-foreground">{scopes.length}</span> 项同步内容
                </>
              ) : (
                '请在上方设备列表中勾选至少 1 台目标设备'
              )}
            </div>
            <Button onClick={onSync} disabled={pending || selectedCount === 0 || scopes.length === 0}>
              {pending ? (
                <>
                  <LoaderCircle className="size-4 animate-spin" />
                  正在同步…
                </>
              ) : (
                <>
                  <Send className="size-4" />
                  同步到所选设备（{selectedCount}）
                </>
              )}
            </Button>
          </div>

          {results && results.length > 0 && (
            <div className="space-y-2 rounded-lg border bg-muted/20 p-3">
              <div className="text-xs font-semibold">最近一次同步结果</div>
              <div className="space-y-2">
                {results.map(res => (
                  <div
                    key={res.host}
                    className="flex flex-wrap items-start justify-between gap-2 rounded-md border bg-background px-3 py-2 text-xs"
                  >
                    <div className="flex min-w-0 items-start gap-2">
                      {res.ok ? (
                        <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-emerald-600" />
                      ) : (
                        <XCircle className="mt-0.5 size-4 shrink-0 text-destructive" />
                      )}
                      <div className="min-w-0 space-y-0.5">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">{res.name || res.host}</span>
                          <span className="font-mono text-muted-foreground">{res.host}</span>
                        </div>
                        <p className={res.ok ? 'text-muted-foreground' : 'text-destructive'}>
                          {res.message || (res.ok ? '同步成功' : '同步失败')}
                        </p>
                      </div>
                    </div>
                    {!res.ok && (
                      <Button
                        size="sm"
                        variant="ghost"
                        className="h-7 text-xs"
                        onClick={() => onFillHostPassword(res.host)}
                      >
                        <KeyRound className="size-3" />
                        填写单独密码
                      </Button>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </section>
  );
}
