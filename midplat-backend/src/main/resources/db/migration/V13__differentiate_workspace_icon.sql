-- Distinguish the AI workspace from the home navigation item.
update midplat_menu
   set icon = 'Workflow'
 where id = 'menu-entry';
