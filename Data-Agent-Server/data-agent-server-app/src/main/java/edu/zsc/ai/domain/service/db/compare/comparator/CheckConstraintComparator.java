package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.model.ConstraintSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/** CHECK 约束比较：表达式在保留字符串字面量原样的前提下去空白后比较。 */
@Component
public class CheckConstraintComparator extends AbstractSchemaObjectComparator {

    @Override
    public DiffObjectType objectType() {
        return DiffObjectType.CHECK;
    }

    @Override
    public String metadataError(TableSnapshot table) {
        return table.constraintError();
    }

    @Override
    protected List<Item> entries(TableSnapshot table, CompareContext context) {
        return table.constraints().stream()
                .filter(constraint -> "CHECK".equals(constraintType(constraint.type())))
                .map(constraint -> checkItem(constraint, context))
                .toList();
    }

    private Item checkItem(ConstraintSnapshot constraint, CompareContext context) {
        String expression = normalizeExpression(constraint.checkExpression());
        String key = constraint.name() != null && !constraint.name().isBlank()
                ? context.rules().normalizeIdentifier(constraint.name())
                : expression;
        return item(key == null ? "CHECK" : key, attributes(
                "EXPRESSION", expression,
                "ENABLED", yesNo(constraint.enabled())
        ));
    }

    /**
     * 只压缩字符串字面量之外的空白：字面量内的空格、大小写属于语义，必须原样保留，
     * 否则 'a  b' 与 'a b' 会被误判为一致。
     */
    private static String normalizeExpression(String expression) {
        if (expression == null) return null;
        StringBuilder out = new StringBuilder(expression.length());
        boolean inLiteral = false;
        boolean pendingSpace = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\'') {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(c);
                inLiteral = !inLiteral;
                continue;
            }
            if (!inLiteral && Character.isWhitespace(c)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
            }
            out.append(c);
        }
        String value = out.toString().trim();
        return value.isEmpty() ? null : value;
    }
}
