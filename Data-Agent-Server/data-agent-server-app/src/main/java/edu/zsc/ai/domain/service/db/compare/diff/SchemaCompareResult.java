package edu.zsc.ai.domain.service.db.compare.diff;

import java.util.List;

/** 一次结构比较的完整结果；warnings 记录不影响结论、但需要提示的比较过程问题。 */
public record SchemaCompareResult(CompareSummary summary, List<TableDiff> tables, List<String> warnings) {
}
