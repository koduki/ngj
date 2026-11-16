package ngj.parser;

import ngj.ast.*;
import ngj.diagnostic.*;

import java.util.*;

public final class Parser {
    private final Lexer lexer;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private Token current;
    private Token previous;
    
    public Parser(Lexer lexer) {
        this.lexer = lexer;
        this.current = lexer.nextToken();
    }
    
    public List<Diagnostic> getDiagnostics() {
        var result = new ArrayList<>(lexer.getDiagnostics());
        result.addAll(diagnostics);
        return result;
    }
    
    public CompilationUnit parse() throws ParseException {
        int start = current.span().startByte();
        
        // Parse module declaration
        var moduleDecl = parseModuleDecl();
        
        // Parse imports
        var imports = new ArrayList<ImportDecl>();
        while (check(TokenType.IMPORT)) {
            imports.add(parseImportDecl());
        }
        
        // Parse top-level declarations
        var declarations = new ArrayList<TopLevelDecl>();
        while (!isAtEnd()) {
            declarations.add(parseTopLevelDecl());
        }
        
        int end = previous != null ? previous.span().endByte() : start;
        return new CompilationUnit(moduleDecl, imports, declarations, new SourceSpan("", start, end));
    }
    
    private ModuleDecl parseModuleDecl() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.MODULE, "Expected 'module' at start of file");
        
        var path = parseModulePath();
        int end = previous.span().endByte();
        
        return new ModuleDecl(path, new SourceSpan("", start, end));
    }
    
    private String parseModulePath() throws ParseException {
        var sb = new StringBuilder();
        consumeIdentOrKeyword("Expected module name");
        sb.append(previous.text());
        
        while (match(TokenType.DOT)) {
            consumeIdentOrKeyword("Expected identifier after '.'");
            sb.append(".").append(previous.text());
        }
        
        return sb.toString();
    }
    
    private void consumeIdentOrKeyword(String message) throws ParseException {
        // Module paths can contain keywords like 'data', 'enum', etc.
        if (check(TokenType.IDENT) || isKeyword(current.type())) {
            advance();
            return;
        }
        throw error("NGJ-PARSE-007", message + ", got " + current.type(), current.span());
    }
    
    private boolean isKeyword(TokenType type) {
        return type == TokenType.DATA || type == TokenType.ENUM || 
               type == TokenType.FN || type == TokenType.PUB ||
               type == TokenType.MODULE || type == TokenType.IMPORT ||
               type == TokenType.LET || type == TokenType.IF ||
               type == TokenType.ELSE || type == TokenType.MATCH ||
               type == TokenType.RETURN || type == TokenType.TRUE ||
               type == TokenType.FALSE;
    }
    
    private ImportDecl parseImportDecl() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.IMPORT, "Expected 'import'");
        
        // Parse import path, stopping at { or *
        var pathParts = new ArrayList<String>();
        consumeIdentOrKeyword("Expected import path");
        pathParts.add(previous.text());
        
        while (match(TokenType.DOT)) {
            // Check what follows the dot
            if (check(TokenType.LBRACE)) {
                // Grouped imports: import std.io.{read, write}
                advance(); // consume {
                var names = new ArrayList<String>();
                do {
                    consume(TokenType.IDENT, "Expected import name");
                    names.add(previous.text());
                } while (match(TokenType.COMMA));
                
                consume(TokenType.RBRACE, "Expected '}' after import names");
                var modulePath = String.join(".", pathParts);
                return new ImportDecl(modulePath, names, false, new SourceSpan("", start, previous.span().endByte()));
            } else if (check(TokenType.STAR)) {
                // Wildcard import (rejected)
                advance(); // consume *
                error("NGJ-PARSE-001", "Wildcard imports are not supported", previous.span());
                var modulePath = String.join(".", pathParts);
                return new ImportDecl(modulePath, List.of(), true, new SourceSpan("", start, previous.span().endByte()));
            } else {
                // Continue building path
                consumeIdentOrKeyword("Expected identifier after '.'");
                pathParts.add(previous.text());
            }
        }
        
        // Single identifier import: import foo.bar.Baz
        // The last part is the imported name, the rest is the module path
        if (pathParts.size() > 1) {
            String name = pathParts.remove(pathParts.size() - 1);
            var modulePath = String.join(".", pathParts);
            return new ImportDecl(modulePath, List.of(name), false, new SourceSpan("", start, previous.span().endByte()));
        }
        
        // Just a single name
        return new ImportDecl("", pathParts, false, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private TopLevelDecl parseTopLevelDecl() throws ParseException {
        boolean isPublic = match(TokenType.PUB);
        
        if (check(TokenType.FN)) {
            return parseFnDecl(isPublic);
        } else if (check(TokenType.DATA)) {
            return parseDataDecl(isPublic);
        } else if (check(TokenType.ENUM)) {
            return parseEnumDecl(isPublic);
        }
        
        throw error("NGJ-PARSE-002", "Expected function, data, or enum declaration", current.span());
    }
    
    private FnDecl parseFnDecl(boolean isPublic) throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.FN, "Expected 'fn'");
        
        consume(TokenType.IDENT, "Expected function name");
        String name = previous.text();
        
        // Parameters
        consume(TokenType.LPAREN, "Expected '(' after function name");
        var params = new ArrayList<Param>();
        if (!check(TokenType.RPAREN)) {
            do {
                params.add(parseParam());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RPAREN, "Expected ')' after parameters");
        
        // Return type
        consume(TokenType.ARROW, "Expected '->' before return type");
        var returnType = parseTypeExpr();
        
        // Effect bound
        var effects = parseEffectBound();
        
        // Body
        var body = parseBlock();
        
        return new FnDecl(name, isPublic, params, returnType, effects, body,
            new SourceSpan("", start, previous.span().endByte()));
    }
    
    private Param parseParam() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.IDENT, "Expected parameter name");
        String name = previous.text();
        
        consume(TokenType.COLON, "Expected ':' after parameter name");
        var type = parseTypeExpr();
        
        return new Param(name, type, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private List<String> parseEffectBound() throws ParseException {
        consume(TokenType.BANG, "Expected '!' for effect bound");
        consume(TokenType.LBRACE, "Expected '{' after '!'");
        
        var effects = new ArrayList<String>();
        if (!check(TokenType.RBRACE)) {
            do {
                consume(TokenType.IDENT, "Expected effect name");
                String effect = previous.text();
                
                // Check for unsupported effects in MVP
                if (!effect.isEmpty()) {
                    error("NGJ-EFFECT-001", 
                        "Effect '" + effect + "' is not supported in MVP. Only pure functions (! {}) are allowed.",
                        previous.span());
                }
                effects.add(effect);
            } while (match(TokenType.COMMA));
        }
        
        consume(TokenType.RBRACE, "Expected '}' after effect list");
        return effects;
    }
    
    private DataDecl parseDataDecl(boolean isPublic) throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.DATA, "Expected 'data'");
        
        consume(TokenType.IDENT, "Expected type name");
        String name = previous.text();
        
        // Check for generic parameters (not supported in MVP for user types)
        if (check(TokenType.LANGLE)) {
            error("NGJ-PARSE-003", "Generic type parameters on data types are not supported in MVP", current.span());
            // Try to skip the generic params for error recovery
            match(TokenType.LANGLE);
            int depth = 1;
            while (depth > 0 && !isAtEnd()) {
                if (match(TokenType.LANGLE)) depth++;
                else if (match(TokenType.RANGLE)) depth--;
                else advance();
            }
        }
        
        consume(TokenType.LBRACE, "Expected '{' after data name");
        
        var fields = new ArrayList<FieldDecl>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            fields.add(parseFieldDecl());
            match(TokenType.COMMA); // Optional trailing comma
        }
        
        consume(TokenType.RBRACE, "Expected '}' after data fields");
        
        return new DataDecl(name, isPublic, fields, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private FieldDecl parseFieldDecl() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.IDENT, "Expected field name");
        String name = previous.text();
        
        consume(TokenType.COLON, "Expected ':' after field name");
        var type = parseTypeExpr();
        
        return new FieldDecl(name, type, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private EnumDecl parseEnumDecl(boolean isPublic) throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.ENUM, "Expected 'enum'");
        
        consume(TokenType.IDENT, "Expected type name");
        String name = previous.text();
        
        // Check for generic parameters
        if (check(TokenType.LANGLE)) {
            error("NGJ-PARSE-003", "Generic type parameters on enum types are not supported in MVP", current.span());
            match(TokenType.LANGLE);
            int depth = 1;
            while (depth > 0 && !isAtEnd()) {
                if (match(TokenType.LANGLE)) depth++;
                else if (match(TokenType.RANGLE)) depth--;
                else advance();
            }
        }
        
        consume(TokenType.LBRACE, "Expected '{' after enum name");
        
        var variants = new ArrayList<Variant>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            variants.add(parseVariant());
            match(TokenType.COMMA); // Optional trailing comma
        }
        
        consume(TokenType.RBRACE, "Expected '}' after enum variants");
        
        return new EnumDecl(name, isPublic, variants, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private Variant parseVariant() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.IDENT, "Expected variant name");
        String name = previous.text();
        
        var fields = new ArrayList<FieldDecl>();
        if (match(TokenType.LPAREN)) {
            if (!check(TokenType.RPAREN)) {
                do {
                    fields.add(parseFieldDecl());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RPAREN, "Expected ')' after variant fields");
        }
        
        return new Variant(name, fields, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private TypeExpr parseTypeExpr() throws ParseException {
        int start = current.span().startByte();
        
        if (match(TokenType.UNIT)) {
            return new UnitTypeExpr(new SourceSpan("", start, previous.span().endByte()));
        }
        
        consume(TokenType.IDENT, "Expected type name");
        String name = previous.text();
        
        // Check for generic type arguments (only allowed for Result/Option in MVP)
        if (match(TokenType.LANGLE)) {
            var typeArgs = new ArrayList<TypeExpr>();
            do {
                typeArgs.add(parseTypeExpr());
            } while (match(TokenType.COMMA));
            
            consume(TokenType.RANGLE, "Expected '>' after type arguments");
            
            // Validate: only Result and Option are allowed generics in MVP
            if (!name.equals("Result") && !name.equals("Option")) {
                error("NGJ-PARSE-004", 
                    "Generic types other than Result<T,E> and Option<T> are not supported in MVP",
                    new SourceSpan("", start, previous.span().endByte()));
            }
            
            return new GenericTypeExpr(name, typeArgs, new SourceSpan("", start, previous.span().endByte()));
        }
        
        return new SimpleTypeExpr(name, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private BlockExpr parseBlock() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.LBRACE, "Expected '{'");
        
        var statements = new ArrayList<Statement>();
        Expr tailExpr = null;
        
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            // Try to parse statement or expression
            if (check(TokenType.LET)) {
                statements.add(parseLetStmt());
            } else if (check(TokenType.RETURN)) {
                statements.add(parseReturnStmt());
            } else {
                // Could be expr statement or tail expr
                var expr = parseExpr();
                
                if (check(TokenType.RBRACE)) {
                    // This is the tail expression
                    tailExpr = expr;
                } else {
                    // This is an expression statement
                    statements.add(new ExprStmt(expr, expr.span()));
                }
            }
        }
        
        consume(TokenType.RBRACE, "Expected '}'");
        
        return new BlockExpr(statements, tailExpr, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private LetStmt parseLetStmt() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.LET, "Expected 'let'");
        
        consume(TokenType.IDENT, "Expected variable name");
        String name = previous.text();
        
        TypeExpr explicitType = null;
        if (match(TokenType.COLON)) {
            explicitType = parseTypeExpr();
        }
        
        consume(TokenType.ASSIGN, "Expected '=' after variable name");
        var initializer = parseExpr();
        
        return new LetStmt(name, explicitType, initializer, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private ReturnStmt parseReturnStmt() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.RETURN, "Expected 'return'");
        
        Expr value = null;
        if (!check(TokenType.RBRACE) && !check(TokenType.SEMICOLON)) {
            value = parseExpr();
        }
        
        return new ReturnStmt(value, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private Expr parseExpr() throws ParseException {
        return parseOr();
    }
    
    private Expr parseOr() throws ParseException {
        var expr = parseAnd();
        
        while (match(TokenType.OR)) {
            int start = expr.span().startByte();
            var right = parseAnd();
            expr = new BinaryExpr(expr, BinaryExpr.BinaryOp.OR, right, 
                new SourceSpan("", start, right.span().endByte()));
        }
        
        return parsePropagateOpt(expr);
    }
    
    private Expr parseAnd() throws ParseException {
        var expr = parseComparison();
        
        while (match(TokenType.AND)) {
            int start = expr.span().startByte();
            var right = parseComparison();
            expr = new BinaryExpr(expr, BinaryExpr.BinaryOp.AND, right,
                new SourceSpan("", start, right.span().endByte()));
        }
        
        return expr;
    }
    
    private Expr parseComparison() throws ParseException {
        var expr = parseAddition();
        
        if (match(TokenType.EQ)) {
            var right = parseAddition();
            return new BinaryExpr(expr, BinaryExpr.BinaryOp.EQ, right,
                new SourceSpan("", expr.span().startByte(), right.span().endByte()));
        } else if (match(TokenType.NE)) {
            var right = parseAddition();
            return new BinaryExpr(expr, BinaryExpr.BinaryOp.NE, right,
                new SourceSpan("", expr.span().startByte(), right.span().endByte()));
        } else if (match(TokenType.LANGLE)) {
            // Could be < or type argument start - check context
            if (!isTypeContext()) {
                var right = parseAddition();
                return new BinaryExpr(expr, BinaryExpr.BinaryOp.LT, right,
                    new SourceSpan("", expr.span().startByte(), right.span().endByte()));
            }
            // Backtrack if it was type context
            pos--;
        } else if (match(TokenType.LE)) {
            var right = parseAddition();
            return new BinaryExpr(expr, BinaryExpr.BinaryOp.LE, right,
                new SourceSpan("", expr.span().startByte(), right.span().endByte()));
        } else if (match(TokenType.RANGLE)) {
            var right = parseAddition();
            return new BinaryExpr(expr, BinaryExpr.BinaryOp.GT, right,
                new SourceSpan("", expr.span().startByte(), right.span().endByte()));
        } else if (match(TokenType.GE)) {
            var right = parseAddition();
            return new BinaryExpr(expr, BinaryExpr.BinaryOp.GE, right,
                new SourceSpan("", expr.span().startByte(), right.span().endByte()));
        }
        
        return expr;
    }
    
    private int pos = 0; // For backtracking
    
    private boolean isTypeContext() {
        // Simple heuristic: if next token is an identifier followed by > or ,
        // it's likely a type context
        return false; // Conservative: treat < as comparison
    }
    
    private Expr parseAddition() throws ParseException {
        var expr = parseMultiplication();
        
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            var op = previous.type() == TokenType.PLUS ? 
                BinaryExpr.BinaryOp.ADD : BinaryExpr.BinaryOp.SUB;
            var right = parseMultiplication();
            expr = new BinaryExpr(expr, op, right,
                new SourceSpan("", expr.span().startByte(), right.span().endByte()));
        }
        
        return expr;
    }
    
    private Expr parseMultiplication() throws ParseException {
        var expr = parseUnary();
        
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            var op = switch (previous.type()) {
                case STAR -> BinaryExpr.BinaryOp.MUL;
                case SLASH -> BinaryExpr.BinaryOp.DIV;
                case PERCENT -> BinaryExpr.BinaryOp.MOD;
                default -> throw new IllegalStateException();
            };
            var right = parseUnary();
            expr = new BinaryExpr(expr, op, right,
                new SourceSpan("", expr.span().startByte(), right.span().endByte()));
        }
        
        return expr;
    }
    
    private Expr parseUnary() throws ParseException {
        if (match(TokenType.MINUS)) {
            int start = previous.span().startByte();
            var operand = parseUnary();
            return new UnaryExpr(UnaryExpr.UnaryOp.NEG, operand,
                new SourceSpan("", start, operand.span().endByte()));
        }
        if (match(TokenType.BANG)) {
            int start = previous.span().startByte();
            var operand = parseUnary();
            return new UnaryExpr(UnaryExpr.UnaryOp.NOT, operand,
                new SourceSpan("", start, operand.span().endByte()));
        }
        
        return parsePostfix();
    }
    
    private Expr parsePostfix() throws ParseException {
        var expr = parsePrimary();
        
        while (true) {
            if (match(TokenType.DOT)) {
                consume(TokenType.IDENT, "Expected field name after '.'");
                expr = new FieldAccessExpr(expr, previous.text(),
                    new SourceSpan("", expr.span().startByte(), previous.span().endByte()));
            } else if (match(TokenType.LPAREN)) {
                // Function call
                var args = new ArrayList<Expr>();
                if (!check(TokenType.RPAREN)) {
                    do {
                        args.add(parseExpr());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RPAREN, "Expected ')' after arguments");
                expr = new CallExpr(expr, args,
                    new SourceSpan("", expr.span().startByte(), previous.span().endByte()));
            } else {
                break;
            }
        }
        
        return expr;
    }
    
    private Expr parsePropagateOpt(Expr expr) throws ParseException {
        if (match(TokenType.QUESTION)) {
            return new PropagateExpr(expr,
                new SourceSpan("", expr.span().startByte(), previous.span().endByte()));
        }
        return expr;
    }
    
    private Expr parsePrimary() throws ParseException {
        int start = current.span().startByte();
        
        // Literals
        if (match(TokenType.INT_LITERAL)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.INT, previous.value(),
                new SourceSpan("", start, previous.span().endByte()));
        }
        if (match(TokenType.LONG_LITERAL)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.LONG, previous.value(),
                new SourceSpan("", start, previous.span().endByte()));
        }
        if (match(TokenType.STRING_LITERAL)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.STRING, previous.value(),
                new SourceSpan("", start, previous.span().endByte()));
        }
        if (match(TokenType.TRUE)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.BOOL, true,
                new SourceSpan("", start, previous.span().endByte()));
        }
        if (match(TokenType.FALSE)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.BOOL, false,
                new SourceSpan("", start, previous.span().endByte()));
        }
        if (match(TokenType.UNIT)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.UNIT, null,
                new SourceSpan("", start, previous.span().endByte()));
        }
        
        // Hole
        if (match(TokenType.HOLE)) {
            return new HoleExpr((String) previous.value(),
                new SourceSpan("", start, previous.span().endByte()));
        }
        
        // If expression
        if (match(TokenType.IF)) {
            return parseIfExpr(start);
        }
        
        // Match expression
        if (match(TokenType.MATCH)) {
            return parseMatchExpr(start);
        }
        
        // Block
        if (check(TokenType.LBRACE)) {
            return parseBlock();
        }
        
        // Parenthesized or unit
        if (match(TokenType.LPAREN)) {
            if (match(TokenType.RPAREN)) {
                return new LiteralExpr(LiteralExpr.LiteralKind.UNIT, null,
                    new SourceSpan("", start, previous.span().endByte()));
            }
            var expr = parseExpr();
            consume(TokenType.RPAREN, "Expected ')' after expression");
            return new ParenExpr(expr, new SourceSpan("", start, previous.span().endByte()));
        }
        
        // Identifier or constructor
        if (match(TokenType.IDENT)) {
            String name = previous.text();
            
            // Check for constructor call: TypeName { ... }
            if (check(TokenType.LBRACE) && Character.isUpperCase(name.charAt(0))) {
                return parseConstructorExpr(name, start);
            }
            
            return new IdentExpr(name, new SourceSpan("", start, previous.span().endByte()));
        }
        
        throw error("NGJ-PARSE-005", "Unexpected token: " + current.type(), current.span());
    }
    
    private Expr parseIfExpr(int start) throws ParseException {
        var condition = parseExpr();
        var thenBranch = parseBlock();
        
        Expr elseBranch = null;
        if (match(TokenType.ELSE)) {
            if (check(TokenType.IF)) {
                advance();
                elseBranch = parseIfExpr(previous.span().startByte());
            } else {
                elseBranch = parseBlock();
            }
        }
        
        return new IfExpr(condition, thenBranch, elseBranch,
            new SourceSpan("", start, previous.span().endByte()));
    }
    
    private Expr parseMatchExpr(int start) throws ParseException {
        var scrutinee = parseExpr();
        
        consume(TokenType.LBRACE, "Expected '{' after match expression");
        
        var arms = new ArrayList<MatchExpr.MatchArm>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            arms.add(parseMatchArm());
            match(TokenType.COMMA); // Optional
        }
        
        consume(TokenType.RBRACE, "Expected '}' after match arms");
        
        return new MatchExpr(scrutinee, arms, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private MatchExpr.MatchArm parseMatchArm() throws ParseException {
        int start = current.span().startByte();
        var pattern = parsePattern();
        
        consume(TokenType.FAT_ARROW, "Expected '=>' after pattern");
        
        Expr body;
        if (check(TokenType.LBRACE)) {
            body = parseBlock();
        } else {
            body = parseExpr();
        }
        
        return new MatchExpr.MatchArm(pattern, body, new SourceSpan("", start, previous.span().endByte()));
    }
    
    private Pattern parsePattern() throws ParseException {
        int start = current.span().startByte();
        
        // Wildcard
        if (current.text().equals("_")) {
            advance();
            return new WildcardPattern(new SourceSpan("", start, previous.span().endByte()));
        }
        
        // Literal patterns
        if (match(TokenType.INT_LITERAL, TokenType.LONG_LITERAL, TokenType.STRING_LITERAL,
                TokenType.TRUE, TokenType.FALSE)) {
            var kind = switch (previous.type()) {
                case INT_LITERAL -> LiteralExpr.LiteralKind.INT;
                case LONG_LITERAL -> LiteralExpr.LiteralKind.LONG;
                case STRING_LITERAL -> LiteralExpr.LiteralKind.STRING;
                case TRUE, FALSE -> LiteralExpr.LiteralKind.BOOL;
                default -> throw new IllegalStateException();
            };
            var value = previous.type() == TokenType.TRUE ? true :
                        previous.type() == TokenType.FALSE ? false :
                        previous.value();
            var literal = new LiteralExpr(kind, value, previous.span());
            return new LiteralPattern(literal, new SourceSpan("", start, previous.span().endByte()));
        }
        
        // Constructor pattern or binding
        if (match(TokenType.IDENT)) {
            String name = previous.text();
            
            // Constructor pattern
            if (match(TokenType.LPAREN)) {
                var fields = new ArrayList<ConstructorPattern.PatternField>();
                if (!check(TokenType.RPAREN)) {
                    do {
                        int fieldStart = current.span().startByte();
                        consume(TokenType.IDENT, "Expected field name");
                        String fieldName = previous.text();
                        
                        Pattern fieldPattern = null;
                        if (match(TokenType.COLON)) {
                            fieldPattern = parsePattern();
                        }
                        
                        fields.add(new ConstructorPattern.PatternField(
                            fieldName, fieldPattern, 
                            new SourceSpan("", fieldStart, previous.span().endByte())));
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RPAREN, "Expected ')' after constructor fields");
                
                return new ConstructorPattern(name, fields, new SourceSpan("", start, previous.span().endByte()));
            }
            
            // Unit constructor (no parens)
            if (Character.isUpperCase(name.charAt(0))) {
                return new ConstructorPattern(name, List.of(), new SourceSpan("", start, previous.span().endByte()));
            }
            
            // Binding pattern
            return new BindingPattern(name, new SourceSpan("", start, previous.span().endByte()));
        }
        
        throw error("NGJ-PARSE-006", "Expected pattern", current.span());
    }
    
    private Expr parseConstructorExpr(String typePath, int start) throws ParseException {
        consume(TokenType.LBRACE, "Expected '{' for constructor");
        
        var fields = new ArrayList<ConstructorExpr.FieldInit>();
        if (!check(TokenType.RBRACE)) {
            do {
                int fieldStart = current.span().startByte();
                consume(TokenType.IDENT, "Expected field name");
                String fieldName = previous.text();
                
                consume(TokenType.COLON, "Expected ':' after field name");
                var value = parseExpr();
                
                fields.add(new ConstructorExpr.FieldInit(fieldName, value,
                    new SourceSpan("", fieldStart, previous.span().endByte())));
            } while (match(TokenType.COMMA));
        }
        
        consume(TokenType.RBRACE, "Expected '}' after constructor fields");
        
        return new ConstructorExpr(typePath, fields, new SourceSpan("", start, previous.span().endByte()));
    }
    
    // Utility methods
    
    private boolean check(TokenType type) {
        return current.type() == type;
    }
    
    private boolean match(TokenType... types) {
        for (var type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }
    
    private Token advance() {
        previous = current;
        current = lexer.nextToken();
        return previous;
    }
    
    private void consume(TokenType type, String message) throws ParseException {
        if (check(type)) {
            advance();
            return;
        }
        throw error("NGJ-PARSE-007", message + ", got " + current.type(), current.span());
    }
    
    private boolean isAtEnd() {
        return current.type() == TokenType.EOF;
    }
    
    private ParseException error(String id, String message, SourceSpan span) {
        var diag = Diagnostic.error(id, message, span);
        diagnostics.add(diag);
        return new ParseException(diagnostics);
    }
}
