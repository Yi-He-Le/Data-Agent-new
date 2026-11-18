package edu.zsc.ai.domain.service.db.compare.dialect;

/**
 * 方言归一化规则：比较器只面对统一文本，不判断数据库类型。
 * 新增数据库时实现本接口并交给 {@link DialectRulesRegistry} 即可。
 */
public interface DialectRules {

    /** 数据库类型键，如 mysql、dm。 */
    String dbType();

    /** 标识符归一化；同一对象在两端应得到相同结果。 */
    String normalizeIdentifier(String name);

    /** 类型名归一化（不含长度）。 */
    String normalizeTypeName(String typeName);

    /** 默认值归一化；null 表示“无默认值”。 */
    String normalizeDefault(String rawDefault);

    /** 注释归一化；空白视为无注释。 */
    String normalizeComment(String comment);

    /**
     * 是否为数据库自动生成的索引名（如达梦的 INDEX33555526）。
     * 这类名字随对象创建顺序变化，两端必然不同，比较时应按索引结构匹配。
     */
    default boolean isGeneratedIndexName(String name) {
        return false;
    }
}
