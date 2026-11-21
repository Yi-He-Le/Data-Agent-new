package edu.zsc.ai.plugin.mysql.manager;

import edu.zsc.ai.plugin.capability.ConstraintManager;
import edu.zsc.ai.plugin.capability.MysqlIdentifierEscaper;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.metadata.ConstraintMetadata;
import edu.zsc.ai.plugin.mysql.constant.MySqlTemplate;
import edu.zsc.ai.plugin.mysql.support.MysqlCapabilitySupport;
import edu.zsc.ai.plugin.mysql.util.MysqlIdentifierBuilder;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * MySQL 约束发现（最小版）。
 * <p>
 * 只返回外键与 CHECK：PRIMARY KEY / UNIQUE 已由索引比较覆盖，重复返回会让同一处差异被计两次。
 * MySQL 外键不能跨库，因此引用的库与本表库相同时记为“本地引用”（referencedSchema 为 null），
 * 避免比较两个不同库时每个外键都因为库名不同而误报差异。
 * CHECK 需要 MySQL 8.0.16+（5.7 接受语法但会忽略 CHECK）。
 */
public final class MysqlConstraintManager implements ConstraintManager {

    private static final String TYPE_FOREIGN_KEY = "FOREIGN KEY";
    private static final String TYPE_CHECK = "CHECK";

    private final MysqlCapabilitySupport support;

    public MysqlConstraintManager(MysqlCapabilitySupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public List<ConstraintMetadata> getConstraints(Connection connection, String catalog,
                                                   String schema, String tableName) {
        if (connection == null || StringUtils.isBlank(tableName)) {
            return List.of();
        }
        String database = support.resolveDatabase(catalog, schema);
        if (StringUtils.isBlank(database)) {
            return List.of();
        }
        List<ConstraintMetadata> constraints = new ArrayList<>(listForeignKeys(connection, database, tableName));
        constraints.addAll(listCheckConstraints(connection, database, tableName));
        return constraints;
    }

    private List<ConstraintMetadata> listForeignKeys(Connection connection, String database, String tableName) {
        Map<String, ConstraintBuilder> builders = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(MySqlTemplate.SQL_LIST_FOREIGN_KEYS)) {
            statement.setString(1, database);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString("CONSTRAINT_NAME");
                    if (StringUtils.isBlank(name)) {
                        continue;
                    }
                    String ownerTable = resultSet.getString("TABLE_NAME");
                    String referencedSchema = resultSet.getString("REFERENCED_TABLE_SCHEMA");
                    String referencedTable = resultSet.getString("REFERENCED_TABLE_NAME");
                    if (StringUtils.equalsIgnoreCase(referencedSchema, database)) {
                        referencedSchema = null;
                    }
                    final String localReferencedSchema = referencedSchema;
                    ConstraintBuilder builder = builders.computeIfAbsent(name,
                            key -> new ConstraintBuilder(key, ownerTable, TYPE_FOREIGN_KEY,
                                    localReferencedSchema, referencedTable));
                    String column = resultSet.getString("COLUMN_NAME");
                    if (StringUtils.isNotBlank(column)) {
                        builder.columns.add(column);
                    }
                    String referencedColumn = resultSet.getString("REFERENCED_COLUMN_NAME");
                    if (StringUtils.isNotBlank(referencedColumn)) {
                        builder.referencedColumns.add(referencedColumn);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list foreign keys for " + tableName + ": " + e.getMessage(), e);
        }
        return builders.values().stream().map(ConstraintBuilder::build).toList();
    }

    private List<ConstraintMetadata> listCheckConstraints(Connection connection, String database, String tableName) {
        if (!supportsCheckConstraints(connection)) {
            return List.of();
        }
        List<ConstraintMetadata> constraints = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(MySqlTemplate.SQL_LIST_CHECK_CONSTRAINTS)) {
            statement.setString(1, database);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString("CONSTRAINT_NAME");
                    if (StringUtils.isBlank(name)) {
                        continue;
                    }
                    constraints.add(new ConstraintMetadata(
                            name,
                            resultSet.getString("TABLE_NAME"),
                            TYPE_CHECK,
                            List.of(),
                            null,
                            null,
                            List.of(),
                            resultSet.getString("CHECK_CLAUSE"),
                            "YES".equalsIgnoreCase(resultSet.getString("ENFORCED"))
                    ));
                }
            }
        } catch (SQLException e) {
            // 8.0.16 之前没有 CHECK_CONSTRAINTS 表，此时确实不存在可比较的 CHECK 约束；
            // 其他错误（如权限不足）必须上报，否则会把“读不到”误判成“没有约束”。
            if (isMissingCheckConstraintsTable(e)) {
                return List.of();
            }
            throw new RuntimeException("Failed to list check constraints for " + tableName + ": " + e.getMessage(), e);
        }
        return constraints;
    }

    private static boolean isMissingCheckConstraintsTable(SQLException e) {
        return "42S02".equals(e.getSQLState());
    }

    private static boolean supportsCheckConstraints(Connection connection) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            return metaData.getDatabaseMajorVersion() >= 8;
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public String getConstraintDdl(Connection connection, String catalog, String schema, String constraintName) {
        if (connection == null || StringUtils.isBlank(constraintName)) {
            return "";
        }
        String database = support.resolveDatabase(catalog, schema);
        if (StringUtils.isBlank(database)) {
            return "";
        }
        String tableName = findTableByConstraint(connection, database, constraintName);
        if (StringUtils.isBlank(tableName)) {
            return "";
        }
        ConstraintMetadata constraint = getConstraints(connection, database, null, tableName).stream()
                .filter(item -> constraintName.equalsIgnoreCase(item.name()))
                .findFirst()
                .orElse(null);
        if (constraint == null) {
            return "";
        }
        return buildAddConstraint(database, tableName, constraint);
    }

    @Override
    public void deleteConstraint(Connection connection, String catalog, String schema,
                                 String tableName, String constraintName) {
        if (connection == null || StringUtils.isBlank(tableName) || StringUtils.isBlank(constraintName)) {
            throw new IllegalArgumentException("Connection, table name and constraint name are required");
        }
        String database = support.resolveDatabase(catalog, schema);
        String type = findConstraintType(connection, database, tableName, constraintName);
        if (type == null) {
            throw new IllegalArgumentException("Constraint not found: " + constraintName);
        }
        String fullTable = MysqlIdentifierBuilder.buildFullIdentifier(database, tableName);
        String quotedName = MysqlIdentifierEscaper.getInstance().quoteIdentifier(constraintName);
        String sql = switch (type) {
            case "PRIMARY KEY" -> String.format("ALTER TABLE %s DROP PRIMARY KEY", fullTable);
            case "UNIQUE" -> String.format("ALTER TABLE %s DROP INDEX %s", fullTable, quotedName);
            case "CHECK" -> String.format("ALTER TABLE %s DROP CHECK %s", fullTable, quotedName);
            default -> String.format("ALTER TABLE %s DROP FOREIGN KEY %s", fullTable, quotedName);
        };
        SqlCommandResult result = support.execute(connection, database, sql);
        if (!result.isSuccess()) {
            throw new RuntimeException("Failed to delete constraint: " + result.getErrorMessage());
        }
    }

    private String findTableByConstraint(Connection connection, String database, String constraintName) {
        try (PreparedStatement statement = connection.prepareStatement(MySqlTemplate.SQL_FIND_CONSTRAINT_TABLE)) {
            statement.setString(1, database);
            statement.setString(2, constraintName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find constraint " + constraintName + ": " + e.getMessage(), e);
        }
    }

    private String findConstraintType(Connection connection, String database, String tableName, String constraintName) {
        try (PreparedStatement statement = connection.prepareStatement(MySqlTemplate.SQL_GET_CONSTRAINT_TYPE)) {
            statement.setString(1, database);
            statement.setString(2, tableName);
            statement.setString(3, constraintName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read constraint type: " + e.getMessage(), e);
        }
    }

    private static String buildAddConstraint(String database, String tableName, ConstraintMetadata constraint) {
        MysqlIdentifierEscaper escaper = MysqlIdentifierEscaper.getInstance();
        String fullTable = MysqlIdentifierBuilder.buildFullIdentifier(database, tableName);
        String quotedName = escaper.quoteIdentifier(constraint.name());
        String body = switch (constraint.type()) {
            case TYPE_FOREIGN_KEY -> String.format("FOREIGN KEY (%s) REFERENCES %s (%s)",
                    quoteList(constraint.columns()),
                    MysqlIdentifierBuilder.buildFullIdentifier(constraint.referencedSchema(), constraint.referencedTable()),
                    quoteList(constraint.referencedColumns()));
            case TYPE_CHECK -> "CHECK (" + constraint.checkExpression() + ")" + (constraint.enabled() ? "" : " NOT ENFORCED");
            default -> throw new IllegalArgumentException("Unsupported MySQL constraint type: " + constraint.type());
        };
        return String.format("ALTER TABLE %s ADD CONSTRAINT %s %s", fullTable, quotedName, body);
    }

    private static String quoteList(List<String> columns) {
        MysqlIdentifierEscaper escaper = MysqlIdentifierEscaper.getInstance();
        return columns.stream().map(escaper::quoteIdentifier).collect(Collectors.joining(", "));
    }

    private static final class ConstraintBuilder {
        private final String name;
        private final String tableName;
        private final String type;
        private final String referencedSchema;
        private final String referencedTable;
        private final List<String> columns = new ArrayList<>();
        private final List<String> referencedColumns = new ArrayList<>();

        private ConstraintBuilder(String name, String tableName, String type,
                                  String referencedSchema, String referencedTable) {
            this.name = name;
            this.tableName = tableName;
            this.type = type;
            this.referencedSchema = referencedSchema;
            this.referencedTable = referencedTable;
        }

        private ConstraintMetadata build() {
            return new ConstraintMetadata(name, tableName, type, List.copyOf(columns),
                    referencedSchema, referencedTable, List.copyOf(referencedColumns), null, true);
        }
    }
}
