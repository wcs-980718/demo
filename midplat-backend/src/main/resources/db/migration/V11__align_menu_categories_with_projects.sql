-- Align menu categories with the three projects managed by the mid-platform.
update midplat_menu
   set name = 'AI 工作台'
 where id = 'menu-entry';

update midplat_menu
   set name = '智能问数'
 where id = 'menu-scene-insight';

update midplat_menu
   set name = '数据标注平台'
 where id = 'menu-scene-label';
