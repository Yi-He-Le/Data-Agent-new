package edu.zsc.ai.domain.service.db.compare.diff;

/** 单个对象或单张表的比较结果。 */
public enum DiffStatus {
    /** 仅源端存在。 */
    ONLY_SOURCE,
    /** 仅目标端存在。 */
    ONLY_TARGET,
    /** 两端都存在但定义不同。 */
    DIFFERENT,
    /** 两端定义一致。 */
    SAME,
    /** 元数据读取失败，无法判断。 */
    READ_ERROR,
    /** 该类对象元数据不可用（读取失败或驱动不支持），与“确实不存在”区分开。 */
    UNKNOWN
}
