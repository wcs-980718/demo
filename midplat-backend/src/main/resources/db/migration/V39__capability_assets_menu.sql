-- 开发中心新增“能力资产”菜单：独立的能力资产管理页面；
-- 现有“对外接口设置”(menu-developer-apis) 保持不变。
insert into midplat_menu (
    id, parent_id, name, route_name, icon, platform_id,
    sort_order, visible, locked, version, path, file_path
)
select
    'menu-developer-assets', 'menu-capabilities', '能力资产', 'capability-assets', 'Boxes', null,
    60, true, true, 0, '/capabilities/assets', 'src/pages/capability-assets/index.tsx'
where not exists (
    select 1 from midplat_menu where id = 'menu-developer-assets'
);
