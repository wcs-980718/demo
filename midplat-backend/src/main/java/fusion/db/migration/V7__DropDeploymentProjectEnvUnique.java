package fusion.db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * W4：解除 fusion_deployment 的 UNIQUE(project_id, environment)，允许同一项目环境多份分配部署。
 * 该约束在 V1 中未显式命名：PostgreSQL 默认名为 fusion_deployment_project_id_environment_key
 * （V6 已按名删除），H2 生成 CONSTRAINT_xx 不可预测，因此这里从 information_schema 动态定位并删除。
 */
public class V7__DropDeploymentProjectEnvUnique extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            String found = null;
            try (ResultSet constraints = statement.executeQuery(
                "SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k ON k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME AND k.TABLE_NAME = tc.TABLE_NAME " +
                "WHERE LOWER(tc.TABLE_NAME) = 'fusion_deployment' AND tc.CONSTRAINT_TYPE = 'UNIQUE' AND LOWER(k.COLUMN_NAME) = 'project_id'")) {
                while (constraints.next()) found = constraints.getString(1);
            }
            if (found != null && !found.isBlank()) {
                statement.execute("ALTER TABLE fusion_deployment DROP CONSTRAINT IF EXISTS \"" + found + "\"");
            }
        }
    }
}
