import { useQuery } from '@tanstack/react-query';
import { api } from './api';
import DeviceBackupSection from './components/device/DeviceBackupSection';
import DeviceIntegrationSection from './components/device/DeviceIntegrationSection';
import DeviceOverviewSection from './components/device/DeviceOverviewSection';
import DeviceSecuritySection from './components/device/DeviceSecuritySection';
import DeviceUpdateSection from './components/device/DeviceUpdateSection';

export default function DevicePanel({
  sessionId,
  onPasswordChanged,
  only,
  onOpenKeepAlive,
}: {
  sessionId: number;
  onPasswordChanged: () => void;
  only: 'device' | 'update' | 'security' | 'integration';
  onOpenKeepAlive?: () => void;
}) {
  const device = useQuery({
    queryKey: ['device', sessionId],
    queryFn: () => api('/api/device'),
    refetchInterval: 15000,
  });

  return (
    <div className="space-y-4">
      {only === 'device' && (
        <>
          <DeviceOverviewSection
            sessionId={sessionId}
            deviceData={device.data}
            onOpenKeepAlive={onOpenKeepAlive}
          />
          <DeviceBackupSection />
        </>
      )}
      {only === 'security' && <DeviceSecuritySection onPasswordChanged={onPasswordChanged} />}
      {only === 'integration' && (
        <DeviceIntegrationSection sessionId={sessionId} deviceData={device.data} />
      )}
      {only === 'update' && (
        <DeviceUpdateSection sessionId={sessionId} deviceData={device.data} />
      )}
    </div>
  );
}
