package ngj.parser;

public enum TokenType {
    // Keywords
    MODULE, IMPORT, PUB, FN, LET, IF, ELSE, MATCH, RETURN, DATA, ENUM,
    TRUE, FALSE,
    
    // Punctuation
    LPAREN, RPAREN,     // ( )
    LBRACE, RBRACE,     // { }
    LBRACKET, RBRACKET, // [ ]
    LANGLE, RANGLE,     // < >
    COMMA, DOT, COLON, SEMICOLON,
    ARROW,              // ->
    FAT_ARROW,          // =>
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
