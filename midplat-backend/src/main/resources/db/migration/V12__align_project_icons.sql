-- Use semantic icons consistently across project menus and platform cards.
update midplat_platform set icon = 'LineChart' where id = 'plat-qa';
update midplat_platform set icon = 'BookOpenText' where id = 'plat-kb';
update midplat_platform set icon = 'Tags' where id = 'plat-an';

update midplat_menu set icon = 'LineChart' where id = 'menu-scene-insight';
update midplat_menu set icon = 'BookOpenText' where id = 'menu-scene-knowledge';
update midplat_menu set icon = 'Tags' where id = 'menu-scene-label';
