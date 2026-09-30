-- 项目配置已合并到 AI 工作台分类页，不再保留独立一级菜单。
delete from midplat_menu_platform where menu_id = 'menu-platforms';
delete from midplat_menu where id = 'menu-platforms';

update midplat_menu set sort_order = 30 where id = 'menu-settings';
