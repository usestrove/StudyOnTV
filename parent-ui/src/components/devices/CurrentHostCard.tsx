import { LoaderCircle, Plus, RefreshCw, Smartphone, Tv } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { SectionHeading } from '@/components/ui/section-heading';

export type SelfDeviceSummary = {
  name: string;
  host: string;
  version: string;
  isTv: boolean;
  questions?: number;
};

type Props = {
  selfInfo: SelfDeviceSummary;
  scanning: boolean;
  onRescan: () => void;
  onOpenAdd: () => void;
};

export default function CurrentHostCard({ selfInfo, scanning, onRescan, onOpenAdd }: Props) {
  return (
    <section className="space-y-2.5">
      <SectionHeading
        icon={Tv}
        title="当前主控设备"
        description="批量同步将把这台设备的学习计划、题库或配置同步到下方勾选的其他设备。"
      />
      <Card className="border-primary/25 bg-primary/[0.03] shadow-xs">
        <CardContent className="flex flex-wrap items-center justify-between gap-3 py-4">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
              {selfInfo.isTv ? <Tv className="size-5" /> : <Smartphone className="size-5" />}
            </div>
            <div className="min-w-0 space-y-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="truncate text-sm font-semibold">{selfInfo.name}</span>
                <Badge className="bg-emerald-600 text-white hover:bg-emerald-600">当前管理中</Badge>
                <Badge variant="outline">{selfInfo.isTv ? '电视版' : '手机 / 平板版'}</Badge>
              </div>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                <span className="font-mono">{selfInfo.host}</span>
                {selfInfo.version && <span>v{selfInfo.version}</span>}
                {typeof selfInfo.questions === 'number' && <span>{selfInfo.questions} 道题</span>}
              </div>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="outline" size="sm" onClick={onRescan} disabled={scanning}>
              {scanning ? <LoaderCircle className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
              重新扫描
            </Button>
            <Button size="sm" onClick={onOpenAdd}>
              <Plus className="size-4" />
              手动添加设备
            </Button>
          </div>
        </CardContent>
      </Card>
    </section>
  );
}
