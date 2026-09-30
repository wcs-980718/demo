-- Use one user-facing name for the knowledge base platform and its menu.
update midplat_platform
   set name = '知识库'
 where id = 'plat-kb';

update midplat_menu
   set name = '知识库'
 where id = 'menu-scene-knowledge';
