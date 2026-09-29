package io.github.hronosin.miracle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for the flat subset of TOML that {@code miracle.mod.toml} uses: {@code key = "string"},
 * {@code key = ["a", "b"]}, and {@code #} comments. No tables, no numbers, no multiline strings —
 * the loader has zero dependencies and intends to keep it that way.
 */
public final class MiniToml {

    private MiniToml() {
    }

    public static final class ParseException extends Exception {
        ParseException(int line, String msg) {
            super("line " + line + ": " + msg);
        }
    }

    /** Values are {@code String} or {@code List<String>}. */
    public static Map<String, Object> parse(String text) throws ParseException {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                throw new ParseException(lineNo, "tables are not supported (yet), keep it flat");
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new ParseException(lineNo, "expected key = value");
            }
            String key = line.substring(0, eq).strip();
            if (!key.matches("[A-Za-z0-9_-]+")) {
                throw new ParseException(lineNo, "bad key '" + key + "'");
            }
            if (out.containsKey(key)) {
                throw new ParseException(lineNo, "duplicate key '" + key + "'");
            }
            Cursor c = new Cursor(line, eq + 1, lineNo);
            c.skipSpaces();
            Object value;
            if (c.peek() == '"') {
                value = c.readString();
            } else if (c.peek() == '[') {
                value = c.readArray();
            } else {
                throw new ParseException(lineNo, "only \"strings\" and [\"arrays\"] are supported");
            }
            c.skipSpaces();
            if (!c.atEnd() && c.peek() != '#') {
                throw new ParseException(lineNo, "unexpected text after value");
            }
            out.put(key, value);
        }
        return out;
    }

    private static final class Cursor {
        private final String s;
        private final int line;
        private int pos;

        Cursor(String s, int pos, int line) {
            this.s = s;
            this.pos = pos;
            this.line = line;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        char peek() {
            return atEnd() ? '\0' : s.charAt(pos);
        }

        void skipSpaces() {
            while (!atEnd() && (peek() == ' ' || peek() == '\t')) {
                pos++;
            }
        }

        String readString() throws ParseException {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (!atEnd()) {
                char ch = s.charAt(pos++);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch == '\\') {
                    if (atEnd()) {
                        break;
                    }
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        default -> throw new ParseException(line, "unknown escape \\" + esc);
                    }
                } else {
                    sb.append(ch);
                }
            }
            throw new ParseException(line, "unterminated string");
        }

        List<String> readArray() throws ParseException {
            pos++; // [
            List<String> items = new ArrayList<>();
            skipSpaces();
            if (peek() == ']') {
                pos++;
                return items;
            }
            while (true) {
                skipSpaces();
                if (peek() != '"') {
                    throw new ParseException(line, "arrays may only contain strings");
                }
                items.add(readString());
                skipSpaces();
                char ch = peek();
                pos++;
                if (ch == ']') {
                    return items;
                }
                if (ch != ',') {
                    throw new ParseException(line, "expected , or ] in array");
                }
                skipSpaces();
                if (peek() == ']') { // trailing comma
                    pos++;
                    return items;
                }
            }
        }
    }
}
