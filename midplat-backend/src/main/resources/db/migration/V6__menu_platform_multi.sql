-- 菜单-平台多绑关联表（join table，保留 midplat_menu.platform_id 兼容读）
create table midplat_menu_platform (
    menu_id     varchar(64) not null,
    platform_id varchar(64) not null,
    constraint pk_menu_platform primary key (menu_id, platform_id),
    constraint uq_platform_id unique (platform_id),
    constraint fk_menu_platform_menu foreign key (menu_id) references midplat_menu(id) on delete cascade,
    constraint fk_menu_platform_platform foreign key (platform_id) references midplat_platform(id) on delete cascade
);

create index idx_menu_platform_menu on midplat_menu_platform(menu_id);

-- 迁移：将已有 platform_id 非空的菜单行写入 join 表
insert into midplat_menu_platform (menu_id, platform_id)
select id, platform_id from midplat_menu where platform_id is not null;
