// MyBatis adapter for fixed internal SQL. HTTP input is bound only through #{p.*} values.

package edu.municipal.access;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SqlMapper {
    @SelectProvider(type = Statements.class, method = "sql")
    List<Map<String, Object>> select(@Param("sql") String sql, @Param("p") Map<String, Object> parameters);

    @UpdateProvider(type = Statements.class, method = "sql")
    int update(@Param("sql") String sql, @Param("p") Map<String, Object> parameters);

    @InsertProvider(type = Statements.class, method = "sql")
    @Options(useGeneratedKeys = true, keyProperty = "p.id")
    int insert(@Param("sql") String sql, @Param("p") Map<String, Object> parameters);

    class Statements {
        public static String sql(Map<String, Object> arguments) { return (String) arguments.get("sql"); }
    }
}
