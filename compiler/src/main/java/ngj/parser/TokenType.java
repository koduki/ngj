package ngj.parser;

public enum TokenType {
    // v0.3 Keywords
    PACKAGE, IMPORT, PUBLIC, FN, VAL, VAR, IF, ELSE, MATCH, CASE, 
    RECORD, ENUM, EFFECTS, FOR, YIELD, WHILE,
    TRUE, FALSE,
    
    // Legacy v0.2 keywords (for migration diagnostics)
    MODULE, PUB, DATA, LET, RETURN, MUT,
    
    // Punctuation
    LPAREN, RPAREN,     // ( )
    LBRACE, RBRACE,     // { }
    LBRACKET, RBRACKET, // [ ]
    LANGLE, RANGLE,     // < >
    COMMA, DOT, COLON, SEMICOLON,
    ARROW,              // ->
    FAT_ARROW,          // =>
    DOUBLE_COLON,       // ::
    LEFT_ARROW,         // <-
    BANG,               // !
    QUESTION,           // ?
    
    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, NE, LT, LE, GT, GE,
    AND, OR,
    ASSIGN,             // =
    
    // Literals
    INT_LITERAL, LONG_LITERAL, STRING_LITERAL,
    
    // Identifier
    IDENT,
    
    // Special
    HOLE,               // ?name
    UNIT,               // ()
    
    // End of file
    EOF,
    
    // Error
    ERROR
}
