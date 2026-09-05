package io.github.truscdo.mixauth.loginchain.testmock;

import java.util.HashMap;
import java.util.Map;

final class MockJson {
    private MockJson() {
    }

    static Map<String, String> parseStringFields(String body) {
        Map<String, String> fields = new HashMap<>();
        int[] position = {skipWhitespace(body, 0)};
        if (position[0] >= body.length() || body.charAt(position[0]++) != '{') {
            throw new IllegalArgumentException("JSON object expected");
        }
        position[0] = skipWhitespace(body, position[0]);
        while (position[0] < body.length() && body.charAt(position[0]) != '}') {
            String key = readString(body, position);
            position[0] = skipWhitespace(body, position[0]);
            if (position[0] >= body.length() || body.charAt(position[0]++) != ':') {
                throw new IllegalArgumentException("JSON colon expected");
            }
            position[0] = skipWhitespace(body, position[0]);
            if (position[0] < body.length() && body.charAt(position[0]) == '"') {
                fields.put(key, readString(body, position));
            } else if (body.startsWith("null", position[0])) {
                fields.put(key, null);
                position[0] += 4;
            } else {
                position[0] = skipValue(body, position[0]);
            }
            position[0] = skipWhitespace(body, position[0]);
            if (position[0] < body.length() && body.charAt(position[0]) == ',') {
                position[0] = skipWhitespace(body, position[0] + 1);
            } else if (position[0] >= body.length() || body.charAt(position[0]) != '}') {
                throw new IllegalArgumentException("JSON delimiter expected");
            }
        }
        if (position[0] >= body.length() || body.charAt(position[0]) != '}') {
            throw new IllegalArgumentException("JSON object terminator expected");
        }
        return fields;
    }

    private static int skipValue(String body, int position) {
        if (position >= body.length()) {
            throw new IllegalArgumentException("JSON value expected");
        }
        char first = body.charAt(position);
        if (first != '{' && first != '[') {
            while (position < body.length() && body.charAt(position) != ','
                    && body.charAt(position) != '}') {
                position++;
            }
            return position;
        }

        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        while (position < body.length()) {
            char current = body.charAt(position++);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
                continue;
            }
            if (current == '"') {
                quoted = true;
            } else if (current == '{' || current == '[') {
                depth++;
            } else if (current == '}' || current == ']') {
                depth--;
                if (depth == 0) {
                    return position;
                }
            }
        }
        throw new IllegalArgumentException("JSON nested value unterminated");
    }

    private static String readString(String body, int[] position) {
        if (position[0] >= body.length() || body.charAt(position[0]++) != '"') {
            throw new IllegalArgumentException("JSON string expected");
        }
        StringBuilder result = new StringBuilder();
        while (position[0] < body.length()) {
            char current = body.charAt(position[0]++);
            if (current == '"') {
                return result.toString();
            }
            if (current != '\\') {
                result.append(current);
                continue;
            }
            if (position[0] >= body.length()) {
                break;
            }
            char escaped = body.charAt(position[0]++);
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (position[0] + 4 > body.length()) {
                        throw new IllegalArgumentException("Invalid JSON unicode escape");
                    }
                    result.append((char) Integer.parseInt(body.substring(position[0], position[0] + 4), 16));
                    position[0] += 4;
                }
                default -> throw new IllegalArgumentException("Invalid JSON escape");
            }
        }
        throw new IllegalArgumentException("JSON string unterminated");
    }

    private static int skipWhitespace(String body, int position) {
        while (position < body.length() && Character.isWhitespace(body.charAt(position))) {
            position++;
        }
        return position;
    }

    static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> result.append(current);
            }
        }
        return result.toString();
    }
}
