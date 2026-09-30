import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Empty, Spin } from 'antd';
import { useParams } from '@umijs/max';
import { midplatApi } from '@/api/midplatApi';
import { flattenMenus } from '@/menuHref';
import PlatformsPage from '@/pages/platforms';

export default function EntryCategoryPage() {
  const { menuId } = useParams<{ menuId: string }>();
  const menusQuery = useQuery({ queryKey: ['menus'], queryFn: midplatApi.listMenus });

  const menu = useMemo(
    () => flattenMenus(menusQuery.data ?? []).find((item) => item.id === menuId) ?? null,
    [menusQuery.data, menuId],
  );

  if (menusQuery.isLoading) {
    return <div className="loading-panel"><Spin size="large" /></div>;
  }

  if (!menu) {
    return (
      <div className="page-shell">
        <div className="empty-panel"><Empty description="未找到该 AI 工作台分类。" /></div>
      </div>
    );
  }

  return (
    <PlatformsPage
      key={menuId}
      category={{
        menu,
        description: `${menu.name}分类中的项目统一在此登记和配置。`,
      }}
    />
  );
}
