-- Add the hospital-facing capability publication module as a first-level menu.
update midplat_menu set sort_order = 30 where id = 'menu-platforms';
update midplat_menu set sort_order = 40 where id = 'menu-settings';

insert into midplat_menu (
    id, parent_id, name, route_name, icon, platform_id,
    sort_order, visible, locked, version, path, file_path
)
select
    'menu-capabilities', null, '能力开放中心', 'capabilities', 'Network', null,
    20, true, true, 0, '/capabilities', 'src/pages/capabilities/index.tsx'
where not exists (
    select 1 from midplat_menu where id = 'menu-capabilities'
);
