package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.exception.BusinessException;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.SchemaCompareRequest;
import edu.zsc.ai.domain.service.db.ConnectionAccessService;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.SchemaCompareService;
import edu.zsc.ai.domain.service.db.compare.comparator.CompareContext;
import edu.zsc.ai.domain.service.db.compare.comparator.SchemaComparatorRegistry;
import edu.zsc.ai.domain.service.db.compare.comparator.SchemaObjectComparator;
import edu.zsc.ai.domain.service.db.compare.dialect.DialectRules;
import edu.zsc.ai.domain.service.db.compare.dialect.DialectRulesRegistry;
import edu.zsc.ai.domain.service.db.compare.diff.CompareOptions;
import edu.zsc.ai.domain.service.db.compare.diff.CompareSummary;
import edu.zsc.ai.domain.service.db.compare.diff.DiffStatus;
import edu.zsc.ai.domain.service.db.compare.diff.ObjectDiff;
import edu.zsc.ai.domain.service.db.compare.diff.SchemaCompareResult;
import edu.zsc.ai.domain.service.db.compare.diff.TableDiff;
import edu.zsc.ai.domain.service.db.compare.model.SchemaSnapshot;
import edu.zsc.ai.domain.service.db.compare.model.TableSnapshot;
import edu.zsc.ai.domain.service.db.compare.snapshot.SchemaSnapshotReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** 结构比较门面：读快照、配对表、按注册表分发比较器、汇总状态。 */
@Service
@RequiredArgsConstructor
public class SchemaCompareServiceImpl implements SchemaCompareService {

    private final SchemaSnapshotReader snapshotReader;
    private final SchemaComparatorRegistry comparatorRegistry;
    private final DialectRulesRegistry dialectRulesRegistry;
    private final ConnectionService connectionService;
    private final ConnectionAccessService connectionAccessService;

    @Override
    public SchemaCompareResult compare(SchemaCompareRequest request) {
        // 结构比较属于工作台能力，COMMON 成员不能通过接口直接调用。
        connectionAccessService.assertWorkbenchApiAllowed();
        Long sourceConnectionId = request.getSourceConnectionId() != null
                ? request.getSourceConnectionId() : request.getConnectionId();
        Long targetConnectionId = request.getTargetConnectionId() != null
                ? request.getTargetConnectionId() : request.getConnectionId();
        if (sourceConnectionId == null || targetConnectionId == null) {
            throw new BusinessException("请选择源连接与目标连接");
        }

        DbContext source = toContext(sourceConnectionId, request.getSourceCatalog(), request.getSourceSchema());
        DbContext target = toContext(targetConnectionId, request.getTargetCatalog(), request.getTargetSchema());
        if (source.equals(target)) {
            throw new BusinessException("源与目标指向了同一个对象，请选择不同的库、模式或连接");
        }

        connectionService.openConnection(source);
        connectionService.openConnection(target);
        // 跨连接比较时两侧可能是不同类型（如 MySQL vs 达梦），此时退回通用归一化规则。
        String sourceDbType = ActiveConnectionRegistry.getOwnedConnection(source).dbType();
        String targetDbType = ActiveConnectionRegistry.getOwnedConnection(target).dbType();
        DialectRules rules = dialectRulesRegistry.resolve(
                Objects.equals(sourceDbType, targetDbType) ? sourceDbType : "generic");
        CompareContext context = new CompareContext(rules, request.isIgnoreColumnOrder(), request.isIgnoreComments());
        CompareOptions options = new CompareOptions(
                CompareOptions.normalizeNames(toSet(request.getIncludeTables())),
                CompareOptions.normalizeNames(toSet(request.getExcludeTables())),
                request.isIgnoreColumnOrder(),
                request.isIgnoreComments()
        );

        SchemaSnapshot sourceSnapshot = snapshotReader.read(source, options);
        SchemaSnapshot targetSnapshot = snapshotReader.read(target, options);

        List<String> warnings = new ArrayList<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        findNameCollisions(sourceSnapshot.tables(), rules, ambiguous);
        findNameCollisions(targetSnapshot.tables(), rules, ambiguous);
        for (String name : ambiguous) warnings.add("表名归一化冲突：" + name + "，该组按原始名称精确比较");
        Map<TableKey, TableSnapshot> sourceTables = indexByName(sourceSnapshot.tables(), rules, ambiguous);
        Map<TableKey, TableSnapshot> targetTables = indexByName(targetSnapshot.tables(), rules, ambiguous);

        Set<TableKey> tableKeys = new LinkedHashSet<>(sourceTables.keySet());
        tableKeys.addAll(targetTables.keySet());

        List<TableDiff> tableDiffs = new ArrayList<>();
        for (TableKey key : tableKeys) {
            TableSnapshot sourceTable = sourceTables.get(key);
            TableSnapshot targetTable = targetTables.get(key);
            if (targetTable == null) {
                tableDiffs.add(new TableDiff(sourceTable.name(), DiffStatus.ONLY_SOURCE, List.of(),
                        sourceTable.auxiliaryError()));
            } else if (sourceTable == null) {
                tableDiffs.add(new TableDiff(targetTable.name(), DiffStatus.ONLY_TARGET, List.of(),
                        targetTable.auxiliaryError()));
            } else {
                tableDiffs.add(compareTable(sourceTable, targetTable, context));
            }
        }

        return new SchemaCompareResult(summarize(tableDiffs), tableDiffs, warnings);
    }

    private TableDiff compareTable(TableSnapshot source, TableSnapshot target, CompareContext context) {
        if (!source.columnsReadable() || !target.columnsReadable()) {
            String error = Stream.of(
                            source.columnError() == null ? null : "source " + source.columnError(),
                            target.columnError() == null ? null : "target " + target.columnError())
                    .filter(Objects::nonNull)
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("元数据读取失败");
            return new TableDiff(source.name(), DiffStatus.READ_ERROR, List.of(), error);
        }

        List<ObjectDiff> objects = new ArrayList<>();
        for (SchemaObjectComparator comparator : comparatorRegistry.ordered()) {
            objects.addAll(comparator.compare(source, target, context));
        }

        boolean different = objects.stream().anyMatch(object -> object.status() == DiffStatus.ONLY_SOURCE
                || object.status() == DiffStatus.ONLY_TARGET
                || object.status() == DiffStatus.DIFFERENT);
        // 只有“无法判断”而不存在真实差异时，表状态为 UNKNOWN，不能显示为一致。
        boolean unknown = objects.stream().anyMatch(object -> object.status() == DiffStatus.UNKNOWN);
        DiffStatus status = different ? DiffStatus.DIFFERENT
                : unknown ? DiffStatus.UNKNOWN : DiffStatus.SAME;
        String message = Stream.of(source.auxiliaryError(), target.auxiliaryError())
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + "; " + right)
                .orElse(null);
        return new TableDiff(source.name(), status, objects, message);
    }

    private static CompareSummary summarize(List<TableDiff> tables) {
        int onlySource = 0;
        int onlyTarget = 0;
        int different = 0;
        int same = 0;
        int readError = 0;
        int unknown = 0;
        for (TableDiff table : tables) {
            switch (table.status()) {
                case ONLY_SOURCE -> onlySource++;
                case ONLY_TARGET -> onlyTarget++;
                case DIFFERENT -> different++;
                case SAME -> same++;
                case READ_ERROR -> readError++;
                case UNKNOWN -> unknown++;
            }
        }
        return new CompareSummary(onlySource, onlyTarget, different, same, readError, unknown);
    }

    private record TableKey(String normalized, String originalName) {}

    private static void findNameCollisions(List<TableSnapshot> tables, DialectRules rules, Set<String> ambiguous) {
        Set<String> seen = new HashSet<>();
        for (TableSnapshot table : tables) {
            String name = rules.normalizeIdentifier(table.name());
            if (!seen.add(name)) ambiguous.add(name);
        }
    }

    private static Map<TableKey, TableSnapshot> indexByName(List<TableSnapshot> tables, DialectRules rules,
                                                          Set<String> ambiguous) {
        Map<TableKey, TableSnapshot> map = new LinkedHashMap<>();
        for (TableSnapshot table : tables) {
            String normalized = rules.normalizeIdentifier(table.name());
            map.put(new TableKey(normalized, ambiguous.contains(normalized) ? table.name() : null), table);
        }
        return map;
    }

    private static DbContext toContext(Long connectionId, String catalog, String schema) {
        return new DbContext(connectionId, blankToNull(catalog), blankToNull(schema));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Set<String> toSet(List<String> values) {
        return values == null ? new HashSet<>() : new HashSet<>(values);
    }
}
