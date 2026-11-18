package edu.zsc.ai.domain.service.db.compare.dialect;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 达梦规则：字典中的未加引号标识符以大写存储，类型名也习惯大写返回。
 */
@Component
public class DmDialectRules implements DialectRules {

    @Override
    public String dbType() {
        return "dm";
    }

    @Override
    public String normalizeIdentifier(String name) {
        return name == null ? null : name.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public String normalizeTypeName(String typeName) {
        return typeName == null ? null : typeName.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /**
     * null 表示无默认值；字符串默认值返回去掉外层引号的原文，空串返回 ''，
     * 避免把“无默认值”和“默认值为空串”归一化成同一个值。
     */
    @Override
    public String normalizeDefault(String rawDefault) {
        if (rawDefault == null) return null;
        String value = rawDefault.trim();
        // 空串默认值是合法取值，只有 null 才表示"没有默认值"。
        if (value.isEmpty()) return "''";
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            String inner = value.substring(1, value.length() - 1);
            return inner.isEmpty() ? "''" : inner.trim();
        }
        return value;
    }

    @Override
    public String normalizeComment(String comment) {
        if (comment == null) return null;
        String value = comment.trim();
        return value.isEmpty() ? null : value;
    }

    /** 达梦为未命名的索引生成 INDEX+数字 的名字，主键/外键的支撑索引走这条路径。 */
    @Override
    public boolean isGeneratedIndexName(String name) {
        return name != null && name.trim().toUpperCase(Locale.ROOT).matches("INDEX\\d+");
    }
}
