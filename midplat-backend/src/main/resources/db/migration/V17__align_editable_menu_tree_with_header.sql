-- 以 Header 当前展示结构为准，将菜单设置中的可管理菜单同步为同一结构。
update midplat_menu set sort_order = 20 where id = 'menu-entry';
update midplat_menu
   set name = '开发中心', sort_order = 30
 where id = 'menu-capabilities';
update midplat_menu set sort_order = 40 where id = 'menu-settings';

insert into midplat_menu (
    id, parent_id, name, route_name, icon, platform_id,
    sort_order, visible, locked, version, path, file_path
)
select
    'menu-home', null, '首页', 'home', 'Home', null,
    10, true, true, 0, '/home', 'src/pages/home/index.tsx'
where not exists (select 1 from midplat_menu where id = 'menu-home');

update midplat_menu
   set parent_id = 'menu-capabilities', sort_order = 10
 where id = 'menu-settings-models';

update midplat_menu
   set parent_id = 'menu-capabilities', sort_order = 20
 where id = 'menu-settings-prompts';

insert into midplat_menu (
    id, parent_id, name, route_name, icon, platform_id,
    sort_order, visible, locked, version, path, file_path
)
select
    'menu-developer-apis', 'menu-capabilities', '对外接口设置', 'capabilities', 'Waypoints', null,
    30, true, true, 0, '/capabilities', 'src/pages/capabilities/index.tsx'
where not exists (select 1 from midplat_menu where id = 'menu-developer-apis');

insert into midplat_menu (
    id, parent_id, name, route_name, icon, platform_id,
    sort_order, visible, locked, version, path, file_path
)
select
    'menu-developer-agent-platform', 'menu-capabilities', '智能体平台', 'agent-platform', 'BrainCircuit', null,
    40, true, true, 0, '/agent-platform', 'src/pages/agent-platform/index.tsx'
where not exists (select 1 from midplat_menu where id = 'menu-developer-agent-platform');
