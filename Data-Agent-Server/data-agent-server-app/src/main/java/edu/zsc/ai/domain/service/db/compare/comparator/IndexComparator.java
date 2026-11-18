package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.model.IndexSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 索引比较：唯一性、索引类型、列清单及顺序；主键索引由主键比较器负责，此处跳过。 */
@Component
public class IndexComparator extends AbstractSchemaObjectComparator {

    @Override
    public DiffObjectType objectType() {
        return DiffObjectType.INDEX;
    }

    @Override
    public String metadataError(TableSnapshot table) {
        return table.indexError();
    }

    @Override
    protected List<Item> entries(TableSnapshot table, CompareContext context) {
        List<Item> items = new ArrayList<>();
        for (IndexSnapshot index : table.indexes()) {
            if (index.primaryKey()) {
                continue;
            }
            List<String> columns = index.columns().stream()
                    .map(context.rules()::normalizeIdentifier)
                    .toList();
            String type = index.type() == null ? null : index.type().trim().toUpperCase(Locale.ROOT);
            // 自动生成的索引名两端必然不同，改用结构作为键，避免整表误报差异。
            String key = context.rules().isGeneratedIndexName(index.name())
                    ? "SYS_INDEX(" + yesNo(index.unique()) + "," + type + "," + join(columns) + ")"
                    : context.rules().normalizeIdentifier(index.name());
            items.add(item(
                    key,
                    attributes(
                            "UNIQUE", yesNo(index.unique()),
                            "TYPE", type,
                            "COLUMNS", join(columns)
                    )
            ));
        }
        return items;
    }
}
