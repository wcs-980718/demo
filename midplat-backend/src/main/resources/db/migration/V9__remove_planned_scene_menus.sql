-- Remove scene menus that do not have an implemented platform.
delete from midplat_menu_platform
 where menu_id in ('menu-scene-patient', 'menu-scene-clinic', 'menu-scene-image');

delete from midplat_menu
 where id in ('menu-scene-patient', 'menu-scene-clinic', 'menu-scene-image');
