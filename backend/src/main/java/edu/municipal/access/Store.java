// Small persistence functions shared by functional processes; maps become camelCase HTTP data.

package edu.municipal.access;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Store {
    private final SqlMapper mapper;
    public Store(SqlMapper mapper) { this.mapper = mapper; }

    public static Map<String, Object> parameters(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) values.put((String) pairs[index], pairs[index + 1]);
        return values;
    }
    public List<Map<String, Object>> list(String sql, Object... pairs) {
        return mapper.select(sql, parameters(pairs)).stream().map(this::normalize).toList();
    }
    public Map<String, Object> one(String sql, Object... pairs) {
        List<Map<String, Object>> rows = list(sql, pairs);
        if (rows.isEmpty()) throw Problem.missing();
        return rows.getFirst();
    }
    public int execute(String sql, Object... pairs) { return mapper.update(sql, parameters(pairs)); }
    public long insert(String sql, Map<String, Object> values) {
        mapper.insert(sql, values);
        return ((Number) values.get("id")).longValue();
    }
    public long number(String sql, Object... pairs) {
        return ((Number) one(sql, pairs).values().iterator().next()).longValue();
    }
    private Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            StringBuilder name = new StringBuilder();
            boolean uppercase = false;
            for (char character : key.toCharArray()) {
                if (character == '_') uppercase = true;
                else { name.append(uppercase ? Character.toUpperCase(character) : character); uppercase = false; }
            }
            Object output = value instanceof Timestamp timestamp ? timestamp.toInstant() : value;
            if (Set.of("enabled", "locked", "synthetic").contains(name.toString()) && value instanceof Number number) output = number.intValue() != 0;
            normalized.put(name.toString(), output);
        });
        return normalized;
    }
}
