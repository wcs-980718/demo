-- 菜单自定义路由 path 与文件路径 filePath（记录型，旧数据留 null 回落标准路由）
alter table midplat_menu add column if not exists path varchar(128);
alter table midplat_menu add column if not exists file_path varchar(256);
create index if not exists idx_menu_path on midplat_menu(path);
