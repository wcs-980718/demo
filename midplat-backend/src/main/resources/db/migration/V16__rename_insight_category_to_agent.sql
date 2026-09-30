-- 分类名称与项目名称分离：分类为“智能体”，其下项目仍保留各自名称。
update midplat_menu
   set name = '智能体'
 where id = 'menu-scene-insight';
