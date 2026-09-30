-- 在「智能体」分类登记五个数据平台入口，点击即可进入对应项目页面。
-- 入口地址为演示环境 malacca 门户（portal.example.com:30200）下的业务子应用。

insert into midplat_platform (
    id, name, entry_url, icon, consume, token,
    llm_model_id, embedding_model_id, rerank_model_id, prompt_id, version
)
select
    'plat-data-integration', '数据集成',
    'http://portal.example.com:30200/esb/agent_mock?tenantId=etl&app=etl',
    'Database', false, null,
    null, null, null, null, 0
where not exists (select 1 from midplat_platform where id = 'plat-data-integration');

insert into midplat_platform (
    id, name, entry_url, icon, consume, token,
    llm_model_id, embedding_model_id, rerank_model_id, prompt_id, version
)
select
    'plat-smart-exploration', '智能探测',
    'http://portal.example.com:30200/dds/exploration/smart',
    'ScanLine', false, null,
    null, null, null, null, 0
where not exists (select 1 from midplat_platform where id = 'plat-smart-exploration');

insert into midplat_platform (
    id, name, entry_url, icon, consume, token,
    llm_model_id, embedding_model_id, rerank_model_id, prompt_id, version
)
select
    'plat-asset-catalog', '资产目录',
    'http://portal.example.com:30200/dam/asset-catalog',
    'Boxes', false, null,
    null, null, null, null, 0
where not exists (select 1 from midplat_platform where id = 'plat-asset-catalog');

insert into midplat_platform (
    id, name, entry_url, icon, consume, token,
    llm_model_id, embedding_model_id, rerank_model_id, prompt_id, version
)
select
    'plat-metadata', '元数据管理',
    'http://portal.example.com:30200/meta/mgmt/maintenanceTree',
    'Layers', false, null,
    null, null, null, null, 0
where not exists (select 1 from midplat_platform where id = 'plat-metadata');

insert into midplat_platform (
    id, name, entry_url, icon, consume, token,
    llm_model_id, embedding_model_id, rerank_model_id, prompt_id, version
)
select
    'plat-data-dev', '数据开发',
    'http://portal.example.com:30200/esb/service?tenantId=esb&app=esb',
    'Waypoints', false, null,
    null, null, null, null, 0
where not exists (select 1 from midplat_platform where id = 'plat-data-dev');

insert into midplat_menu_platform (menu_id, platform_id)
select 'menu-scene-insight', 'plat-data-integration'
where exists (select 1 from midplat_menu where id = 'menu-scene-insight')
  and not exists (select 1 from midplat_menu_platform where platform_id = 'plat-data-integration');

insert into midplat_menu_platform (menu_id, platform_id)
select 'menu-scene-insight', 'plat-smart-exploration'
where exists (select 1 from midplat_menu where id = 'menu-scene-insight')
  and not exists (select 1 from midplat_menu_platform where platform_id = 'plat-smart-exploration');

insert into midplat_menu_platform (menu_id, platform_id)
select 'menu-scene-insight', 'plat-asset-catalog'
where exists (select 1 from midplat_menu where id = 'menu-scene-insight')
  and not exists (select 1 from midplat_menu_platform where platform_id = 'plat-asset-catalog');

insert into midplat_menu_platform (menu_id, platform_id)
select 'menu-scene-insight', 'plat-metadata'
where exists (select 1 from midplat_menu where id = 'menu-scene-insight')
  and not exists (select 1 from midplat_menu_platform where platform_id = 'plat-metadata');

insert into midplat_menu_platform (menu_id, platform_id)
select 'menu-scene-insight', 'plat-data-dev'
where exists (select 1 from midplat_menu where id = 'menu-scene-insight')
  and not exists (select 1 from midplat_menu_platform where platform_id = 'plat-data-dev');
