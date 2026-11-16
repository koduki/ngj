package ngj.parser;

import ngj.diagnostic.SourceSpan;

public record Token(
    TokenType type,
    String text,
    Object value,
    SourceSpan span
) {
    public Token(TokenType type, String text, SourceSpan span) {
        this(type, text, null, span);
    }
}
