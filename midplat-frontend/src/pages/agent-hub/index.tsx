import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Spin } from 'antd';
import FusionWorkspace from './FusionWorkspace';
import { fusionApi } from './fusionApi';
export default function AgentHubEntry() {
 const mode = useQuery({ queryKey: ['fusion-status'], queryFn: fusionApi.status, retry: false });
 useEffect(() => { try { localStorage.removeItem('midplat-agent-hub-demo-v3'); sessionStorage.removeItem('hub-demo-role'); } catch {} }, []);
 if (mode.isPending) return <Spin><div style={{ minHeight: 200 }} /></Spin>;
 if (mode.error) return <Alert type="error" showIcon title="无法确认融合服务状态" description={(mode.error as Error).message} action={<Button onClick={() => void mode.refetch()}>重试</Button>} />;
 if (!mode.data?.enabled) return <Alert type="info" showIcon title="智能体管理尚未启用" description="当前中台尚未启用智能体运行组件。" />;
 const environment = mode.data.runtimeEnvironment;
 if (environment !== 'development' && environment !== 'staging' && environment !== 'production') return <Alert type="warning" showIcon title="工作台服务配置尚未就绪" description="请稍后重试，或联系管理员确认服务配置。" action={<Button onClick={() => void mode.refetch()}>重试</Button>} />;
 return <FusionWorkspace environment={environment} />;
}
