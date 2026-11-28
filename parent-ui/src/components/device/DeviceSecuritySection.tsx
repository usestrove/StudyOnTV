import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { KeyRound, LoaderCircle } from 'lucide-react';
import { toast } from 'sonner';
import { api, setToken as setSessionToken } from '@/api';
import { Button } from '@/components/ui/button';
import { Field, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { SettingsCard } from './DeviceSettingsFrame';

export default function DeviceSecuritySection({ onPasswordChanged }: { onPasswordChanged: () => void }) {
  const [password, setPassword] = useState({ current: '', next: '', confirm: '' });

  const changePassword = useMutation({
    mutationFn: () =>
      api('/api/password', 'POST', { current: password.current, password: password.next }),
    onSuccess: (res: any) => {
      setPassword({ current: '', next: '', confirm: '' });
      if (res?.token) {
        setSessionToken(res.token);
        toast.success('管理密码已修改，当前设备登录状态已自动续期');
      } else {
        toast.success('管理密码已修改，请用新密码重新登录');
        onPasswordChanged();
      }
    },
    onError: (error: any) => toast.error(error.message),
  });

  function submitPassword() {
    if (password.next.length < 6 || password.next.length > 64) {
      return toast.error('新密码需要 6–64 位');
    }
    if (password.next !== password.confirm) return toast.error('两次输入的新密码不一致');
    changePassword.mutate();
  }

  return (
    <SettingsCard
      title="管理密码"
      icon={KeyRound}
      description="修改后其他设备需重新验证，当前操作设备会自动保持登录。"
      footer={
        <Button
          disabled={changePassword.isPending || !password.current || !password.next || !password.confirm}
          onClick={submitPassword}
        >
          {changePassword.isPending ? <LoaderCircle className="animate-spin" /> : null}
          修改密码
        </Button>
      }
    >
      <FieldGroup className="grid gap-4 py-4 sm:grid-cols-3">
        <Field>
          <FieldLabel htmlFor="current-password">当前密码</FieldLabel>
          <Input
            id="current-password"
            type="password"
            autoComplete="current-password"
            value={password.current}
            onChange={event => setPassword({ ...password, current: event.target.value })}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="new-password">新密码</FieldLabel>
          <Input
            id="new-password"
            type="password"
            autoComplete="new-password"
            placeholder="6–64 位"
            value={password.next}
            onChange={event => setPassword({ ...password, next: event.target.value })}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="confirm-password">确认新密码</FieldLabel>
          <Input
            id="confirm-password"
            type="password"
            autoComplete="new-password"
            value={password.confirm}
            onChange={event => setPassword({ ...password, confirm: event.target.value })}
          />
        </Field>
      </FieldGroup>
    </SettingsCard>
  );
}
