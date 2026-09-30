-- 评测治理页已替换为异常历史（各项目报错主动上报），菜单名同步更新。
update midplat_menu
   set name = '异常历史'
 where id = 'menu-evaluation'
   and name in ('评测治理', '评测治理（开发中）');
