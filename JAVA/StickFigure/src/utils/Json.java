package utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Minimal JSON reader: parses into nested LinkedHashMap<String,Object> / List<Object> /
// String / Double / Boolean / null, mirroring how e.g. Python's json.loads works. Only
// the parsing direction is implemented (no writer) since that's all the project needs
// so far, and there's no JSON library on this project's classpath yet.
public class Json {

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.parseValue();
        p.skipWhitespace();
        return value;
    }

    private static class Parser {

        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.pos = 0;
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    pos += 4; // true
                    return Boolean.TRUE;
                case 'f':
                    pos += 5; // false
                    return Boolean.FALSE;
                case 'n':
                    pos += 4; // null
                    return null;
                default:
                    return parseNumber();
            }
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                pos++; // ':'
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = s.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                // c == ',' -> continue with next member
            }
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = s.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                // c == ',' -> continue with next element
            }
        }

        String parseString() {
            pos++; // opening '"'
            StringBuilder sb = new StringBuilder();
            while (s.charAt(pos) != '"') {
                char c = s.charAt(pos++);
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char esc = s.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: sb.append(esc);
                }
            }
            pos++; // closing '"'
            return sb.toString();
        }

        Double parseNumber() {
            int start = pos;
            while (pos < s.length() && "-+.eE0123456789".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
