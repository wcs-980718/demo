import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Empty, Spin } from 'antd';
import { useParams } from '@umijs/max';
import { midplatApi } from '@/api/midplatApi';
import { flattenMenus } from '@/menuHref';
import PlatformsPage from '@/pages/platforms';
import { getScene } from '@/pages/entry/sceneCatalog';

export default function EntryScenePage() {
  const { sceneId } = useParams<{ sceneId: string }>();
  const scene = getScene(sceneId);
  const menusQuery = useQuery({ queryKey: ['menus'], queryFn: midplatApi.listMenus });

  const menu = useMemo(() => {
    const all = flattenMenus(menusQuery.data ?? []);
    return all.find((item) => item.id === scene?.menuId || item.path === `/entry/scene/${sceneId}`) ?? null;
  }, [menusQuery.data, scene, sceneId]);

  if (menusQuery.isLoading) {
    return <div className="loading-panel"><Spin size="large" /></div>;
  }

  if (!scene || !menu) {
    return (
      <div className="page-shell">
        <div className="empty-panel"><Empty description="未找到该 AI 工作台分类。" /></div>
      </div>
    );
  }

  return (
    <PlatformsPage
      key={sceneId}
      category={{
        menu,
        description: `面向 ${scene.audience}。`,
      }}
    />
  );
}
