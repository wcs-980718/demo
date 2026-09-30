update midplat_menu
   set sort_order = 40
 where id = 'menu-capabilities';

update midplat_menu
   set sort_order = 50
 where id = 'menu-settings';

insert into midplat_menu (
    id, parent_id, name, route_name, icon, platform_id,
    sort_order, visible, locked, version, path, file_path
)
select
    'menu-evaluation', null, '评测治理', 'evaluation', 'Gauge', null,
    30, true, true, 0, '/evaluation', 'src/pages/evaluation/index.tsx'
where not exists (select 1 from midplat_menu where id = 'menu-evaluation');
