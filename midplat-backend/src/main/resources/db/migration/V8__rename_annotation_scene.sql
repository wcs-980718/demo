update midplat_menu
   set name = '医疗数据标注',
       updated_at = current_timestamp
 where id = 'menu-scene-label'
   and name <> '医疗数据标注';
