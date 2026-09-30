-- 外部运行时项目（根因报告、鱼骨图）用数据标记，不再只靠前端写死项目 id。
alter table midplat_platform add column agent_mode varchar(32);

update midplat_platform
   set agent_mode = 'external'
 where id in ('plat-root-cause', 'plat-fishbone');
