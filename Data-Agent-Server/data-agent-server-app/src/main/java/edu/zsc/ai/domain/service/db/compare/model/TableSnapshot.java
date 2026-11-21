package edu.zsc.ai.domain.service.db.compare.model;

import java.util.List;

/**
 * 一张表的结构快照。
 * 列读取失败时 columnError 非空，该表整体标记为 READ_ERROR；
 * 索引、约束读取失败时各自记录错误，对应对象类型在比较结果中标记为 UNKNOWN，
 * 不能当成空集合参与比较，否则会把“读不到”误判成“不存在”。
 */
public record TableSnapshot(
        String name,
        String comment,
        List<ColumnSnapshot> columns,
        List<IndexSnapshot> indexes,
        List<ConstraintSnapshot> constraints,
        String columnError,
        String indexError,
        String constraintError,
        String auxiliaryError
) {

    public boolean columnsReadable() {
        return columnError == null || columnError.isBlank();
    }

    public boolean indexesReadable() {
        return indexError == null || indexError.isBlank();
    }

    public boolean constraintsReadable() {
        return constraintError == null || constraintError.isBlank();
    }
}
