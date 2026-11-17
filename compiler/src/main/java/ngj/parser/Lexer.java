package ngj.parser;

import ngj.diagnostic.SourceSpan;
import ngj.diagnostic.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Lexer {
    private final String source;
    private final String fileName;
    private int pos = 0;
    private int line = 1;
    private int column = 1;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
        // v0.3 keywords
        Map.entry("package", TokenType.PACKAGE),
        Map.entry("import", TokenType.IMPORT),
        Map.entry("public", TokenType.PUBLIC),
        Map.entry("fn", TokenType.FN),
        Map.entry("val", TokenType.VAL),
        Map.entry("var", TokenType.VAR),
        Map.entry("if", TokenType.IF),
        Map.entry("else", TokenType.ELSE),
        Map.entry("match", TokenType.MATCH),
        Map.entry("case", TokenType.CASE),
        Map.entry("record", TokenType.RECORD),
        Map.entry("enum", TokenType.ENUM),
        Map.entry("effects", TokenType.EFFECTS),
        Map.entry("for", TokenType.FOR),
        Map.entry("yield", TokenType.YIELD),
        Map.entry("while", TokenType.WHILE),
        Map.entry("true", TokenType.TRUE),
        Map.entry("false", TokenType.FALSE),
        // Valhalla foundation keywords
        Map.entry("identity", TokenType.IDENTITY),
        Map.entry("class", TokenType.CLASS),
        // null is a reserved keyword that must be rejected (NULL-01)
        Map.entry("null", TokenType.NULL),
        // Legacy v0.2 keywords (for migration diagnostics)
        Map.entry("module", TokenType.MODULE),
        Map.entry("pub", TokenType.PUB),
        Map.entry("data", TokenType.DATA),
        Map.entry("let", TokenType.LET),
        Map.entry("return", TokenType.RETURN),
        Map.entry("mut", TokenType.MUT)
    );
    
    public Lexer(String source, String fileName) {
        this.source = source;
        this.fileName = fileName;
    }
    
    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }
    
    public Token nextToken() {
        skipWhitespaceAndComments();
        
        if (pos >= source.length()) {
            return new Token(TokenType.EOF, "", span(pos, pos));
        }
        
        int start = pos;
        char c = peek();
        
        // Identifiers and keywords
        if (isAlpha(c)) {
            return scanIdentifier();
        }
        
        // Numbers
        if (isDigit(c)) {
            return scanNumber();
        }
        
        // Strings
        if (c == '"') {
            return scanString();
        }
        
        // Hole: ?identifier
        if (c == '?' && pos + 1 < source.length() && isAlpha(source.charAt(pos + 1))) {
            return scanHole();
        }
        
        // Note: () as unit literal is handled in parser, not lexer
        // because we need to distinguish between fn main() and ()
        
        // Two-character tokens
        if (pos + 1 < source.length()) {
            String two = source.substring(pos, pos + 2);
            TokenType twoType = switch (two) {
                case "->" -> TokenType.ARROW;
                case "=>" -> TokenType.FAT_ARROW;
                case "::" -> TokenType.DOUBLE_COLON;
                case "<-" -> TokenType.LEFT_ARROW;
                case "==" -> TokenType.EQ;
                case "!=" -> TokenType.NE;
                case "<=" -> TokenType.LE;
                case ">=" -> TokenType.GE;
                case "&&" -> TokenType.AND;
                case "||" -> TokenType.OR;
                default -> null;
            };
            if (twoType != null) {
                pos += 2;
                return new Token(twoType, two, span(start, pos));
            }
        }
        
        // Single-character tokens
        pos++;
        return switch (c) {
            case '(' -> new Token(TokenType.LPAREN, "(", span(start, pos));
            case ')' -> new Token(TokenType.RPAREN, ")", span(start, pos));
            case '{' -> new Token(TokenType.LBRACE, "{", span(start, pos));
            case '}' -> new Token(TokenType.RBRACE, "}", span(start, pos));
            case '[' -> new Token(TokenType.LBRACKET, "[", span(start, pos));
            case ']' -> new Token(TokenType.RBRACKET, "]", span(start, pos));
            case '<' -> new Token(TokenType.LANGLE, "<", span(start, pos));
            case '>' -> new Token(TokenType.RANGLE, ">", span(start, pos));
            case ',' -> new Token(TokenType.COMMA, ",", span(start, pos));
            case '.' -> new Token(TokenType.DOT, ".", span(start, pos));
            case ':' -> new Token(TokenType.COLON, ":", span(start, pos));
            case ';' -> new Token(TokenType.SEMICOLON, ";", span(start, pos));
            case '+' -> new Token(TokenType.PLUS, "+", span(start, pos));
            case '-' -> new Token(TokenType.MINUS, "-", span(start, pos));
            case '*' -> new Token(TokenType.STAR, "*", span(start, pos));
            case '/' -> new Token(TokenType.SLASH, "/", span(start, pos));
            case '%' -> new Token(TokenType.PERCENT, "%", span(start, pos));
            case '=' -> new Token(TokenType.ASSIGN, "=", span(start, pos));
            case '!' -> new Token(TokenType.BANG, "!", span(start, pos));
            case '?' -> new Token(TokenType.QUESTION, "?", span(start, pos));
            default -> {
                diagnostics.add(Diagnostic.error(
                    "NGJ-LEX-001",
                    "Unexpected character: " + c,
                    span(start, pos)
                ));
                yield new Token(TokenType.ERROR, String.valueOf(c), span(start, pos));
            }
        };
    }
    
    private Token scanIdentifier() {
        int start = pos;
        while (pos < source.length() && isAlphaNumeric(peek())) {
            pos++;
        }
        String text = source.substring(start, pos);
        TokenType type = KEYWORDS.getOrDefault(text, TokenType.IDENT);
        return new Token(type, text, span(start, pos));
    }
    
    private Token scanNumber() {
        int start = pos;
        while (pos < source.length() && isDigit(peek())) {
            pos++;
        }
        
        boolean isLong = false;
        if (pos < source.length() && (peek() == 'L' || peek() == 'l')) {
            isLong = true;
            pos++;
        }
        
        String text = source.substring(start, isLong ? pos - 1 : pos);
        try {
            if (isLong) {
                long value = Long.parseLong(text);
                return new Token(TokenType.LONG_LITERAL, source.substring(start, pos), value, span(start, pos));
            } else {
                int value = Integer.parseInt(text);
                return new Token(TokenType.INT_LITERAL, text, value, span(start, pos));
            }
        } catch (NumberFormatException e) {
            diagnostics.add(Diagnostic.error(
                "NGJ-LEX-002",
                "Invalid number literal: " + text,
                span(start, pos)
            ));
            return new Token(TokenType.ERROR, text, span(start, pos));
        }
    }
    
    private Token scanString() {
        int start = pos;
        pos++; // skip opening quote
        
        var sb = new StringBuilder();
        while (pos < source.length() && peek() != '"') {
            if (peek() == '\\') {
                pos++;
                if (pos >= source.length()) break;
                char escaped = switch (peek()) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    case 'u' -> {
                        pos++;
                        if (pos + 4 <= source.length()) {
                            String hex = source.substring(pos, pos + 4);
                            pos += 3;
                            yield (char) Integer.parseInt(hex, 16);
                        }
                        yield '?';
                    }
                    default -> peek();
                };
                sb.append(escaped);
            } else if (peek() == '\n') {
                diagnostics.add(Diagnostic.error(
                    "NGJ-LEX-003",
                    "Unterminated string literal",
                    span(start, pos)
                ));
                break;
            } else {
                sb.append(peek());
            }
            pos++;
        }
        
        if (pos < source.length() && peek() == '"') {
            pos++;
        }
        
        return new Token(TokenType.STRING_LITERAL, source.substring(start, pos), sb.toString(), span(start, pos));
    }
    
    private Token scanHole() {
        int start = pos;
        pos++; // skip ?
        
        while (pos < source.length() && isAlphaNumeric(peek())) {
            pos++;
        }
        
        String text = source.substring(start, pos);
        String name = text.substring(1); // remove leading ?
        return new Token(TokenType.HOLE, text, name, span(start, pos));
    }
    
    private void skipWhitespaceAndComments() {
        while (pos < source.length()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r') {
                pos++;
            } else if (c == '\n') {
                pos++;
                line++;
                column = 1;
            } else if (c == '/' && pos + 1 < source.length()) {
                if (source.charAt(pos + 1) == '/') {
                    // Line comment
                    while (pos < source.length() && peek() != '\n') {
                        pos++;
                    }
                } else if (source.charAt(pos + 1) == '*') {
                    // Block comment
                    pos += 2;
                    while (pos + 1 < source.length() && 
                           !(source.charAt(pos) == '*' && source.charAt(pos + 1) == '/')) {
                        if (peek() == '\n') line++;
                        pos++;
                    }
                    if (pos + 1 < source.length()) {
                        pos += 2;
                    }
                } else {
                    break;
                }
            } else {
                break;
            }
        }
    }
    
    private char peek() {
        return source.charAt(pos);
    }
    
    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }
    
    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
    
    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }
    
    private SourceSpan span(int start, int end) {
        return new SourceSpan(fileName, start, end);
    }
}
