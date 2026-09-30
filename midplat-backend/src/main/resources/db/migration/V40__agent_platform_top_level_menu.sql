-- 智能体平台升级为一级菜单，并把智能体工作台左侧导航的 8 个视图挂为子菜单。
-- 背景：原 menu-developer-agent-platform 挂在开发中心下，路径 /agent-platform 仅重定向到
-- /agent-hub/overview；工作台内部导航（总览/智能体/能力资产/版本与发布/运行记录/API 接入/
-- 治理设置/审计日志）一直只在页面内渲染，主应用 header 无法直达。
-- 目标：一级菜单「智能体平台」落在 AI 工作台与开发中心之间；子菜单直接指向 /agent-hub/* 视图。
-- 锁定与可见性与现有系统菜单保持一致（locked=true，visible=true），菜单设置页可调整排序但不可删除。

-- 1) 提升为一级菜单，修正落地路径与排序（20=AI 工作台，25=智能体平台，30=开发中心）
update midplat_menu
   set parent_id = null,
       path = '/agent-hub/overview',
       sort_order = 25,
       file_path = 'src/pages/agent-hub/index.tsx'
 where id = 'menu-developer-agent-platform';

-- 2) 工作台 8 个视图挂为子菜单；route_name 走自定义路由，path/file_path 直接写实
--    （validateCustomRoute 要求自定义路由提供 / 开头的 path 与 src/pages/... 的 file_path）
insert into midplat_menu (
    id, parent_id, name, route_name, icon, platform_id,
    sort_order, visible, locked, version, path, file_path
)
select * from (values
    ('menu-agent-hub-overview', 'menu-developer-agent-platform', '工作台总览', 'agent-hub-overview', 'LayoutDashboard', null, 10, true, true, 0, '/agent-hub/overview', 'src/pages/agent-hub/index.tsx'),
    ('menu-agent-hub-agents',   'menu-developer-agent-platform', '智能体',     'agent-hub-agents',   'Bot',           null, 20, true, true, 0, '/agent-hub/agents',   'src/pages/agent-hub/index.tsx'),
    ('menu-agent-hub-assets',   'menu-developer-agent-platform', '能力资产',   'agent-hub-assets',   'Boxes',         null, 30, true, true, 0, '/agent-hub/assets',   'src/pages/agent-hub/index.tsx'),
    ('menu-agent-hub-releases', 'menu-developer-agent-platform', '版本与发布', 'agent-hub-releases', 'GitBranch',      null, 40, true, true, 0, '/agent-hub/releases', 'src/pages/agent-hub/index.tsx'),
    ('menu-agent-hub-runs',     'menu-developer-agent-platform', '运行记录',   'agent-hub-runs',     'Activity',       null, 50, true, true, 0, '/agent-hub/runs',     'src/pages/agent-hub/index.tsx'),
    ('menu-agent-hub-use',      'menu-developer-agent-platform', 'API 接入',   'agent-hub-use',      'Plug',          null, 60, true, true, 0, '/agent-hub/use',      'src/pages/agent-hub/index.tsx'),
    ('menu-agent-hub-settings', 'menu-developer-agent-platform', '治理设置',   'agent-hub-settings', 'Settings2',     null, 70, true, true, 0, '/agent-hub/settings', 'src/pages/agent-hub/index.tsx'),
    ('menu-agent-hub-audit',    'menu-developer-agent-platform', '审计日志',   'agent-hub-audit',    'FileClock',      null, 80, true, true, 0, '/agent-hub/audit',    'src/pages/agent-hub/index.tsx')
) as t(id, parent_id, name, route_name, icon, platform_id, sort_order, visible, locked, version, path, file_path)
where not exists (select 1 from midplat_menu m where m.id = t.id);
