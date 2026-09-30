import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AutoComplete, Button, Form, Input, Modal, Popconfirm, Select, Spin, Switch, Tooltip, Table, type TableProps, message } from 'antd';
import { closestCenter, DndContext, DragOverlay, type DragEndEvent, type DragMoveEvent, type DragStartEvent, PointerSensor, useSensor, useSensors } from '@dnd-kit/core';
import { arrayMove, SortableContext, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { ChevronRight, Edit, FileCode, GripVertical, Lock, Plus, Trash2 } from 'lucide-react';
import { midplatApi, type MenuItem, type PlatformItem } from '@/api/midplatApi';
import { ROUTE_CATALOG, getMenuCreateDefaults, isStandardRoute, getRouteMeta } from '@/routeCatalog';
import { IconPicker } from '@/components/IconPicker';
import { LucideIcon } from '@/components/LucideIcon';
import { PageHeader } from '@/components/PageHeader';
import { SettingsSectionHeader } from '@/components/SettingsSectionHeader';
import { flattenMenus } from '@/menuHref';

const ENTRY_MENU_ID = 'menu-entry';

type MenuForm = {
  name: string;
  routeName: string;
  icon: string;
  platformIds?: string[];
  visible: boolean;
  path?: string;
  filePath?: string;
};

const RowDragContext = createContext<{
  attributes: React.HTMLAttributes<HTMLElement>;
  listeners?: React.HTMLAttributes<HTMLElement>;
} | null>(null);

function findMenu(nodes: MenuItem[], id: string): MenuItem | undefined {
  for (const node of nodes) {
    if (node.id === id) return node;
    const hit = findMenu(node.children ?? [], id);
    if (hit) return hit;
  }
  return undefined;
}

function SortableRow({
  id,
  hasChildren,
  isChild,
  onRowClick,
  children,
  ...restProps
}: {
  id: string;
  hasChildren: boolean;
  isChild?: boolean;
  onRowClick?: React.MouseEventHandler<HTMLTableRowElement>;
  children?: React.ReactNode;
} & React.HTMLAttributes<HTMLTableRowElement>) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: id || '__noop__',
    disabled: !id,
  });
  const style: React.CSSProperties = {
    ...restProps.style,
    transform: CSS.Transform.toString(transform),
    transition: isDragging ? transition : undefined,
    cursor: hasChildren ? 'pointer' : undefined,
    opacity: isDragging ? 0.45 : undefined,
  };

  return (
    <RowDragContext.Provider value={{ attributes, listeners }}>
      <tr
        {...restProps}
        ref={setNodeRef}
        style={style}
        className={`${restProps.className ?? ''}${isDragging ? ' is-dragging' : ''}${isChild ? ' menu-table-child' : ' menu-table-parent'}${hasChildren ? ' menu-table-expandable' : ''}`}
        data-dnd-id={id}
        onClick={(e) => {
          const target = e.target as HTMLElement;
          if (target.closest('.menu-drag-handle, .menu-actions, .menu-lock, button, a, .mp-ant-btn, .mp-ant-switch, .mp-ant-select')) return;
          onRowClick?.(e);
        }}
      >
        {children}
      </tr>
    </RowDragContext.Provider>
  );
}

function DragHandleCell() {
  const ctx = useContext(RowDragContext);
  if (!ctx) {
    return <span className="menu-drag-handle menu-drag-handle--ghost"><GripVertical size={14} /></span>;
  }
  return (
    <span
      className="menu-drag-handle"
      aria-label="拖拽排序"
      onClick={(e) => e.stopPropagation()}
      {...ctx.attributes}
      {...ctx.listeners}
    >
      <GripVertical size={14} />
    </span>
  );
}

export default function MenusPage() {
  const queryClient = useQueryClient();
  const menusQuery = useQuery({ queryKey: ['menus'], queryFn: midplatApi.listMenus });
  const platformsQuery = useQuery({ queryKey: ['platforms'], queryFn: midplatApi.listPlatforms });
  const [open, setOpen] = useState(false);
  const [parentId, setParentId] = useState<string | undefined>();
  const [editing, setEditing] = useState<MenuItem | null>(null);
  const [form] = Form.useForm<MenuForm>();
  const routeName = Form.useWatch('routeName', form);

  // Auto-fill path/filePath when routeName changes to a standard route
  useEffect(() => {
    if (isStandardRoute(routeName)) {
      const meta = getRouteMeta(routeName);
      form.setFieldsValue({
        filePath: meta.filePath,
        ...(!meta.path.includes(':') || meta.name === 'entry-category' ? { path: meta.path } : {}),
      });
    }
  }, [routeName, form]);

  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);

  const toggleExpand = (id: string) => {
    setExpandedKeys((prev) =>
      prev.includes(id) ? prev.filter((k) => k !== id) : [...prev, id],
    );
  };

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['menus'] });
  const canBindPlatform = (editing ? editing.parentId : parentId) === ENTRY_MENU_ID;

  const saveMutation = useMutation({
    mutationFn: async (values: MenuForm) => {
      const platformIds = canBindPlatform ? values.platformIds : undefined;
      if (editing) {
        return midplatApi.updateMenu(editing.id, {
          name: values.name,
          routeName: values.routeName,
          icon: values.icon,
          platformId: platformIds?.[0],
          platformIds,
          visible: values.visible,
          path: values.path,
          filePath: values.filePath,
        });
      }
      return midplatApi.createMenu({
        parentId,
        name: values.name,
        routeName: values.routeName,
        icon: values.icon,
        platformId: platformIds?.[0],
        platformIds,
        visible: values.visible,
        path: values.path,
        filePath: values.filePath,
      });
    },
    onSuccess: async () => {
      message.success(editing ? '菜单已更新' : '菜单已创建');
      setOpen(false);
      setEditing(null);
      await invalidate();
    },
    onError: (error: Error) => message.error(error.message),
  });
  const deleteMutation = useMutation({
    mutationFn: midplatApi.deleteMenu,
    onSuccess: async () => {
      message.success('已删除菜单');
      await invalidate();
    },
    onError: (error: Error) => message.error(error.message),
  });

  const openCreate = (id?: string) => {
    setEditing(null);
    setParentId(id);
    form.resetFields();
    form.setFieldsValue(getMenuCreateDefaults(id));
    setOpen(true);
  };

  const openEdit = (item: MenuItem) => {
    setEditing(item);
    setParentId(item.parentId ?? undefined);
    const catalogEntry = ROUTE_CATALOG.find((r) => r.name === item.routeName);
    form.setFieldsValue({
      name: item.name,
      routeName: item.routeName,
      icon: item.icon,
      platformIds: item.platformIds ?? undefined,
      visible: item.visible,
      path: item.path || catalogEntry?.path,
      filePath: item.filePath || catalogEntry?.filePath,
    });
    setOpen(true);
  };

  const tree = menusQuery.data ?? [];
  const allMenus = useMemo(() => flattenMenus(tree), [tree]);

  // ── Drag state ──
  const [activeDragId, setActiveDragId] = useState<string | null>(null);
  const [isReordering, setIsReordering] = useState(false);
  const [forbiddenGroupId, setForbiddenGroupId] = useState<string | null>(null);

  // Map child id → parent group id
  const childParentMap = useMemo(() => {
    const map = new Map<string, string>();
    for (const group of tree) {
      for (const child of group.children ?? []) {
        map.set(child.id, group.id);
      }
    }
    return map;
  }, [tree]);

  // Map platformId → menu name (for occupied platform detection in form)
  const occupiedPlatformMap = useMemo(() => {
    const map = new Map<string, string>();
    const currentId = editing?.id;
    for (const menu of allMenus) {
      if (menu.id === currentId) continue;
      const ids = menu.platformIds ?? (menu.platformId ? [menu.platformId] : []);
      for (const pid of ids) {
        if (!map.has(pid)) map.set(pid, menu.name);
      }
    }
    return map;
  }, [allMenus, editing?.id]);

  const allRowIds = useMemo(
    () => tree.flatMap((group) => [group.id, ...(group.children ?? []).map((child) => child.id)]),
    [tree],
  );

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
  );

  const handleDragStart = useCallback(({ active }: DragStartEvent) => {
    setActiveDragId(String(active.id));
    setForbiddenGroupId(null);
  }, []);

  const handleDragMove = useCallback(({ active, over }: DragMoveEvent) => {
    if (!over) {
      setForbiddenGroupId(null);
      return;
    }
    const activeId = String(active.id);
    const overId = String(over.id);
    const activeParent = childParentMap.get(activeId);
    if (activeParent) {
      const overParent = childParentMap.get(overId);
      if (overParent && overParent !== activeParent) {
        setForbiddenGroupId(overParent);
      } else {
        setForbiddenGroupId(null);
      }
    }
  }, [childParentMap]);

  const handleDragEnd = useCallback(async ({ active, over }: DragEndEvent) => {
    setActiveDragId(null);
    setForbiddenGroupId(null);
    if (!over || active.id === over.id) return;

    const activeId = String(active.id);
    const overId = String(over.id);
    const activeParent = childParentMap.get(activeId);
    const overParent = childParentMap.get(overId);

    // Block cross-parent drops for children
    if (activeParent && overParent && activeParent !== overParent) {
      message.warning('子菜单不能拖到其他父级下');
      return;
    }

    setIsReordering(true);
    const snapshot = JSON.parse(JSON.stringify(tree)) as MenuItem[];

    try {
      if (!activeParent) {
        // Top-level reorder (moves entire group with children)
        const oldIndex = tree.findIndex((m) => m.id === activeId);
        const newIndex = tree.findIndex((m) => m.id === overId);
        if (oldIndex < 0 || newIndex < 0) return;

        const newTree = arrayMove(tree, oldIndex, newIndex);
        queryClient.setQueryData(['menus'], newTree);

        const steps = newIndex - oldIndex;
        const direction = steps > 0 ? 1 : -1;
        for (let i = 0; i < Math.abs(steps); i++) {
          await midplatApi.moveMenu(activeId, direction);
        }
        await invalidate();
      } else {
        // Child reorder within same parent
        const group = tree.find((g) => g.id === activeParent);
        const children = group?.children ?? [];
        const oldIndex = children.findIndex((c) => c.id === activeId);
        const newIndex = children.findIndex((c) => c.id === overId);
        if (oldIndex < 0 || newIndex < 0) return;

        const newChildren = arrayMove(children, oldIndex, newIndex);
        const newTree = tree.map((g) =>
          g.id === activeParent ? { ...g, children: newChildren } : g,
        );
        queryClient.setQueryData(['menus'], newTree);

        const steps = newIndex - oldIndex;
        const direction = steps > 0 ? 1 : -1;
        for (let i = 0; i < Math.abs(steps); i++) {
          await midplatApi.moveMenu(activeId, direction);
        }
        await invalidate();
      }
    } catch (error: unknown) {
      queryClient.setQueryData(['menus'], snapshot);
      const msg = error instanceof Error ? error.message : '排序失败，已回滚';
      message.error(msg);
    } finally {
      setIsReordering(false);
    }
  }, [tree, childParentMap, queryClient, invalidate]);

  // ── Table columns ──
  const columns: TableProps<MenuItem>['columns'] = [
    {
      title: '',
      width: 48,
      fixed: 'left' as const,
      render: () => <DragHandleCell />,
    },
    {
      title: '菜单名称',
      dataIndex: 'name',
      render: (_: unknown, record: MenuItem) => {
        const hasChildren = !!(record.children && record.children.length > 0);
        const expanded = expandedKeys.includes(record.id);
        return (
          <div className="menu-table-name">
            {hasChildren ? (
              <Tooltip title={expanded ? '收起子菜单' : '展开子菜单'}>
                <button
                  type="button"
                  className={`menu-chevron ${expanded ? 'is-open' : ''}`}
                  aria-label={expanded ? '收起子菜单' : '展开子菜单'}
                  aria-expanded={expanded}
                  onClick={(e) => {
                    e.stopPropagation();
                    toggleExpand(record.id);
                  }}
                >
                  <ChevronRight size={16} />
                </button>
              </Tooltip>
            ) : record.parentId ? (
              <span className="menu-tree-branch" aria-hidden="true" />
            ) : (
              <span className="menu-chevron-placeholder" aria-hidden="true" />
            )}
            <div className="menu-table-name-line">
              <LucideIcon name={record.icon} size={record.parentId ? 14 : 16} />
              <span>{record.name}</span>
              {record.locked && (
                <Tooltip title="系统预置菜单：可改名称和图标，不能改路由，也不能删除。">
                  <span
                    className="menu-lock"
                    aria-label="系统预置菜单，已锁定"
                    onClick={(e) => e.stopPropagation()}
                  >
                    <Lock size={13} strokeWidth={2} />
                  </span>
                </Tooltip>
              )}
              {!record.visible && <span className="status status-muted">隐藏</span>}
            </div>
          </div>
        );
      },
    },
    {
      title: '路由标识',
      dataIndex: 'routeName',
      width: 160,
      render: (v: string) => {
        const title = ROUTE_CATALOG.find((r) => r.name === v)?.title;
        return <><span className="mono">{v}</span>{title ? <div className="menu-table-route">页面：{title}</div> : null}</>;
      },
    },
    {
      title: '路由 path',
      dataIndex: 'path',
      width: 160,
      render: (v: string) => v ? <span className="mono">{v}</span> : <span className="menu-table-empty">—</span>,
    },
    {
      title: '文件路径',
      dataIndex: 'filePath',
      width: 220,
      render: (v: string) => v ? <Tooltip title={v}><span className="mono"><FileCode size={12} style={{ marginRight: 4 }} />{v}</span></Tooltip> : <span className="menu-table-empty">—</span>,
    },
    {
      title: '绑定平台',
      width: 140,
      render: (_: unknown, record: MenuItem) => {
        const pIds = record.platformIds ?? (record.platformId ? [record.platformId] : []);
        if (!pIds.length) return <span className="menu-table-empty">—</span>;
        const platforms = platformsQuery.data ?? [];
        const names = pIds.map((id) => platforms.find((p: PlatformItem) => p.id === id)?.name ?? id);
        const badge = <span className="chip chip-emb">{`已绑 ${names.length} 个平台`}</span>;
        return <Tooltip title={names.join('、')}>{badge}</Tooltip>;
      },
    },
    {
      title: '操作',
      width: 120,
      fixed: 'right' as const,
      render: (_: unknown, record: MenuItem) => (
        <div className="menu-actions">
          {!record.parentId && record.id !== 'menu-home' ? (
            <Tooltip title="加子菜单">
              <Button size="small" type="text" icon={<Plus size={14} />} onClick={(e) => { e.stopPropagation(); openCreate(record.id); }} />
            </Tooltip>
          ) : null}
          <Tooltip title="编辑">
            <Button size="small" type="text" icon={<Edit size={14} />} onClick={(e) => { e.stopPropagation(); openEdit(record); }} />
          </Tooltip>
          {record.locked ? (
            <Tooltip title="系统菜单不可删除">
              <span className="chip chip-prompt">系统</span>
            </Tooltip>
          ) : (
            <Popconfirm title="确定删除该菜单？" onConfirm={() => deleteMutation.mutate(record.id)}>
              <Tooltip title="删除">
                <Button size="small" type="text" danger icon={<Trash2 size={14} />} onClick={(e) => e.stopPropagation()} />
              </Tooltip>
            </Popconfirm>
          )}
        </div>
      ),
    },
  ];

  if (menusQuery.isLoading) {
    return (
      <div className="page-shell">
        <PageHeader
          title="菜单设置"
          description="页面来自文件中定义的路由标识。只有「AI 工作台」下的子菜单可以绑定平台；未绑定平台的菜单不会显示工作台入口。"
          actions={<Button type="primary" icon={<Plus size={16} />} disabled>新增顶级菜单</Button>}
        />
        <div className="card" style={{ padding: '48px 0', display: 'flex', justifyContent: 'center' }}>
          <Spin size="large" />
        </div>
      </div>
    );
  }

  return (
    <div className="page-shell">
      <PageHeader
        title="菜单设置"
        description="页面来自文件中定义的路由标识。只有「AI 工作台」下的子菜单可以绑定平台；未绑定平台的菜单不会显示工作台入口。"
        actions={<Button type="primary" icon={<Plus size={16} />} onClick={() => openCreate()}>新增顶级菜单</Button>}
      />
      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        onDragStart={handleDragStart}
        onDragMove={handleDragMove}
        onDragEnd={handleDragEnd}
      >
        <SortableContext items={allRowIds} strategy={verticalListSortingStrategy}>
          <div className="card menu-settings-card surface-table" style={{ position: 'relative' }}>
            {isReordering && (
              <div className="menu-reorder-overlay">
                <Spin size="large" tip="正在保存排序..." />
              </div>
            )}
            {tree.length === 0 ? (
              <div className="empty">暂无菜单，点右上角新增。</div>
            ) : (
              <Table<MenuItem>
                columns={columns}
                dataSource={tree}
                rowKey="id"
                pagination={false}
                size="middle"
                childrenColumnName="children"
                expandable={{
                  expandedRowKeys: expandedKeys,
                  showExpandColumn: false,
                  expandIcon: () => null,
                  rowExpandable: (record) => !!record.children?.length,
                }}
                components={{
                  body: {
                    row: (props: React.HTMLAttributes<HTMLTableRowElement> & { 'data-row-key': string }) => {
                      const rowKey = String(props['data-row-key'] ?? '');
                      const record = findMenu(tree, rowKey);
                      const hasChildren = !!(record?.children && record.children.length > 0);
                      return (
                        <SortableRow
                          {...props}
                          id={rowKey}
                          hasChildren={hasChildren}
                          isChild={!!record?.parentId}
                          onRowClick={() => hasChildren && toggleExpand(rowKey)}
                        />
                      );
                    },
                  },
                }}
              />
            )}
          </div>
        </SortableContext>

        <DragOverlay>
          {activeDragId ? (
            <div className="sortable-dragging-overlay">
              {(() => {
                const group = tree.find((g) => g.id === activeDragId);
                if (group) {
                  const routeTitle = ROUTE_CATALOG.find((r) => r.name === group.routeName)?.title ?? group.routeName;
                  const childNames = (group.children ?? []).map((c) => c.name);
                  return (
                    <div className="drag-preview">
                      <DragHandleCell />
                      <div className="drag-preview-accent" />
                      <LucideIcon name={group.icon} size={16} />
                      <span className="drag-preview-name">{group.name}</span>
                      <span className="drag-preview-route">{routeTitle}</span>
                      {childNames.length > 0 && (
                        <span style={{ fontSize: 11, color: 'var(--muted)', marginLeft: 6 }}>
                          · {childNames.length} 子菜单: {childNames.join(' / ')}
                        </span>
                      )}
                    </div>
                  );
                }
                // Check if it's a child
                for (const g of tree) {
                  const child = g.children?.find((c) => c.id === activeDragId);
                  if (child) {
                    const childRouteTitle = ROUTE_CATALOG.find((r) => r.name === child.routeName)?.title ?? child.routeName;
                    return (
                      <div className="drag-preview drag-preview-child">
                        <DragHandleCell />
                        <LucideIcon name={child.icon} size={16} />
                        <span className="drag-preview-name">{child.name}</span>
                        <span className="drag-preview-route">{childRouteTitle}</span>
                      </div>
                    );
                  }
                }
                return null;
              })()}
            </div>
          ) : null}
        </DragOverlay>
      </DndContext>
      <Modal
        title={editing ? '编辑菜单' : parentId ? '新增子菜单' : '新增顶级菜单'}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => form.validateFields().then((values) => saveMutation.mutate(values))}
        confirmLoading={saveMutation.isPending}
        okText={editing ? '保存修改' : '创建菜单'}
        cancelText="取消"
        destroyOnHidden
        width={680}
      >
        <Form form={form} layout="vertical" initialValues={{ visible: true }}>
          <section className="settings-section">
            <SettingsSectionHeader step={1} title="菜单展示" description="先确认使用者在导航中看到的名称、图标和可见状态。" />
            <div className="settings-form-grid">
              <Form.Item name="name" label="显示名称" rules={[{ required: true, message: '请填写菜单名称' }]}><Input placeholder="输入菜单显示名称" /></Form.Item>
              <Form.Item name="icon" label="图标"><IconPicker /></Form.Item>
            </div>
            <Form.Item name="visible" label="在导航中显示" valuePropName="checked"><Switch /></Form.Item>
          </section>
          {canBindPlatform ? (
            <section className="settings-section">
              <SettingsSectionHeader step={2} title="分类项目" description="决定该 AI 工作台分类中展示和管理哪些项目。" />
              <Form.Item name="platformIds" label="所属项目"
                help="新增项目请直接进入该 AI 工作台分类登记；这里用于调整已有项目的分类归属。"
              >
                <Select
                  mode="multiple"
                  maxTagCount={2}
                  allowClear
                  placeholder="选择 AI 工作台中的项目"
                  options={(platformsQuery.data ?? []).map((item) => {
                    const occupiedBy = occupiedPlatformMap.get(item.id);
                    return {
                      value: item.id,
                      label: item.name,
                      disabled: !!occupiedBy,
                      labelRender: () => (
                        <span>
                          {item.name}
                          {occupiedBy && (
                            <Tooltip title={`已被「${occupiedBy}」绑定`}>
                              <span style={{ color: 'var(--muted)', marginLeft: 4, fontSize: 12 }}>（已占用）</span>
                            </Tooltip>
                          )}
                        </span>
                      ),
                    };
                  })}
                />
              </Form.Item>
            </section>
          ) : null}
          <section className="settings-section">
            <SettingsSectionHeader step={canBindPlatform ? 3 : 2} title="访问路由" description="高级配置：确定点击菜单后打开的站内地址和对应页面文件。" />
            <Form.Item
              name="routeName"
              label="路由标识"
              rules={[{ required: true, message: '路由标识必填' }]}
              help={editing?.locked ? '系统菜单的路由不可修改' : '可从下拉选择标准路由标识，也可直接输入自定义标识，如 my-page'}
            >
              <AutoComplete
                disabled={Boolean(editing?.locked)}
                options={ROUTE_CATALOG.map((route) => ({
                  value: route.name,
                  label: `${route.title} (${route.name})`,
                }))}
                placeholder="选择或输入自定义路由标识"
                filterOption={(inputValue, option) =>
                  !!option?.label?.toString().toLowerCase().includes(inputValue.toLowerCase()) ||
                  !!option?.value?.toString().toLowerCase().includes(inputValue.toLowerCase())
                }
                allowClear
              />
            </Form.Item>
            <Form.Item
              name="path"
              label="路由 path"
              rules={[
                { required: true, message: '路由 path 必填' },
                { validator: (_, value) => {
                  if (value && !value.startsWith('/')) return Promise.reject(new Error('path 必须以 / 开头'));
                  return Promise.resolve();
                }},
              ]}
              help="访问地址，以 / 开头，如 /my-page"
            >
              <Input disabled={Boolean(editing?.locked)} placeholder="/my-page" />
            </Form.Item>
            <Form.Item
              name="filePath"
              label="文件路径 filePath"
              rules={[
                { required: true, message: '文件路径必填' },
                { validator: (_, value) => {
                  if (value && !/^src\/pages\/.+\.tsx?$/.test(value)) return Promise.reject(new Error('filePath 格式应为 src/pages/.../index.tsx'));
                  return Promise.resolve();
                }},
              ]}
              help="页面文件相对路径，需手动创建文件并在 .umirc.ts 中配置路由"
            >
              <Input disabled={Boolean(editing?.locked)} placeholder="src/pages/custom/my-page/index.tsx" />
            </Form.Item>
            <div className="settings-form-impact">
              <span><strong>修改影响：</strong>{editing?.locked ? '系统菜单的访问路由已锁定，只允许修改展示信息。' : <>自定义路由需同时创建页面文件并在 <code className="mono">.umirc.ts</code> 中配置，否则访问时会 404。</>}</span>
            </div>
          </section>
        </Form>
      </Modal>
    </div>
  );
}
