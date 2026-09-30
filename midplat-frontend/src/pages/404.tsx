import { Button, Result } from 'antd';
import { useNavigate } from '@umijs/max';

export default function NotFound() {
  const navigate = useNavigate();
  return (
    <div className="page-shell">
      <Result
        status="404"
        title="页面不存在"
        subTitle="你访问的地址不存在或已被移除。"
        extra={<Button type="primary" onClick={() => navigate('/home')}>返回首页</Button>}
      />
    </div>
  );
}
