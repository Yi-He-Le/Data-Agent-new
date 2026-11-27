package edu.zsc.ai.plugin.mysql.manager;
import edu.zsc.ai.plugin.mysql.support.MysqlCapabilitySupport;
import edu.zsc.ai.plugin.mysql.constant.MySqlTemplate;
import java.sql.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class MysqlConstraintManagerTest {
    @ParameterizedTest @CsvSource({"YES,true","NO,false"})
    void preservesCheckEnforcement(String enforcement, boolean enabled) throws Exception {
        var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);when(metadata.getDatabaseMajorVersion()).thenReturn(8);
        var support=mock(MysqlCapabilitySupport.class);when(support.resolveDatabase("db",null)).thenReturn("db");
        var fk=mock(PreparedStatement.class);var fkRows=mock(ResultSet.class);
        when(connection.prepareStatement(MySqlTemplate.SQL_LIST_FOREIGN_KEYS)).thenReturn(fk);when(fk.executeQuery()).thenReturn(fkRows);
        var checks=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(connection.prepareStatement(MySqlTemplate.SQL_LIST_CHECK_CONSTRAINTS)).thenReturn(checks);when(checks.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true,false,true,false);when(rows.getString("CONSTRAINT_NAME")).thenReturn("ck_age");
        when(rows.getString("TABLE_NAME")).thenReturn("t");when(rows.getString("CHECK_CLAUSE")).thenReturn("age > 0");
        when(rows.getString("ENFORCED")).thenReturn(enforcement);
        var result=new MysqlConstraintManager(support).getConstraints(connection,"db",null,"t");
        assertEquals(1,result.size());assertEquals(enabled,result.get(0).enabled());
        var lookup=mock(PreparedStatement.class);var found=mock(ResultSet.class);
        when(connection.prepareStatement(MySqlTemplate.SQL_FIND_CONSTRAINT_TABLE)).thenReturn(lookup);
        when(lookup.executeQuery()).thenReturn(found);when(found.next()).thenReturn(true);when(found.getString(1)).thenReturn("t");
        String ddl=new MysqlConstraintManager(support).getConstraintDdl(connection,"db",null,"ck_age");
        assertEquals(!enabled,ddl.contains("NOT ENFORCED"));
    }
}
