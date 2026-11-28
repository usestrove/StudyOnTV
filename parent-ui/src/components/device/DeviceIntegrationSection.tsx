import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Copy, RadioTower } from 'lucide-react';
import { toast } from 'sonner';
import { api } from '@/api';
import { copyText } from '@/lib/clipboard';
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/ui/accordion';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Field, FieldLabel } from '@/components/ui/field';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { SettingItem, SettingsCard } from './DeviceSettingsFrame';

export default function DeviceIntegrationSection({
  sessionId,
  deviceData,
}: {
  sessionId: number;
  deviceData: any;
}) {
  const client = useQueryClient();
  const integration = useQuery({
    queryKey: ['integration', sessionId],
    queryFn: () => api('/api/integration'),
  });
  const [scope, setScope] = useState({ read: true, control: false });
  const [issued, setIssued] = useState('');

  useEffect(() => {
    if (integration.data) {
      setScope({ read: !!integration.data.read, control: !!integration.data.control });
    }
  }, [integration.data?.read, integration.data?.control, integration.data?.hasToken]);

  const issue = useMutation({
    mutationFn: () => api('/api/integration', 'POST', scope),
    onSuccess: (result: any) => {
      setIssued(result.token);
      client.setQueryData(['integration', sessionId], result.integration);
      toast.success('集成令牌已生成');
    },
    onError: (error: any) => toast.error(error.message),
  });
  const patch = useMutation({
    mutationFn: (body: any) => api('/api/integration', 'PUT', body),
    onSuccess: (result: any) => {
      client.setQueryData(['integration', sessionId], result);
      toast.success('已保存');
    },
    onError: (error: any) => {
      if (integration.data) {
        setScope({ read: !!integration.data.read, control: !!integration.data.control });
      }
      toast.error(error.message);
    },
  });
  const revoke = useMutation({
    mutationFn: () => api('/api/integration', 'DELETE'),
    onSuccess: (result: any) => {
      client.setQueryData(['integration', sessionId], result);
      setIssued('');
      toast.success('集成令牌已吊销');
    },
    onError: (error: any) => toast.error(error.message),
  });

  const copy = async (value: string) => {
    const result = await copyText(value);
    if (result === 'ok') toast.success('已复制');
    else toast.error('复制失败，请手动选中文字复制');
  };

  const integrationBase = `http://${deviceData?.ip || '设备IP'}:${deviceData?.port || 8099}`;
  const displayToken = issued || '<集成令牌>';
  const stateCurl = `curl "${integrationBase}/api/state" -H "Authorization: Bearer ${displayToken}"`;
  const unlockCurl = `curl -X POST "${integrationBase}/api/remote" -H "Authorization: Bearer ${displayToken}" -H "Content-Type: application/json" -d '{"action":"unlock","minutes":30}'`;
  const haYaml = `sensor:\n  - platform: rest\n    name: 题了个题\n    resource: ${integrationBase}/api/state\n    headers:\n      Authorization: Bearer <集成令牌>\n    value_template: "{{ value_json.learning.remainingSeconds }}"\n    json_attributes:\n      - learning\n      - today\n    scan_interval: 60`;

  return (
    <>
      <SettingsCard
        title="家庭自动化"
        icon={RadioTower}
        description="供 Home Assistant 等使用，可吊销。"
      >
        <SettingItem title="启用接口" description="只接受局域网请求。">
          <Switch
            aria-label="启用家庭自动化接口"
            checked={!!integration.data?.enabled}
            onCheckedChange={enabled => patch.mutate({ enabled })}
          />
        </SettingItem>
        <SettingItem
          title="集成令牌"
          description={
            integration.data?.hasToken
              ? `${integration.data.hint} · 创建于 ${new Date(integration.data.createdAt).toLocaleString('zh-CN', { hour12: false })}`
              : '尚未生成。令牌只在创建时显示一次。'
          }
        >
          <Button
            variant="outline"
            onClick={() => issue.mutate()}
            disabled={issue.isPending || (!scope.read && !scope.control)}
          >
            {integration.data?.hasToken ? '重新生成' : '生成令牌'}
          </Button>
          {integration.data?.hasToken && (
            <Button
              variant="ghost"
              onClick={() => {
                if (window.confirm('吊销后使用该令牌的系统会立即断开，确定吗？')) revoke.mutate();
              }}
              disabled={revoke.isPending}
            >
              吊销
            </Button>
          )}
        </SettingItem>
        <SettingItem title="权限" description="只读：查询状态；控制：解锁或暂停。">
          <div className="grid w-full gap-3 sm:w-auto sm:grid-cols-2">
            <Field orientation="horizontal" className="w-auto">
              <Checkbox
                id="integration-read"
                checked={scope.read}
                onCheckedChange={read => {
                  if (!read && !scope.control) return toast.error('至少保留一种权限');
                  setScope({ ...scope, read: !!read });
                  patch.mutate({ read: !!read });
                }}
              />
              <FieldLabel htmlFor="integration-read" className="font-normal">
                只读
              </FieldLabel>
            </Field>
            <Field orientation="horizontal" className="w-auto">
              <Checkbox
                id="integration-control"
                checked={scope.control}
                onCheckedChange={control => {
                  if (!control && !scope.read) return toast.error('至少保留一种权限');
                  setScope({ ...scope, control: !!control });
                  patch.mutate({ control: !!control });
                }}
              />
              <FieldLabel htmlFor="integration-control" className="font-normal">
                控制
              </FieldLabel>
            </Field>
          </div>
        </SettingItem>
        <div className="py-3.5">
          <Accordion type="single" collapsible>
            <AccordionItem value="examples">
              <AccordionTrigger>接口示例与 Home Assistant 配置</AccordionTrigger>
              <AccordionContent className="space-y-4">
                {[
                  ['读取状态', stateCurl],
                  ['临时解锁 30 分钟', unlockCurl],
                  ['Home Assistant REST sensor', haYaml],
                ].map(([label, value]) => (
                  <div key={label} className="space-y-2">
                    <div className="flex items-center justify-between gap-3">
                      <span className="text-xs font-medium">{label}</span>
                      <Button size="sm" variant="ghost" onClick={() => copy(value)}>
                        <Copy />
                        复制
                      </Button>
                    </div>
                    <pre className="overflow-x-auto rounded-lg bg-muted/45 p-3 text-xs">{value}</pre>
                  </div>
                ))}
                <p className="text-xs leading-5 text-muted-foreground">
                  只开放给同一局域网，不要做端口转发。
                </p>
              </AccordionContent>
            </AccordionItem>
          </Accordion>
        </div>
      </SettingsCard>

      <Dialog
        open={!!issued}
        onOpenChange={open => {
          if (!open) setIssued('');
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>复制集成令牌</DialogTitle>
            <DialogDescription>令牌只显示这一次，请保存到密码管理器。</DialogDescription>
          </DialogHeader>
          <Textarea
            aria-label="集成令牌"
            className="min-h-20 break-all font-mono text-xs"
            readOnly
            value={issued}
          />
          <DialogFooter>
            <Button variant="outline" onClick={() => setIssued('')}>
              已保存
            </Button>
            <Button onClick={() => copy(issued)}>
              <Copy />
              复制令牌
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
