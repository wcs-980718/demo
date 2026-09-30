import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Spin } from 'antd';
import { FusionProjectPanel } from './FusionProjectPanel';
import { fusionApi } from './fusionApi';
export function ProjectAgentPanel({ platformId }: { platformId: string }) {
 const status = useQuery({ queryKey: ['fusion-status'], queryFn: fusionApi.status, retry: false });
 if (status.isPending) return <Spin><div style={{ minHeight: 160 }} /></Spin>;
 if (status.error) return <Alert type="error" showIcon title="无法确认融合服务状态" description={(status.error as Error).message} action={<Button onClick={() => void status.refetch()}>重试</Button>} />;
 const environment = status.data?.runtimeEnvironment;
 if (environment !== 'development' && environment !== 'staging' && environment !== 'production') return <Alert type="warning" showIcon title="工作台服务配置尚未就绪" description="请稍后重试，或联系管理员确认服务配置。" action={<Button onClick={() => void status.refetch()}>重试</Button>} />;
 return <FusionProjectPanel platformId={platformId} environment={environment} />;
}
