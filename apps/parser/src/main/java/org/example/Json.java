package org.example;

import java.util.List;
import java.util.Map;

/** Minimal JSON serializer for Map / List / String / Number / Boolean / null trees. */
final class Json {
    private Json() {
    }

    static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int depth) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> quote(sb, s);
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            case Map<?, ?> map -> {
                if (map.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                int i = 0;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    indent(sb, depth + 1);
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(": ");
                    write(sb, e.getValue(), depth + 1);
                    sb.append(++i < map.size() ? ",\n" : "\n");
                }
                indent(sb, depth);
                sb.append('}');
            }
            case List<?> list -> {
                if (list.isEmpty()) {
                    sb.append("[]");
                    return;
                }
                sb.append("[\n");
                for (int i = 0; i < list.size(); i++) {
                    indent(sb, depth + 1);
                    write(sb, list.get(i), depth + 1);
                    sb.append(i + 1 < list.size() ? ",\n" : "\n");
                }
                indent(sb, depth);
                sb.append(']');
            }
            default -> quote(sb, value.toString());
        }
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
