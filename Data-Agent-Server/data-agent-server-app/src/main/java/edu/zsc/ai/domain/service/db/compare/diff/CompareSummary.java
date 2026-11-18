package edu.zsc.ai.domain.service.db.compare.diff;

/** 按表统计的六态数量；unknown 表示存在无法判定的对象，不等同于一致。 */
public record CompareSummary(int onlySource, int onlyTarget, int different, int same, int readError, int unknown) {
}
