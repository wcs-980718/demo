-- W4 修复：分配幂等键恢复旧表 operation_id UNIQUE 语义（V36 回填时丢失）。
-- 幂等重放按 (operation_id, project_id, environment) 查询；跨项目复用同一键在应用层查不到重放记录后
-- 插入时由该唯一约束兜底为冲突，而不是误读其他项目的分配。
CREATE UNIQUE INDEX midplat_project_agent_binding_operation ON midplat_project_agent_binding(operation_id);
