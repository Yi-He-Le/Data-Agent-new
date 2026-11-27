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

    /** Without literal/expression metadata, retain quoting and whitespace conservatively. */
    @Override
    public String normalizeDefault(String rawDefault) {
        return rawDefault;
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
