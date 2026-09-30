-- 顶栏与开发中心的名称对齐三层分工。
-- menu-developer-agent-platform 就是原型里的「数据治理智能体开发平台」那一格，改名为「智能体开发」。
-- AI 工作台分类「智能体」改为「智能应用」，避免和智能体列表撞名。
-- 开发中心两项是跨项目汇总，改为总览。工作台项目页自己的「对外接口设置」不在这张菜单表里。

update midplat_menu
   set name = '智能体开发',
       updated_at = current_timestamp
 where id = 'menu-developer-agent-platform';

update midplat_menu
   set name = '智能应用',
       updated_at = current_timestamp
 where id = 'menu-scene-insight';

update midplat_menu
   set name = '对外接口总览',
       updated_at = current_timestamp
 where id = 'menu-developer-apis';

update midplat_menu
   set name = '异常总览',
       updated_at = current_timestamp
 where id = 'menu-evaluation';
