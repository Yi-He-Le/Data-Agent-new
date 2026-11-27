package edu.zsc.ai.domain.service.db.compare;

import edu.zsc.ai.domain.service.db.compare.comparator.*;
import edu.zsc.ai.domain.service.db.compare.dialect.*;
import edu.zsc.ai.domain.service.db.compare.diff.*;
import edu.zsc.ai.domain.service.db.compare.model.*;
import edu.zsc.ai.domain.service.db.compare.snapshot.SchemaSnapshotReader;
import edu.zsc.ai.domain.service.db.impl.SchemaCompareServiceImpl;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.SchemaCompareRequest;
import edu.zsc.ai.config.web.WorkbenchOrgCommonWebMvcConfigurer;
import edu.zsc.ai.context.*;
import edu.zsc.ai.common.enums.org.*;
import edu.zsc.ai.domain.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Regression coverage for semantic equality, incomplete metadata and workbench access. */
class SchemaCompareRegressionTest {
    private final CompareContext context = new CompareContext(new GenericDialectRules(), false, false);
    @AfterEach void clear() { RequestContext.clear(); }

    @Test void emptyStringDefaultMustDifferFromNoDefault() {
        var source = table("t", List.of(column("code",1,"''",false)), List.of(), List.of(), null);
        var target = table("t", List.of(column("code",1,null,false)), List.of(), List.of(), null);
        assertEquals(DiffStatus.DIFFERENT, new ColumnComparator().compare(source, target, context).get(0).status());
    }

    @Test void quotedWhitespaceInCheckMustRemainSignificant() {
        var a = new ConstraintSnapshot("ck_code", "CHECK", List.of("code"), null, null, List.of(), "code = 'a  b'", true);
        var b = new ConstraintSnapshot("ck_code", "CHECK", List.of("code"), null, null, List.of(), "code = 'a b'", true);
        assertEquals(DiffStatus.DIFFERENT, new CheckConstraintComparator().compare(
                table("t", List.of(), List.of(), List.of(a), null), table("t", List.of(), List.of(), List.of(b), null), context).get(0).status());
    }

    @Test void compositePrimaryKeyMustUseDeclaredKeyOrder() {
        var cols = List.of(column("a",1,null,true), column("b",2,null,true));
        var a = table("t",cols,List.of(new IndexSnapshot("PRIMARY","BTREE",List.of("a","b"),true,true)),List.of(),null);
        var b = table("t",cols,List.of(new IndexSnapshot("PRIMARY","BTREE",List.of("b","a"),true,true)),List.of(),null);
        assertEquals(DiffStatus.DIFFERENT, new PrimaryKeyComparator().compare(a,b,context).get(0).status());
    }

    @Test void foreignKeyMustCompareTheReferencedNamespace() {
        var a = new ConstraintSnapshot("fk_user", "FOREIGN_KEY", List.of("user_id"), "auth", "users", List.of("id"), null, true);
        var b = new ConstraintSnapshot("fk_user", "FOREIGN_KEY", List.of("user_id"), "archive", "users", List.of("id"), null, true);
        assertEquals(DiffStatus.DIFFERENT, new ForeignKeyComparator().compare(
                table("t",List.of(),List.of(),List.of(a),null), table("t",List.of(),List.of(),List.of(b),null),context).get(0).status());
    }

    @Test void disabledConstraintMustDifferFromEnabledConstraint() {
        var a = new ConstraintSnapshot("ck_age", "CHECK", List.of("age"), null, null, List.of(), "age > 0", true);
        var b = new ConstraintSnapshot("ck_age", "CHECK", List.of("age"), null, null, List.of(), "age > 0", false);
        assertEquals(DiffStatus.DIFFERENT, new CheckConstraintComparator().compare(
                table("t",List.of(),List.of(),List.of(a),null), table("t",List.of(),List.of(),List.of(b),null),context).get(0).status());
    }

    @Test void incompleteMetadataMustNotProduceSame() {
        var cols = List.of(column("id",1,null,false));
        var result = compare(List.of(table("t",cols,List.of(),List.of(),"indexes: permission denied")),
                List.of(table("t",cols,List.of(),List.of(),null)));
        assertEquals(DiffStatus.UNKNOWN, result.tables().get(0).status());
    }

    @Test void distinctCaseSensitiveTablesMustNotBeSilentlyDropped() {
        var cols = List.of(column("id",1,null,false));
        var result = compare(List.of(table("player",cols,List.of(),List.of(),null),table("PLAYER",cols,List.of(),List.of(),null)),
                List.of(table("player",cols,List.of(),List.of(),null)));
        assertEquals(2, result.tables().size());
        assertEquals(1, result.summary().onlySource());
    }

    @Test void orgCommonMustNotBypassWorkbenchRestrictionThroughSchemaCompare() throws Exception {
        RequestContext.set(RequestContextInfo.builder().userId(42L).workspaceType(WorkspaceTypeEnum.ORGANIZATION)
                .orgId(1L).orgRole(OrganizationRoleEnum.COMMON).build());
        var registry = new InterceptorRegistry();
        new WorkbenchOrgCommonWebMvcConfigurer().addInterceptors(registry);
        List<?> interceptors = ReflectionTestUtils.invokeMethod(registry, "getInterceptors");
        var gate = (HandlerInterceptor) interceptors.get(0);
        var ordinary = new MockHttpServletRequest("POST", "/api/columns"); ordinary.setServletPath("/api/columns");
        assertThrows(BusinessException.class, () -> gate.preHandle(ordinary, new MockHttpServletResponse(),new Object()));
        var compare = new MockHttpServletRequest("POST", "/api/schema/compare"); compare.setServletPath("/api/schema/compare");
        assertThrows(BusinessException.class, () -> gate.preHandle(compare, new MockHttpServletResponse(),new Object()));
    }


    @Test void defaultLiteralWhitespaceMustBePreserved() {
        for (var rules : List.of(new GenericDialectRules(), new DmDialectRules())) {
            assertNotEquals(rules.normalizeDefault("' x '"),rules.normalizeDefault("'x'"));
        }
    }
    @Test void defaultLiteralMustDifferFromExpression() {
        assertNotEquals(context.rules().normalizeDefault("'CURRENT_TIMESTAMP'"),
                context.rules().normalizeDefault("CURRENT_TIMESTAMP"));
    }
    @Test void quotedCaseSensitiveColumnsMustNotDisappear() {
        var source=table("t",List.of(column("code",1,null,false),column("CODE",2,null,false)),List.of(),List.of(),null);
        var target=table("t",List.of(column("code",1,null,false)),List.of(),List.of(),null);
        var diff=new ColumnComparator().compare(source,target,context);
        assertEquals(2,diff.size());
    }
    @Test void identicalCaseSensitiveTablesMustNotDependOnListingOrder() {
        var lower=table("player",List.of(column("lower",1,null,false)),List.of(),List.of(),null);
        var upper=table("PLAYER",List.of(column("upper",1,null,false)),List.of(),List.of(),null);
        var result=compare(List.of(lower,upper),List.of(upper,lower));
        assertEquals(2,result.tables().size());
        assertEquals(2,result.summary().same());
    }
    @Test void unreadableCommentsMustNotProduceSame() {
        var tables=mock(edu.zsc.ai.domain.service.db.TableService.class);
        var columns=mock(edu.zsc.ai.domain.service.db.ColumnService.class);
        var indexes=mock(edu.zsc.ai.domain.service.db.IndexService.class);
        var constraints=mock(edu.zsc.ai.domain.service.db.ConstraintService.class);
        var source=new DbContext(1L,"source",null);var target=new DbContext(2L,"target",null);
        when(tables.getTables(any())).thenReturn(List.of("t"));
        when(tables.getTableCommentsStrict(source)).thenThrow(new RuntimeException("permission denied"));
        when(tables.getTableCommentsStrict(target)).thenReturn(java.util.Map.of());
        var reader=new SchemaSnapshotReader(tables,columns,indexes,constraints);
        var result=compare(reader.read(source,CompareOptions.defaults()).tables(),reader.read(target,CompareOptions.defaults()).tables());
        assertNotEquals(DiffStatus.SAME,result.tables().get(0).status());
    }
    @Test void equivalentPrimaryKeysMustNotDifferOnlyBecauseOneDriverReportsConstraintState() {
        var cols=List.of(column("id",1,null,true));
        var indexes=List.of(new IndexSnapshot("PRIMARY","BTREE",List.of("id"),true,true));
        var primary=new ConstraintSnapshot("pk_t","PRIMARY_KEY",List.of("id"),null,null,List.of(),null,true);
        var diff=new PrimaryKeyComparator().compare(table("t",cols,indexes,List.of(),null),table("t",cols,indexes,List.of(primary),null),context);
        assertNotEquals(DiffStatus.DIFFERENT,diff.get(0).status());
    }


    @Test void primaryKeyEnabledStateIsComparedWhenBothDriversProvideIt() {
        var cols=List.of(column("id",1,null,true));
        var indexes=List.of(new IndexSnapshot("PRIMARY","BTREE",List.of("id"),true,true));
        var enabled=new ConstraintSnapshot("pk_t","PRIMARY_KEY",List.of("id"),null,null,List.of(),null,true);
        var disabled=new ConstraintSnapshot("pk_t","PRIMARY_KEY",List.of("id"),null,null,List.of(),null,false);
        var diff=new PrimaryKeyComparator().compare(table("t",cols,indexes,List.of(enabled),null),table("t",cols,indexes,List.of(disabled),null),context);
        assertEquals(DiffStatus.DIFFERENT,diff.get(0).status());
        assertEquals("ENABLED",diff.get(0).attributes().get(0).name());
    }
    @Test void equalDefaultsStillCompareEqual() {
        var columns=List.of(column("code",1,"' x '",false));
        assertEquals(DiffStatus.SAME,new ColumnComparator().compare(table("t",columns,List.of(),List.of(),null),table("t",columns,List.of(),List.of(),null),context).get(0).status());
    }
    @Test void collidingColumnsArePairedByOriginalNameWhenOrderIsIgnored() {
        var lower=column("code",1,"'lower'",false);var upper=column("CODE",2,"'upper'",false);
        var diff=new ColumnComparator().compare(table("t",List.of(lower,upper),List.of(),List.of(),null),
                table("t",List.of(upper,lower),List.of(),List.of(),null),new CompareContext(context.rules(),true,false));
        assertEquals(2,diff.size());assertTrue(diff.stream().allMatch(item->item.status()==DiffStatus.SAME));
    }
    @Test void ignoredCommentsDoNotTriggerLookupOrUnknownStatus() {
        var tables=mock(edu.zsc.ai.domain.service.db.TableService.class);
        var reader=new SchemaSnapshotReader(tables,mock(edu.zsc.ai.domain.service.db.ColumnService.class),
                mock(edu.zsc.ai.domain.service.db.IndexService.class),mock(edu.zsc.ai.domain.service.db.ConstraintService.class));
        var db=new DbContext(1L,"source",null);when(tables.getTables(db)).thenReturn(List.of("t"));
        var snapshot=reader.read(db,new CompareOptions(java.util.Set.of(),java.util.Set.of(),false,true));
        verify(tables,never()).getTableCommentsStrict(any());
        assertNull(snapshot.tables().get(0).commentError());
        var broken=new TableSnapshot("t",null,List.of(),List.of(),List.of(),null,null,null,"comments unavailable","comments unavailable");
        assertTrue(new TableCommentComparator().compare(broken,broken,new CompareContext(context.rules(),false,true)).isEmpty());
    }

    @Test void comparisonUsesStrictCommentsWhileExplorerKeepsItsFallback() {
        var db=new DbContext(1L,"db",null);
        var active=mock(ActiveConnectionRegistry.ActiveConnection.class);
        var borrowed=mock(ActiveConnectionRegistry.BorrowedConnection.class);
        var jdbc=mock(java.sql.Connection.class);
        var plugins=mock(edu.zsc.ai.plugin.manager.DefaultPluginManager.class);
        var tables=mock(edu.zsc.ai.plugin.capability.TableManager.class);
        when(active.pluginId()).thenReturn("test");when(active.borrowConnection()).thenReturn(borrowed);
        when(borrowed.connection()).thenReturn(jdbc);when(plugins.getTableManagerByPluginId("test")).thenReturn(tables);
        when(tables.getTableComments(jdbc,"db",null)).thenThrow(new IllegalStateException("permission denied"));
        try (var connections=mockStatic(ActiveConnectionRegistry.class);
             var managers=mockStatic(edu.zsc.ai.plugin.manager.DefaultPluginManager.class)) {
            connections.when(()->ActiveConnectionRegistry.getOwnedConnection(db)).thenReturn(active);
            managers.when(edu.zsc.ai.plugin.manager.DefaultPluginManager::getInstance).thenReturn(plugins);
            var service=new edu.zsc.ai.domain.service.db.impl.TableServiceImpl(mock(ConnectionService.class),
                    mock(edu.zsc.ai.domain.service.db.transfer.TableDataTransferRegistry.class));
            assertThrows(IllegalStateException.class,()->service.getTableCommentsStrict(db));
            assertEquals(java.util.Map.of(),service.getTableComments(db));
            verify(borrowed,times(2)).close();
        }
    }

    private SchemaCompareResult compare(List<TableSnapshot> source, List<TableSnapshot> target) {
        var reader = mock(SchemaSnapshotReader.class);
        when(reader.read(any(), any())).thenReturn(new SchemaSnapshot("source",null,source),new SchemaSnapshot("target",null,target));
        var active = mock(ActiveConnectionRegistry.ActiveConnection.class); when(active.dbType()).thenReturn("mysql");
        try (var registry = mockStatic(ActiveConnectionRegistry.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(any(DbContext.class))).thenReturn(active);
            var service = new SchemaCompareServiceImpl(reader, new SchemaComparatorRegistry(List.of(
                    new ColumnComparator(), new PrimaryKeyComparator(), new IndexComparator(), new ForeignKeyComparator(),
                    new UniqueConstraintComparator(), new CheckConstraintComparator(), new TableCommentComparator())),
                    new DialectRulesRegistry(List.of(new GenericDialectRules(),new DmDialectRules())),mock(ConnectionService.class), mock(edu.zsc.ai.domain.service.db.ConnectionAccessService.class));
            return service.compare(SchemaCompareRequest.builder().sourceConnectionId(1L).targetConnectionId(2L)
                    .sourceCatalog("source").targetCatalog("target").build());
        }
    }
    private ColumnSnapshot column(String name,int position,String value,boolean pk) {
        return new ColumnSnapshot(name,position,"VARCHAR",20,0,true,value,null,false,pk);
    }
    private TableSnapshot table(String name,List<ColumnSnapshot> cols,List<IndexSnapshot> indexes,List<ConstraintSnapshot> constraints,String error) {
        return new TableSnapshot(name,null,cols,indexes,constraints,null,error,null,error);
    }
}
