import type { ReactNode } from 'react';
import { Controller, type UseFormReturn } from 'react-hook-form';
import { ChevronDown, Clock3 } from 'lucide-react';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field, FieldContent, FieldDescription, FieldGroup, FieldSet, FieldTitle } from '@/components/ui/field';
import { zoneOptions, type Rules } from '@/schema';
import TimePoints from '@/TimePoints';
import WatchWhitelistField from '@/WatchWhitelistField';
import WeekSchedule from '@/WeekSchedule';
import { Choice, FIELD_GROUP_CLASS, Panel } from './RulePrimitives';

type Props = {
  form: UseFormReturn<Rules>;
  c: Rules;
  supervisionEnabled: boolean;
  deviceData: any;
  statusNode?: ReactNode;
  change: (key: any, value: any) => void;
  toggle: (key: any) => ReactNode;
  number: (key: any, min: number, max: number) => ReactNode;
};

export default function TimeRulesTab({
  form,
  c,
  supervisionEnabled,
  deviceData,
  statusNode,
  change,
  toggle,
  number,
}: Props) {
  // Radix 的 SelectItem 不接受空字符串，所以「跟随设备」在控件里用 device 代替
  const zoneChoices = zoneOptions.map(([value, label]) => [value || 'device', label] as [string, string]);

  return (
    <>
      {!supervisionEnabled && (
        <Alert>
          <Clock3 />
          <AlertDescription className="flex flex-wrap items-center gap-3">
            <span>当前为「只学习，不锁屏」模式，不会限制看电视或平板。如需限制观看时长，请切换到管控模式。</span>
            <Button
              type="button"
              size="sm"
              variant="outline"
              className="ms-auto"
              onClick={() => change('supervisionEnabled', true)}
            >
              切换为先答题再看电视
            </Button>
          </AlertDescription>
        </Alert>
      )}
      <div className={supervisionEnabled ? 'space-y-5' : 'space-y-5 opacity-65'}>
        <Panel title="观看时段与防绕过" icon={Clock3} status={statusNode}>
          <FieldSet className="gap-0">
            <FieldGroup className={FIELD_GROUP_CLASS}>
              <Field orientation="responsive" className="py-3.5">
                <FieldContent>
                  <FieldTitle>全天管控</FieldTitle>
                  <FieldDescription className="text-xs leading-5">
                    开启后全天都需要先答题再看；关闭后可在下方按星期和小时自选时段
                  </FieldDescription>
                </FieldContent>
                {toggle('allDay')}
              </Field>
              {!c.allDay && (
                <>
                  <Field orientation="responsive" className="py-3.5">
                    <FieldContent>
                      <FieldTitle>未选中时段怎么处理</FieldTitle>
                      <FieldDescription className="text-xs leading-5">
                        决定下方时间表里灰色时段是直接锁屏，还是不管控自由看
                      </FieldDescription>
                    </FieldContent>
                    <Controller
                      control={form.control}
                      name="scheduleMode"
                      render={({ field }) => (
                        <Choice
                          label="未选中时段怎么处理"
                          value={field.value || 'allow'}
                          onChange={v => field.onChange(v)}
                          options={[
                            ['allow', '未选时段直接锁屏（不给看）'],
                            ['control', '未选时段不管控（自由看）'],
                          ]}
                        />
                      )}
                    />
                  </Field>
                  <WeekSchedule
                    value={c.weekSlots}
                    onChange={v => change('weekSlots', v)}
                    holidayMode={c.holidayMode || 'sunday'}
                    onHolidayMode={v => change('holidayMode', v)}
                    scheduleMode={c.scheduleMode || 'allow'}
                  />
                </>
              )}
              <Field orientation="responsive" className="py-3.5">
                <FieldContent>
                  <FieldTitle>连续看太久强制休息</FieldTitle>
                  <FieldDescription className="text-xs leading-5">
                    看满设定时间就强制休息护眼；关屏和答题不计时，重启也跳不过
                  </FieldDescription>
                </FieldContent>
                {toggle('restEnabled')}
              </Field>
              {c.restEnabled && (
                <>
                  <Field orientation="responsive" className="py-3.5">
                    <FieldContent>
                      <FieldTitle>连续观看多久触发休息（分钟）</FieldTitle>
                    </FieldContent>
                    {number('restWatchMinutes', 1, 480)}
                  </Field>
                  <Field orientation="responsive" className="py-3.5">
                    <FieldContent>
                      <FieldTitle>每次强制休息（分钟）</FieldTitle>
                    </FieldContent>
                    {number('restMinutes', 1, 120)}
                  </Field>
                </>
              )}
              <Field orientation="responsive" className="py-3.5">
                <FieldContent>
                  <FieldTitle>防孩子进「系统设置」卸载或改时间</FieldTitle>
                  <FieldDescription className="text-xs leading-5">
                    默认开启，拦截打开系统设置与应用管理；家长可输管理密码临时进入 2 分钟（需无障碍或使用情况权限）
                  </FieldDescription>
                </FieldContent>
                {toggle('blockSettingsDuringControl')}
              </Field>
              <Field orientation="responsive" className="py-3.5">
                <FieldContent>
                  <FieldTitle>锁屏页支持输密码临时解锁</FieldTitle>
                  <FieldDescription className="text-xs leading-5">
                    在答题锁屏页显示密码入口，家长输入管理密码可临时解锁 30 分钟
                  </FieldDescription>
                </FieldContent>
                {toggle('allowQuizUnlock')}
              </Field>
              <Field className="py-3.5">
                <FieldContent>
                  <FieldTitle>不计时的应用</FieldTitle>
                  <FieldDescription className="text-xs leading-5">
                    这些应用打开时不扣观看时长、不弹题，适合网课软件或屏保（最多 100 个）
                  </FieldDescription>
                  {!!(c.watchWhitelist && c.watchWhitelist.length > 0) &&
                    deviceData?.monitor &&
                    !deviceData.monitor.accessibility &&
                    !deviceData.monitor.usage && (
                      <p className="mt-1.5 text-xs leading-5 text-amber-600 dark:text-amber-400">
                        ⚠ 当前设备未开启「使用情况访问」和「无障碍服务」，系统无法识别正在运行的应用，白名单暂不会生效；请到「设置 → 设备」开启其中任意一项。
                      </p>
                    )}
                </FieldContent>
                <Controller
                  name="watchWhitelist"
                  control={form.control}
                  render={({ field }) => (
                    <WatchWhitelistField value={field.value || []} onChange={field.onChange} />
                  )}
                />
              </Field>
            </FieldGroup>
            <details className="group border-t border-border/60">
              <summary className="flex cursor-pointer list-none items-center gap-1.5 py-3.5 text-xs text-muted-foreground transition-colors hover:text-foreground [&::-webkit-details-marker]:hidden">
                <ChevronDown className="size-3 transition-transform group-open:rotate-180" />
                高级兼容设置（正常可不改）
              </summary>
              <FieldGroup className={FIELD_GROUP_CLASS}>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>结合网速判断是否在看视频</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      有的电视识别不到视频软件。开启后，只要有声音且有明显网络下载，就当作在看视频
                    </FieldDescription>
                  </FieldContent>
                  {toggle('trafficAssistEnabled')}
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>后台防休眠保活（小米等电视专用）</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      帮助维持后台计时不断线；若发现个别机型计时反而异常，可尝试关闭
                    </FieldDescription>
                  </FieldContent>
                  {toggle('backgroundAnchorEnabled')}
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>强制全屏弹题（海信等防遮挡）</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      适合悬浮窗容易被视频画面盖住的电视（如海信 VIDAA）
                    </FieldDescription>
                  </FieldContent>
                  {toggle('forceFullscreenGate')}
                </Field>
                <Field orientation="responsive" className="py-3.5">
                  <FieldContent>
                    <FieldTitle>设备时区</FieldTitle>
                    <FieldDescription className="text-xs leading-5">
                      可用时段和每天 0 点重置都按这个时区算。
                      {!c.timezone && deviceData?.timezone
                        ? `设备当前：${deviceData.timezone}`
                        : '人在海外或设备时区不准时改这里。'}
                    </FieldDescription>
                  </FieldContent>
                  <Choice
                    value={c.timezone || 'device'}
                    onChange={v => change('timezone', v === 'device' ? '' : v)}
                    options={zoneChoices}
                    label="设备时区"
                  />
                </Field>
              </FieldGroup>
            </details>
          </FieldSet>
        </Panel>
        <Panel
          title="网课与大人免打扰时段"
          icon={Clock3}
          description="网课时段可免弹题或单独限额；大人免打扰时段内不弹题（支持跨午夜）"
        >
          <TimePoints
            points={c.timePoints}
            exempt={c.exemptWindows}
            onPoints={v => change('timePoints', v)}
            onExempt={v => change('exemptWindows', v)}
          />
        </Panel>
      </div>
    </>
  );
}
