package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.diff.ObjectDiff;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;

import java.util.List;

/** 一类对象比较策略；新增对象类型只需实现本接口，注册表自动收集。 */
public interface SchemaObjectComparator {

    DiffObjectType objectType();

    List<ObjectDiff> compare(TableSnapshot source, TableSnapshot target, CompareContext context);

    /**
     * 本类对象所依赖的元数据在本表中是否不可用。
     * 返回非空表示“未知”：不能把读不到当成空集合，否则会误判为缺失或一致。
     */
    default String metadataError(TableSnapshot table) {
        return null;
    }
}
