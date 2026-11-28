package ngj.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Pure-JVM, zero-reflection, deterministic JSON and compact binary boundary codec
 * with Secret masking and field refinement validation hooks.
 */
public final class BoundaryCodec {
    public static final String SECRET_MASK = SecretValue.MASK;

    private static final byte TAG_INT = 1;
    private static final byte TAG_LONG = 2;
    private static final byte TAG_BOOL = 3;
    private static final byte TAG_STRING = 4;
    private static final byte TAG_SECRET = 5;

    private BoundaryCodec() {}

    public record RawJson(String json) {}

    public static RawJson rawJson(String json) {
        return new RawJson(Objects.requireNonNull(json, "json"));
    }

    public static int requireInt(Object value) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("Expected numeric Int value, got: " + value);
        }
        long lv = n.longValue();
        if (lv < Integer.MIN_VALUE || lv > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Int overflow: " + lv);
        }
        return (int) lv;
    }

    public static long requireLong(Object value) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("Expected numeric Long value, got: " + value);
        }
        return n.longValue();
    }

    // -------------------------------------------------------------------------
    // JSON Encode Helpers (deterministic, zero reflection)
    // -------------------------------------------------------------------------

    public static String encodeJsonInt(int value) {
        return Integer.toString(value);
    }

    public static String encodeJsonLong(long value) {
        return Long.toString(value);
    }

    public static String encodeJsonBool(boolean value) {
        return Boolean.toString(value);
    }

    public static String encodeJsonString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("BoundaryCodec cannot encode null String");
        }
        return "\"" + escapeJson(value) + "\"";
    }

    public static String encodeJsonSecret(SecretValue<?> ignored) {
        return "\"" + SECRET_MASK + "\"";
    }

    public static String encodeJsonObject(Map<String, Object> fields) {
        Objects.requireNonNull(fields, "fields");
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(encodeJsonString(entry.getKey())).append(':');
            Object v = entry.getValue();
            if (v instanceof Integer i) {
                sb.append(encodeJsonInt(i));
            } else if (v instanceof Long l) {
                sb.append(encodeJsonLong(l));
            } else if (v instanceof Boolean b) {
                sb.append(encodeJsonBool(b));
            } else if (v instanceof String s) {
                sb.append(encodeJsonString(s));
            } else if (v instanceof SecretValue<?> sec) {
                sb.append(encodeJsonSecret(sec));
            } else if (v instanceof ValidatedValue<?> vv) {
                sb.append(encodeScalar(vv.value()));
            } else if (v instanceof RawJson rj) {
                sb.append(rj.json());
            } else if (v instanceof Map<?, ?> nested) {
                Map<String, Object> nestedFields = new LinkedHashMap<>();
                for (Map.Entry<?, ?> ne : nested.entrySet()) {
                    nestedFields.put(String.valueOf(ne.getKey()), ne.getValue());
                }
                sb.append(encodeJsonObject(nestedFields));
            } else {
                throw new IllegalArgumentException(
                    "Unsupported BoundaryCodec JSON value type: " + (v == null ? "null" : v.getClass().getName())
                );
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String encodeScalar(Object v) {
        if (v instanceof Integer i) return encodeJsonInt(i);
        if (v instanceof Long l) return encodeJsonLong(l);
        if (v instanceof Boolean b) return encodeJsonBool(b);
        if (v instanceof String s) return encodeJsonString(s);
        if (v instanceof SecretValue<?> sec) return encodeJsonSecret(sec);
        throw new IllegalArgumentException(
            "Unsupported BoundaryCodec scalar type: " + (v == null ? "null" : v.getClass().getName())
        );
    }

    // -------------------------------------------------------------------------
    // JSON Decode Helpers (deterministic recursive-descent, zero reflection)
    // -------------------------------------------------------------------------

    public static int decodeJsonInt(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input must not be null");
        }
        try {
            return Integer.parseInt(json.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid JSON Int: " + json, e);
        }
    }

    public static long decodeJsonLong(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input must not be null");
        }
        try {
            return Long.parseLong(json.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid JSON Long: " + json, e);
        }
    }

    public static boolean decodeJsonBool(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input must not be null");
        }
        String trimmed = json.trim();
        if ("true".equals(trimmed)) return true;
        if ("false".equals(trimmed)) return false;
        throw new IllegalArgumentException("Invalid JSON Bool: " + json);
    }

    public static String decodeJsonString(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input must not be null");
        }
        JsonCursor cursor = new JsonCursor(json.trim());
        String result = cursor.readString();
        cursor.skipWhitespace();
        if (!cursor.isAtEnd()) {
            throw new IllegalArgumentException("Trailing characters after JSON string");
        }
        return result;
    }

    public static Map<String, Object> decodeJsonObject(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input must not be null");
        }
        JsonCursor cursor = new JsonCursor(json.trim());
        Map<String, Object> result = cursor.readObject();
        cursor.skipWhitespace();
        if (!cursor.isAtEnd()) {
            throw new IllegalArgumentException("Trailing characters after JSON object");
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Compact Binary Encode / Decode Helpers (deterministic, big-endian)
    // -------------------------------------------------------------------------

    public static byte[] encodeBinaryInt(int value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(5);
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeByte(TAG_INT);
            dos.writeInt(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return baos.toByteArray();
    }

    public static int decodeBinaryInt(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte tag = dis.readByte();
            if (tag != TAG_INT) {
                throw new IllegalArgumentException("Expected TAG_INT (1), got " + tag);
            }
            int v = dis.readInt();
            if (dis.available() != 0) {
                throw new IllegalArgumentException("Trailing bytes after binary Int");
            }
            return v;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid binary Int payload", e);
        }
    }

    public static byte[] encodeBinaryLong(long value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(9);
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeByte(TAG_LONG);
            dos.writeLong(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return baos.toByteArray();
    }

    public static long decodeBinaryLong(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte tag = dis.readByte();
            if (tag != TAG_LONG) {
                throw new IllegalArgumentException("Expected TAG_LONG (2), got " + tag);
            }
            long v = dis.readLong();
            if (dis.available() != 0) {
                throw new IllegalArgumentException("Trailing bytes after binary Long");
            }
            return v;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid binary Long payload", e);
        }
    }

    public static byte[] encodeBinaryBool(boolean value) {
        return new byte[] { TAG_BOOL, value ? (byte) 1 : (byte) 0 };
    }

    public static boolean decodeBinaryBool(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != 2 || bytes[0] != TAG_BOOL || (bytes[1] != 0 && bytes[1] != 1)) {
            throw new IllegalArgumentException("Invalid binary Bool payload");
        }
        return bytes[1] == 1;
    }

    public static byte[] encodeBinaryString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("BoundaryCodec cannot encode null String");
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(5 + utf8.length);
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeByte(TAG_STRING);
            dos.writeInt(utf8.length);
            dos.write(utf8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return baos.toByteArray();
    }

    public static String decodeBinaryString(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte tag = dis.readByte();
            if (tag != TAG_STRING && tag != TAG_SECRET) {
                throw new IllegalArgumentException("Expected TAG_STRING (4) or TAG_SECRET (5), got " + tag);
            }
            int len = dis.readInt();
            if (len < 0 || len > dis.available()) {
                throw new IllegalArgumentException("Invalid binary String length: " + len);
            }
            byte[] utf8 = dis.readNBytes(len);
            if (dis.available() != 0) {
                throw new IllegalArgumentException("Trailing bytes after binary String");
            }
            return new String(utf8, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid binary String payload", e);
        }
    }

    public static byte[] encodeBinarySecret(SecretValue<?> ignored) {
        byte[] utf8 = SECRET_MASK.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(5 + utf8.length);
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeByte(TAG_SECRET);
            dos.writeInt(utf8.length);
            dos.write(utf8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Field Refinement Validation Hooks
    // -------------------------------------------------------------------------

    public static <T> Optional<RefinementError> validateRefinement(
        String predicateName,
        T value,
        Predicate<? super T> predicate,
        String errorMessage
    ) {
        Objects.requireNonNull(predicate, "predicate");
        if (!predicate.test(value)) {
            return Optional.of(new RefinementError.PredicateFailed(predicateName, errorMessage));
        }
        return Optional.empty();
    }

    public static Optional<RefinementError> validateIntRange(long value, long min, long max) {
        if (value < min || value > max) {
            return Optional.of(new RefinementError.OutOfRange(value, min, max));
        }
        return Optional.empty();
    }

    public static <T> Optional<ValidationError> validateBoundaryField(
        String fieldName,
        T value,
        Predicate<? super T> predicate,
        String errorCode,
        String errorMessage
    ) {
        Objects.requireNonNull(predicate, "predicate");
        if (!predicate.test(value)) {
            String msg = errorMessage == null
                ? "Field '" + fieldName + "' failed boundary validation"
                : errorMessage;
            return Optional.of(new ValidationError(errorCode, msg));
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------------

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
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
        return sb.toString();
    }

    private static final class JsonCursor {
        private final String input;
        private int pos;

        JsonCursor(String input) {
            this.input = input;
            this.pos = 0;
        }

        boolean isAtEnd() {
            return pos >= input.length();
        }

        void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        Map<String, Object> readObject() {
            skipWhitespace();
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object val = readScalarValue();
                map.put(key, val);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at position " + pos);
                }
            }
        }

        Object readScalarValue() {
            skipWhitespace();
            if (isAtEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = input.charAt(pos);
            if (c == '{') {
                return readObject();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't') {
                expectLiteral("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expectLiteral("false");
                return Boolean.FALSE;
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return readNumber();
            }
            throw new IllegalArgumentException("Unsupported JSON value at position " + pos + ": " + c);
        }

        Number readNumber() {
            int start = pos;
            if (input.charAt(pos) == '-') {
                pos++;
            }
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
            String numStr = input.substring(start, pos);
            try {
                long l = Long.parseLong(numStr);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid JSON integer: " + numStr, e);
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= input.length()) {
                        throw new IllegalArgumentException("Unterminated JSON escape");
                    }
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > input.length()) {
                                throw new IllegalArgumentException("Invalid \\u escape");
                            }
                            String hex = input.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Unknown JSON escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private void expect(char expected) {
            if (pos >= input.length() || input.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + pos);
            }
            pos++;
        }

        private void expectLiteral(String literal) {
            if (!input.startsWith(literal, pos)) {
                throw new IllegalArgumentException("Expected '" + literal + "' at position " + pos);
            }
            pos += literal.length();
        }

        private boolean peek(char c) {
            return pos < input.length() && input.charAt(pos) == c;
        }
    }
}
