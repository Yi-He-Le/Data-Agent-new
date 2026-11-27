package edu.zsc.ai.domain.service.db.compare.comparator;

import edu.zsc.ai.domain.service.db.compare.diff.AttributeDiff;
import edu.zsc.ai.domain.service.db.compare.diff.DiffObjectType;
import edu.zsc.ai.domain.service.db.compare.diff.DiffStatus;
import edu.zsc.ai.domain.service.db.compare.diff.ObjectDiff;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 模板方法基类：固定「取条目 → 配对 → 单边判定 → 属性比对 → 状态汇总」的流程，
 * 子类只负责把快照转换成条目（归一化 key + 归一化属性表）。
 */
public abstract class AbstractSchemaObjectComparator implements SchemaObjectComparator {

    /** 一个待比较对象：归一化 key + 归一化属性表。 */
    protected record Item(String key, Map<String, String> attributes, String originalName) {
    }

    /** 元数据不可用时的占位对象键。 */
    private static final String UNKNOWN_KEY = "(metadata unavailable)";

    @Override
    public List<ObjectDiff> compare(TableSnapshot source, TableSnapshot target, CompareContext context) {
        String sourceError = metadataError(source);
        String targetError = metadataError(target);
        if (sourceError != null || targetError != null) {
            return List.of(new ObjectDiff(objectType(), UNKNOWN_KEY, DiffStatus.UNKNOWN,
                    List.of(new AttributeDiff("METADATA", sourceError, targetError))));
        }
        return compareItems(entries(source, context), entries(target, context), objectType());
    }

    /** 子类从表快照中提取本类对象。 */
    protected abstract List<Item> entries(TableSnapshot table, CompareContext context);

    protected final List<ObjectDiff> compareItems(List<Item> sourceItems, List<Item> targetItems,
                                          DiffObjectType objectType) {
        Set<String> ambiguous = new LinkedHashSet<>();
        findCollisions(sourceItems, ambiguous);
        findCollisions(targetItems, ambiguous);
        Map<MatchKey, Item> sourceByKey = index(sourceItems, ambiguous);
        Map<MatchKey, Item> targetByKey = index(targetItems, ambiguous);
        Set<MatchKey> keys = new LinkedHashSet<>(sourceByKey.keySet());
        keys.addAll(targetByKey.keySet());

        List<ObjectDiff> result = new ArrayList<>();
        for (MatchKey match : keys) {
            Item source = sourceByKey.get(match);
            Item target = targetByKey.get(match);
            String key = match.originalName() == null ? match.normalized() : match.originalName();
            if (source == null) {
                result.add(new ObjectDiff(objectType, key, DiffStatus.ONLY_TARGET, List.of()));
                continue;
            }
            if (target == null) {
                result.add(new ObjectDiff(objectType, key, DiffStatus.ONLY_SOURCE, List.of()));
                continue;
            }
            List<AttributeDiff> differences = attributeDifferences(source.attributes(), target.attributes());
            result.add(new ObjectDiff(objectType, key,
                    differences.isEmpty() ? DiffStatus.SAME : DiffStatus.DIFFERENT, differences));
        }
        return result;
    }

    protected static Item item(String key, Map<String, String> attributes) {
        return item(key, attributes, key);
    }

    protected static Item item(String key, Map<String, String> attributes, String originalName) {
        return new Item(key, attributes, originalName);
    }

    /** 按传入顺序构建属性表，value 为 null 表示该属性不存在。 */
    protected static Map<String, String> attributes(String... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Attribute name/value pairs must be even");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            map.put(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return map;
    }

    protected static String yesNo(boolean value) {
        return value ? "YES" : "NO";
    }

    protected static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }

    /** 把各类数据库返回的约束类型统一成 FOREIGN_KEY / UNIQUE / CHECK / PRIMARY_KEY。 */
    protected static String constraintType(String rawType) {
        if (rawType == null) return "";
        String type = rawType.trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "R", "FOREIGN", "FOREIGN KEY" -> "FOREIGN_KEY";
            case "U", "UNIQUE", "UNIQUE KEY" -> "UNIQUE";
            case "C", "CHECK" -> "CHECK";
            case "P", "PRIMARY", "PRIMARY KEY" -> "PRIMARY_KEY";
            default -> type;
        };
    }

    private record MatchKey(String normalized, String originalName) {}

    private static void findCollisions(List<Item> items, Set<String> ambiguous) {
        Set<String> seen = new LinkedHashSet<>();
        for (Item item : items) if (!seen.add(item.key())) ambiguous.add(item.key());
    }

    private static Map<MatchKey, Item> index(List<Item> items, Set<String> ambiguous) {
        Map<MatchKey, Item> map = new LinkedHashMap<>();
        for (Item item : items) {
            var key = new MatchKey(item.key(), ambiguous.contains(item.key()) ? item.originalName() : null);
            map.putIfAbsent(key, item);
        }
        return map;
    }

    private static List<AttributeDiff> attributeDifferences(Map<String, String> source, Map<String, String> target) {
        Set<String> names = new LinkedHashSet<>(source.keySet());
        names.addAll(target.keySet());
        List<AttributeDiff> diffs = new ArrayList<>();
        for (String name : names) {
            String sourceValue = source.get(name);
            String targetValue = target.get(name);
            if (!Objects.equals(sourceValue, targetValue)) {
                diffs.add(new AttributeDiff(name, sourceValue, targetValue));
            }
        }
        return diffs;
    }
}
