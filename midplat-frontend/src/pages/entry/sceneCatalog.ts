export const SCENE_CATALOG = [
  {
    id: 'insight',
    menuId: 'menu-scene-insight',
    name: '智能应用',
    shortName: '智能应用',
    audience: '医院管理、运营、质控人员',
    icon: projectIcon('plat-qa'),
    platformIds: [
      'plat-qa',
      'plat-root-cause',
      'plat-fishbone',
      'plat-data-integration',
      'plat-smart-exploration',
      'plat-asset-catalog',
      'plat-metadata',
      'plat-data-dev',
    ],
    caps: ['智能问数', '根因报告分析', '鱼骨图分析', '数据集成', '智能探测', '资产目录', '元数据管理', '数据开发'],
    capabilities: [
      { name: '智能问数', desc: '自然语言问数', livePlatformId: 'plat-qa' },
      { name: '根因报告分析', desc: '不良事件根因分析报告', livePlatformId: 'plat-root-cause' },
      { name: '鱼骨图分析', desc: '结构化归因与主要原因识别', livePlatformId: 'plat-fishbone' },
      { name: '数据集成', desc: '采集与交换任务编排', livePlatformId: 'plat-data-integration' },
      { name: '智能探测', desc: '数据质量智能探查', livePlatformId: 'plat-smart-exploration' },
      { name: '资产目录', desc: '数据资产编目与检索', livePlatformId: 'plat-asset-catalog' },
      { name: '元数据管理', desc: '元数据采集与血缘维护', livePlatformId: 'plat-metadata' },
      { name: '数据开发', desc: '数据服务开发与编排', livePlatformId: 'plat-data-dev' },
    ],
  },
  {
    id: 'knowledge',
    menuId: 'menu-scene-knowledge',
    name: '知识库',
    shortName: '知识库',
    audience: '科研与运营人员',
    icon: projectIcon('plat-kb'),
    platformIds: ['plat-kb'],
    caps: ['知识库'],
    capabilities: [
      { name: '知识库', desc: '基于知识库问答', livePlatformId: 'plat-kb' },
    ],
  },
  {
    id: 'label',
    menuId: 'menu-scene-label',
    name: '数据标注平台',
    shortName: '数据标注平台',
    audience: '临床人员',
    icon: projectIcon('plat-an'),
    platformIds: ['plat-an'],
    caps: ['问诊与病历文本标注'],
    capabilities: [
      { name: '问诊与病历文本标注', desc: '临床字段、患者画像、症状与疾病候选标注', livePlatformId: 'plat-an' },
    ],
  },
] as const;

export const FEATURED_PLATFORMS = [
  { platformId: 'plat-kb', sceneId: 'knowledge', desc: '指南、文献和院内知识的检索增强问答。' },
  { platformId: 'plat-qa', sceneId: 'insight', desc: '自然语言查询运营与科室数据。' },
  { platformId: 'plat-an', sceneId: 'label', desc: '问诊单、病历文本的临床语义标注，支持自动预标注、人工复核与结果发布。' },
] as const;

export type SceneId = (typeof SCENE_CATALOG)[number]['id'];

export function getScene(id: string | undefined) {
  return SCENE_CATALOG.find((item) => item.id === id) ?? null;
}

export function fallbackSceneMenus() {
  return SCENE_CATALOG.map((scene, index) => ({
    id: scene.menuId,
    parentId: 'menu-entry',
    name: scene.name,
    routeName: 'entry-scene',
    path: `/entry/scene/${scene.id}`,
    filePath: 'src/pages/entry/scene.tsx',
    icon: scene.icon,
    platformId: scene.platformIds[0] ?? null,
    platformIds: [...scene.platformIds],
    sortOrder: (index + 1) * 10,
    visible: true,
    locked: false,
    children: [],
  }));
}
import { projectIcon } from '@/projectIcons';
