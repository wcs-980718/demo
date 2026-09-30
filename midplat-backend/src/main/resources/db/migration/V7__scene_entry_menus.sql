-- 平台分类下六个医疗场景子菜单；已上线平台挂到对应场景（一平台一入口）

update midplat_menu set name = '平台分类' where id = 'menu-entry';

delete from midplat_menu_platform
 where menu_id in ('menu-entry-kb', 'menu-entry-an', 'menu-entry-qa')
    or platform_id in ('plat-kb', 'plat-qa', 'plat-an');

delete from midplat_menu
 where id in ('menu-entry-kb', 'menu-entry-an', 'menu-entry-qa');

update midplat_menu
   set platform_id = null
 where platform_id in ('plat-kb', 'plat-qa', 'plat-an')
   and id not in ('menu-scene-insight', 'menu-scene-knowledge', 'menu-scene-label');

insert into midplat_menu (id, parent_id, name, route_name, icon, platform_id, sort_order, visible, locked, version, path, file_path)
select 'menu-scene-patient', 'menu-entry', '智能问诊与患者服务', 'entry-scene', 'Stethoscope', null, 10, true, false, 0, '/entry/scene/patient', 'src/pages/entry/scene.tsx'
from (select 1) as dual
where not exists (select 1 from midplat_menu where id = 'menu-scene-patient');

insert into midplat_menu (id, parent_id, name, route_name, icon, platform_id, sort_order, visible, locked, version, path, file_path)
select 'menu-scene-clinic', 'menu-entry', '临床决策与病历文书', 'entry-scene', 'ClipboardList', null, 20, true, false, 0, '/entry/scene/clinic', 'src/pages/entry/scene.tsx'
from (select 1) as dual
where not exists (select 1 from midplat_menu where id = 'menu-scene-clinic');

insert into midplat_menu (id, parent_id, name, route_name, icon, platform_id, sort_order, visible, locked, version, path, file_path)
select 'menu-scene-image', 'menu-entry', '医学影像 AI', 'entry-scene', 'ScanLine', null, 30, true, false, 0, '/entry/scene/image', 'src/pages/entry/scene.tsx'
from (select 1) as dual
where not exists (select 1 from midplat_menu where id = 'menu-scene-image');

insert into midplat_menu (id, parent_id, name, route_name, icon, platform_id, sort_order, visible, locked, version, path, file_path)
select 'menu-scene-insight', 'menu-entry', '医院数据洞察', 'entry-scene', 'LineChart', 'plat-qa', 40, true, false, 0, '/entry/scene/insight', 'src/pages/entry/scene.tsx'
from (select 1) as dual
where not exists (select 1 from midplat_menu where id = 'menu-scene-insight');

insert into midplat_menu (id, parent_id, name, route_name, icon, platform_id, sort_order, visible, locked, version, path, file_path)
select 'menu-scene-knowledge', 'menu-entry', '医学知识与科研', 'entry-scene', 'Library', 'plat-kb', 50, true, false, 0, '/entry/scene/knowledge', 'src/pages/entry/scene.tsx'
from (select 1) as dual
where not exists (select 1 from midplat_menu where id = 'menu-scene-knowledge');

insert into midplat_menu (id, parent_id, name, route_name, icon, platform_id, sort_order, visible, locked, version, path, file_path)
select 'menu-scene-label', 'menu-entry', '医疗数据标注与 AI 训练', 'entry-scene', 'PenLine', 'plat-an', 60, true, false, 0, '/entry/scene/label', 'src/pages/entry/scene.tsx'
from (select 1) as dual
where not exists (select 1 from midplat_menu where id = 'menu-scene-label');

insert into midplat_menu_platform (menu_id, platform_id)
select 'menu-scene-insight', 'plat-qa'
from (select 1) as dual
where not exists (select 1 from midplat_menu_platform where platform_id = 'plat-qa');

insert into midplat_menu_platform (menu_id, platform_id)
select 'menu-scene-knowledge', 'plat-kb'
from (select 1) as dual
where not exists (select 1 from midplat_menu_platform where platform_id = 'plat-kb');

insert into midplat_menu_platform (menu_id, platform_id)
select 'menu-scene-label', 'plat-an'
from (select 1) as dual
where not exists (select 1 from midplat_menu_platform where platform_id = 'plat-an');
