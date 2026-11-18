package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.model.ColumnSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.ConstraintSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.IndexSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/** 主键比较：列清单及顺序（按主键定义顺序，而非列在表中的物理顺序）+ 主键约束启用状态。 */
@Component
public class PrimaryKeyComparator extends AbstractSchemaObjectComparator {

    private static final String OBJECT_KEY = "PRIMARY KEY";

    @Override
    public DiffObjectType objectType() {
        return DiffObjectType.PRIMARY_KEY;
    }

    @Override
    public String metadataError(TableSnapshot table) {
        if (!table.columnsReadable()) {
            // 列读取失败时整表已按 READ_ERROR 处理，这里不再重复报未知。
            return null;
        }
        if (hasPrimaryKeyColumnFlag(table)) {
            return null;
        }
        // 只能依赖索引信息时，索引读不到就无法判断主键，不能当成“无主键”。
        return table.indexError();
    }

    @Override
    protected List<Item> entries(TableSnapshot table, CompareContext context) {
        // 优先用主键索引：复合主键 (b, a) 的列顺序只有索引能给出，列元数据只能给出物理顺序。
        List<String> columns = table.indexes().stream()
                .filter(IndexSnapshot::primaryKey)
                .findFirst()
                .map(IndexSnapshot::columns)
                .orElse(List.of());
        if (columns.isEmpty()) {
            columns = table.columns().stream()
                    .filter(ColumnSnapshot::primaryKeyPart)
                    .map(ColumnSnapshot::name)
                    .toList();
        }
        if (columns.isEmpty()) {
            return List.of();
        }
        List<String> normalized = columns.stream()
                .map(context.rules()::normalizeIdentifier)
                .toList();
        // 主键约束启用状态：仅当驱动以「约束」形式上报主键时才参与比较；
        // 只以索引/列标志暴露主键的库（如 MySQL）不比较 enabled，避免跨方言误报。
        ConstraintSnapshot pkConstraint = table.constraints().stream()
                .filter(constraint -> "PRIMARY_KEY".equals(constraintType(constraint.type())))
                .findFirst()
                .orElse(null);
        String enabled = pkConstraint == null ? null : yesNo(pkConstraint.enabled());
        return List.of(item(OBJECT_KEY, attributes(
                "COLUMNS", join(normalized),
                "ENABLED", enabled
        )));
    }

    private static boolean hasPrimaryKeyColumnFlag(TableSnapshot table) {
        return table.columns().stream().anyMatch(ColumnSnapshot::primaryKeyPart);
    }
}
