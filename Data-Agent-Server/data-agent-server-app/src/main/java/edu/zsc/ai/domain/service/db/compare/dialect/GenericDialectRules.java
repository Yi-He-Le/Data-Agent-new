package edu.zsc.ai.domain.service.db.compare.dialect;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 通用规则：标识符忽略大小写，默认值保留原始语义。未注册方言时的兜底实现。
 */
@Component
public class GenericDialectRules implements DialectRules {

    @Override
    public String dbType() {
        return "generic";
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
}
