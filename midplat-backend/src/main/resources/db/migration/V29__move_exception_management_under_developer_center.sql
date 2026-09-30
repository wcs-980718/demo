-- 异常记录属于开发接入和联调工具，移动到开发中心并统一命名为“异常管理”。
update midplat_menu
   set parent_id = 'menu-capabilities',
       name = '异常管理',
       sort_order = 50,
       updated_at = current_timestamp
 where id = 'menu-evaluation';
