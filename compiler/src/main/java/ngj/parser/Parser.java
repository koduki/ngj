package ngj.parser;

import ngj.ast.*;
import ngj.diagnostic.*;

import java.util.*;

public final class Parser {
    private final Lexer lexer;
    private final String fileName;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private Token current;
    private Token previous;
    
    public Parser(Lexer lexer, String fileName) {
        this.lexer = lexer;
        this.fileName = fileName;
        this.current = lexer.nextToken();
    }
    
    public Parser(Lexer lexer) {
        this(lexer, "unknown");
    }
    
    public List<Diagnostic> getDiagnostics() {
        var result = new ArrayList<>(lexer.getDiagnostics());
        result.addAll(diagnostics);
        return result;
    }
    
    public CompilationUnit parse() throws ParseException {
        int start = current.span().startByte();
        
        var moduleDecl = parsePackageDecl();
        
        var imports = new ArrayList<ImportDecl>();
        while (check(TokenType.IMPORT)) {
            imports.add(parseImportDecl());
        }
        
        var declarations = new ArrayList<TopLevelDecl>();
        while (!isAtEnd()) {
            declarations.add(parseTopLevelDecl());
        }
        
        int end = previous != null ? previous.span().endByte() : start;
        return new CompilationUnit(moduleDecl, imports, declarations, span(start, end));
    }
    
    private ModuleDecl parsePackageDecl() throws ParseException {
        int start = current.span().startByte();
        
        if (check(TokenType.MODULE)) {
            advance();
            diagnostics.add(Diagnostic.error(
                "NGJ-MIGRATE-001",
                "v0.2 syntax 'module' is no longer supported. Use 'package' instead.",
                previous.span()
            ));
        } else {
            consume(TokenType.PACKAGE, "Expected 'package' at start of file");
        }
        
        var path = parseModulePath();
        consume(TokenType.SEMICOLON, "Expected ';' after package declaration");
        int end = previous.span().endByte();
        
        return new ModuleDecl(path, span(start, end));
    }
    
    private String parseModulePath() throws ParseException {
        var sb = new StringBuilder();
        consumeIdentOrKeyword("Expected package/module name");
        sb.append(previous.text());
        
        while (match(TokenType.DOT)) {
            consumeIdentOrKeyword("Expected identifier after '.'");
            sb.append(".").append(previous.text());
        }
        
        return sb.toString();
    }
    
    private void consumeIdentOrKeyword(String message) throws ParseException {
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
               type == TokenType.FALSE || type == TokenType.PACKAGE ||
               type == TokenType.PUBLIC || type == TokenType.RECORD ||
               type == TokenType.VAL || type == TokenType.VAR ||
               type == TokenType.FOR || type == TokenType.YIELD ||
               type == TokenType.WHILE || type == TokenType.CASE ||
               type == TokenType.EFFECTS;
    }
    
    private ImportDecl parseImportDecl() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.IMPORT, "Expected 'import'");
        
        var pathParts = new ArrayList<String>();
        consumeIdentOrKeyword("Expected import path");
        pathParts.add(previous.text());
        
        while (match(TokenType.DOT)) {
            if (check(TokenType.LBRACE)) {
                advance();
                diagnostics.add(Diagnostic.error(
                    "NGJ-MIGRATE-002",
                    "Grouped imports are not supported in v0.3. Use one import per declaration.",
                    previous.span()
                ));
                var names = new ArrayList<String>();
                do {
                    consume(TokenType.IDENT, "Expected import name");
                    names.add(previous.text());
                } while (match(TokenType.COMMA));
                consume(TokenType.RBRACE, "Expected '}' after import names");
                consume(TokenType.SEMICOLON, "Expected ';' after import declaration");
                var modulePath = String.join(".", pathParts);
                return new ImportDecl(modulePath, names, false, span(start, previous.span().endByte()));
            } else if (check(TokenType.STAR)) {
                advance();
                diagnostics.add(Diagnostic.error(
                    "NGJ-PARSE-001",
                    "Wildcard imports are not supported",
                    previous.span()
                ));
                consume(TokenType.SEMICOLON, "Expected ';' after import declaration");
                var modulePath = String.join(".", pathParts);
                return new ImportDecl(modulePath, List.of(), true, span(start, previous.span().endByte()));
            } else {
                consumeIdentOrKeyword("Expected identifier after '.'");
                pathParts.add(previous.text());
            }
        }
        
        consume(TokenType.SEMICOLON, "Expected ';' after import declaration");
        
        if (pathParts.size() > 1) {
            String name = pathParts.remove(pathParts.size() - 1);
            var modulePath = String.join(".", pathParts);
            return new ImportDecl(modulePath, List.of(name), false, span(start, previous.span().endByte()));
        }
        
        return new ImportDecl("", pathParts, false, span(start, previous.span().endByte()));
    }
    
    private TopLevelDecl parseTopLevelDecl() throws ParseException {
        boolean isPublic = false;
        
        if (match(TokenType.PUB)) {
            diagnostics.add(Diagnostic.error(
                "NGJ-MIGRATE-003",
                "v0.2 syntax 'pub' is no longer supported. Use 'public' instead.",
                previous.span()
            ));
            isPublic = true;
        } else if (match(TokenType.PUBLIC)) {
            isPublic = true;
        }
        
        if (check(TokenType.FN)) {
            return parseFnDecl(isPublic);
        } else if (check(TokenType.RECORD)) {
            return parseRecordDecl(isPublic);
        } else if (check(TokenType.DATA)) {
            return parseDataDeclLegacy(isPublic);
        } else if (check(TokenType.ENUM)) {
            return parseEnumDecl(isPublic);
        }
        
        throw error("NGJ-PARSE-002", "Expected function, record, or enum declaration", current.span());
    }
    
    private FnDecl parseFnDecl(boolean isPublic) throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.FN, "Expected 'fn'");
        
        consume(TokenType.IDENT, "Expected function name");
        String name = previous.text();
        
        consume(TokenType.LPAREN, "Expected '(' after function name");
        var params = new ArrayList<Param>();
        if (!check(TokenType.RPAREN)) {
            do {
                params.add(parseParam());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RPAREN, "Expected ')' after parameters");
        
        consume(TokenType.ARROW, "Expected '->' before return type");
        var returnType = parseTypeExpr();
        
        var effects = parseEffectBound();
        
        var body = parseBlock();
        
        return new FnDecl(name, isPublic, params, returnType, effects, body,
            span(start, previous.span().endByte()));
    }
    
    private Param parseParam() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.IDENT, "Expected parameter name");
        String name = previous.text();
        
        consume(TokenType.COLON, "Expected ':' after parameter name");
        var type = parseTypeExpr();
        
        return new Param(name, type, span(start, previous.span().endByte()));
    }
    
    private List<String> parseEffectBound() throws ParseException {
        if (check(TokenType.BANG)) {
            advance();
            diagnostics.add(Diagnostic.error(
                "NGJ-MIGRATE-004",
                "v0.2 syntax '! {}' is no longer supported. Use 'effects()' instead.",
                previous.span()
            ));
            consume(TokenType.LBRACE, "Expected '{' after '!'");
            var effects = new ArrayList<String>();
            if (!check(TokenType.RBRACE)) {
                do {
                    consume(TokenType.IDENT, "Expected effect name");
                    effects.add(previous.text());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RBRACE, "Expected '}' after effect list");
            return effects;
        }
        
        consume(TokenType.EFFECTS, "Expected 'effects' for effect bound");
        consume(TokenType.LPAREN, "Expected '(' after 'effects'");
        
        var effects = new ArrayList<String>();
        if (!check(TokenType.RPAREN)) {
            do {
                consume(TokenType.IDENT, "Expected effect name");
                String effect = previous.text();
                effects.add(effect);
            } while (match(TokenType.COMMA));
        }
        
        consume(TokenType.RPAREN, "Expected ')' after effect list");
        
        if (!effects.isEmpty()) {
            diagnostics.add(Diagnostic.error(
                "NGJ-EFFECT-001",
                "Effects other than 'effects()' are not supported in MVP",
                previous.span()
            ));
        }
        
        return effects;
    }
    
    private RecordDecl parseRecordDecl(boolean isPublic) throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.RECORD, "Expected 'record'");
        
        consume(TokenType.IDENT, "Expected type name");
        String name = previous.text();
        
        consume(TokenType.LPAREN, "Expected '(' after record name");
        var params = new ArrayList<Param>();
        if (!check(TokenType.RPAREN)) {
            do {
                params.add(parseParam());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RPAREN, "Expected ')' after record fields");
        consume(TokenType.SEMICOLON, "Expected ';' after record declaration");
        
        return new RecordDecl(name, isPublic, params, span(start, previous.span().endByte()));
    }
    
    private DataDecl parseDataDeclLegacy(boolean isPublic) throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.DATA, "Expected 'data'");
        
        diagnostics.add(Diagnostic.error(
            "NGJ-MIGRATE-005",
            "v0.2 syntax 'data' is no longer supported. Use 'record Name(field: Type);' instead.",
            previous.span()
        ));
        
        consume(TokenType.IDENT, "Expected type name");
        String name = previous.text();
        
        if (check(TokenType.LANGLE)) {
            diagnostics.add(Diagnostic.error(
                "NGJ-PARSE-003",
                "Generic type parameters on data types are not supported in MVP",
                current.span()
            ));
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
            match(TokenType.COMMA);
        }
        
        consume(TokenType.RBRACE, "Expected '}' after data fields");
        
        return new DataDecl(name, isPublic, fields, span(start, previous.span().endByte()));
    }
    
    private FieldDecl parseFieldDecl() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.IDENT, "Expected field name");
        String name = previous.text();
        
        consume(TokenType.COLON, "Expected ':' after field name");
        var type = parseTypeExpr();
        
        return new FieldDecl(name, type, span(start, previous.span().endByte()));
    }
    
    private EnumDecl parseEnumDecl(boolean isPublic) throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.ENUM, "Expected 'enum'");
        
        consume(TokenType.IDENT, "Expected type name");
        String name = previous.text();
        
        // GENERIC-01: Support generic enum type parameters (e.g., enum Option<T>)
        var typeParams = new ArrayList<String>();
        if (match(TokenType.LANGLE)) {
            do {
                consume(TokenType.IDENT, "Expected type parameter name");
                typeParams.add(previous.text());
            } while (match(TokenType.COMMA));
            consume(TokenType.RANGLE, "Expected '>' after type parameters");
        }
        
        consume(TokenType.LBRACE, "Expected '{' after enum name");
        
        var variants = new ArrayList<Variant>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            variants.add(parseVariant());
            match(TokenType.COMMA);
        }
        
        consume(TokenType.RBRACE, "Expected '}' after enum variants");
        
        return new EnumDecl(name, isPublic, typeParams, variants, span(start, previous.span().endByte()));
    }
    
    /**
     * Parse enum variant with optional payload fields (ADT syntax).
     * 
     * EBNF (per spec): variant = IDENT "(" [ parameters ] ")" ;
     * 
     * Examples:
     *   None()                      // payload-less variant
     *   Some(value: T)              // variant with payload
     *   Circle(radius: Float)       // variant with named field
     */
    private Variant parseVariant() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.IDENT, "Expected variant name");
        String name = previous.text();
        
        var fields = new ArrayList<FieldDecl>();
        
        // ENUM-01: Variants always have parentheses per EBNF spec
        consume(TokenType.LPAREN, "Expected '(' after variant name - variants are written '" + name + "()'");
        if (!check(TokenType.RPAREN)) {
            do {
                fields.add(parseFieldDecl());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RPAREN, "Expected ')' after variant fields");
        
        return new Variant(name, fields, span(start, previous.span().endByte()));
    }
    
    private TypeExpr parseTypeExpr() throws ParseException {
        int start = current.span().startByte();
        
        if (match(TokenType.UNIT)) {
            return new UnitTypeExpr(span(start, previous.span().endByte()));
        }
        
        consume(TokenType.IDENT, "Expected type name");
        String name = previous.text();
        
        if (match(TokenType.LANGLE)) {
            var typeArgs = new ArrayList<TypeExpr>();
            do {
                typeArgs.add(parseTypeExpr());
            } while (match(TokenType.COMMA));
            
            consume(TokenType.RANGLE, "Expected '>' after type arguments");
            
            if (!name.equals("Result") && !name.equals("Option") && !name.equals("List")) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-PARSE-004",
                    "Generic types other than Result<T,E>, Option<T>, and List<T> are not supported in MVP",
                    span(start, previous.span().endByte())
                ));
            }
            
            return new GenericTypeExpr(name, typeArgs, span(start, previous.span().endByte()));
        }
        
        return new SimpleTypeExpr(name, span(start, previous.span().endByte()));
    }
    
    private BlockExpr parseBlock() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.LBRACE, "Expected '{'");
        
        var statements = new ArrayList<Statement>();
        Expr tailExpr = null;
        
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (check(TokenType.VAL)) {
                statements.add(parseValStmt());
            } else if (check(TokenType.VAR)) {
                statements.add(parseVarStmt());
            } else if (check(TokenType.LET)) {
                statements.add(parseLetStmtLegacy());
            } else if (check(TokenType.RETURN)) {
                statements.add(parseReturnStmtLegacy());
            } else if (check(TokenType.IDENT) && peekNext().type() == TokenType.ASSIGN) {
                statements.add(parseAssignStmt());
            } else {
                var expr = parseExpr();
                
                if (check(TokenType.SEMICOLON)) {
                    advance();
                    statements.add(new ExprStmt(expr, expr.span()));
                } else if (check(TokenType.RBRACE)) {
                    tailExpr = expr;
                } else {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-PARSE-008",
                        "Expected ';' after expression statement or '}' for tail expression",
                        current.span()
                    ));
                    statements.add(new ExprStmt(expr, expr.span()));
                }
            }
        }
        
        consume(TokenType.RBRACE, "Expected '}'");
        
        return new BlockExpr(statements, tailExpr, span(start, previous.span().endByte()));
    }
    
    private Token peekedToken = null;
    
    private Token peekNext() {
        if (peekedToken == null) {
            peekedToken = lexer.nextToken();
        }
        return peekedToken;
    }
    
    private Token advanceWithPeek() {
        previous = current;
        if (peekedToken != null) {
            current = peekedToken;
            peekedToken = null;
        } else {
            current = lexer.nextToken();
        }
        return previous;
    }
    
    private ValStmt parseValStmt() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.VAL, "Expected 'val'");
        
        consume(TokenType.IDENT, "Expected variable name");
        String name = previous.text();
        
        TypeExpr explicitType = null;
        if (match(TokenType.COLON)) {
            explicitType = parseTypeExpr();
        }
        
        consume(TokenType.ASSIGN, "Expected '=' after variable name");
        var initializer = parseExpr();
        consume(TokenType.SEMICOLON, "Expected ';' after val binding");
        
        return new ValStmt(name, explicitType, initializer, span(start, previous.span().endByte()));
    }
    
    private VarStmt parseVarStmt() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.VAR, "Expected 'var'");
        
        consume(TokenType.IDENT, "Expected variable name");
        String name = previous.text();
        
        TypeExpr explicitType = null;
        if (match(TokenType.COLON)) {
            explicitType = parseTypeExpr();
        }
        
        consume(TokenType.ASSIGN, "Expected '=' after variable name");
        var initializer = parseExpr();
        consume(TokenType.SEMICOLON, "Expected ';' after var binding");
        
        return new VarStmt(name, explicitType, initializer, span(start, previous.span().endByte()));
    }
    
    private AssignStmt parseAssignStmt() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.IDENT, "Expected variable name");
        String name = previous.text();
        
        consume(TokenType.ASSIGN, "Expected '='");
        var value = parseExpr();
        consume(TokenType.SEMICOLON, "Expected ';' after assignment");
        
        return new AssignStmt(name, value, span(start, previous.span().endByte()));
    }
    
    private LetStmt parseLetStmtLegacy() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.LET, "Expected 'let'");
        
        diagnostics.add(Diagnostic.error(
            "NGJ-MIGRATE-006",
            "v0.2 syntax 'let' is no longer supported. Use 'val' for immutable or 'var' for mutable bindings.",
            previous.span()
        ));
        
        boolean isMutable = match(TokenType.MUT);
        
        consume(TokenType.IDENT, "Expected variable name");
        String name = previous.text();
        
        TypeExpr explicitType = null;
        if (match(TokenType.COLON)) {
            explicitType = parseTypeExpr();
        }
        
        consume(TokenType.ASSIGN, "Expected '=' after variable name");
        var initializer = parseExpr();
        
        if (!check(TokenType.RBRACE)) {
            if (!match(TokenType.SEMICOLON)) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-PARSE-008",
                    "Expected ';' after binding statement",
                    current.span()
                ));
            }
        }
        
        return new LetStmt(name, explicitType, initializer, span(start, previous.span().endByte()));
    }
    
    private ReturnStmt parseReturnStmtLegacy() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.RETURN, "Expected 'return'");
        
        diagnostics.add(Diagnostic.error(
            "NGJ-MIGRATE-007",
            "v0.2 syntax 'return' is no longer supported. Use tail expressions instead.",
            previous.span()
        ));
        
        Expr value = null;
        if (!check(TokenType.RBRACE) && !check(TokenType.SEMICOLON)) {
            value = parseExpr();
        }
        
        return new ReturnStmt(value, span(start, previous.span().endByte()));
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
                span(start, right.span().endByte()));
        }
        
        return parsePropagateOpt(expr);
    }
    
    private Expr parseAnd() throws ParseException {
        var expr = parseComparison();
        
        while (match(TokenType.AND)) {
            int start = expr.span().startByte();
            var right = parseComparison();
            expr = new BinaryExpr(expr, BinaryExpr.BinaryOp.AND, right,
                span(start, right.span().endByte()));
        }
        
        return expr;
    }
    
    private Expr parseComparison() throws ParseException {
        var expr = parseAddition();
        
        if (match(TokenType.EQ)) {
            var right = parseAddition();
            return new BinaryExpr(expr, BinaryExpr.BinaryOp.EQ, right,
                span(expr.span().startByte(), right.span().endByte()));
        } else if (match(TokenType.NE)) {
            var right = parseAddition();
            return new BinaryExpr(expr, BinaryExpr.BinaryOp.NE, right,
                span(expr.span().startByte(), right.span().endByte()));
        } else if (match(TokenType.LE)) {
            var right = parseAddition();
            return new BinaryExpr(expr, BinaryExpr.BinaryOp.LE, right,
                span(expr.span().startByte(), right.span().endByte()));
        } else if (match(TokenType.GE)) {
            var right = parseAddition();
            return new BinaryExpr(expr, BinaryExpr.BinaryOp.GE, right,
                span(expr.span().startByte(), right.span().endByte()));
        } else if (check(TokenType.LANGLE)) {
            int saved = current.span().startByte();
            advance();
            if (!isTypeContext()) {
                var right = parseAddition();
                return new BinaryExpr(expr, BinaryExpr.BinaryOp.LT, right,
                    span(expr.span().startByte(), right.span().endByte()));
            }
        } else if (check(TokenType.RANGLE)) {
            advance();
            var right = parseAddition();
            return new BinaryExpr(expr, BinaryExpr.BinaryOp.GT, right,
                span(expr.span().startByte(), right.span().endByte()));
        }
        
        return expr;
    }
    
    private boolean isTypeContext() {
        return false;
    }
    
    private Expr parseAddition() throws ParseException {
        var expr = parseMultiplication();
        
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            var op = previous.type() == TokenType.PLUS ? 
                BinaryExpr.BinaryOp.ADD : BinaryExpr.BinaryOp.SUB;
            var right = parseMultiplication();
            expr = new BinaryExpr(expr, op, right,
                span(expr.span().startByte(), right.span().endByte()));
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
                span(expr.span().startByte(), right.span().endByte()));
        }
        
        return expr;
    }
    
    private Expr parseUnary() throws ParseException {
        if (match(TokenType.MINUS)) {
            int start = previous.span().startByte();
            var operand = parseUnary();
            return new UnaryExpr(UnaryExpr.UnaryOp.NEG, operand,
                span(start, operand.span().endByte()));
        }
        if (match(TokenType.BANG)) {
            int start = previous.span().startByte();
            var operand = parseUnary();
            return new UnaryExpr(UnaryExpr.UnaryOp.NOT, operand,
                span(start, operand.span().endByte()));
        }
        
        return parsePostfix();
    }
    
    private Expr parsePostfix() throws ParseException {
        var expr = parsePrimary();
        
        while (true) {
            if (match(TokenType.DOT)) {
                consume(TokenType.IDENT, "Expected field name after '.'");
                expr = new FieldAccessExpr(expr, previous.text(),
                    span(expr.span().startByte(), previous.span().endByte()));
            } else if (match(TokenType.LPAREN)) {
                var args = new ArrayList<Expr>();
                if (!check(TokenType.RPAREN)) {
                    do {
                        args.add(parseExpr());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RPAREN, "Expected ')' after arguments");
                expr = new CallExpr(expr, args,
                    span(expr.span().startByte(), previous.span().endByte()));
            } else if (match(TokenType.DOUBLE_COLON)) {
                consume(TokenType.LANGLE, "Expected '<' after '::'");
                var typeArgs = new ArrayList<TypeExpr>();
                do {
                    typeArgs.add(parseTypeExpr());
                } while (match(TokenType.COMMA));
                consume(TokenType.RANGLE, "Expected '>' after type arguments");
                consume(TokenType.LPAREN, "Expected '(' after type arguments");
                var args = new ArrayList<Expr>();
                if (!check(TokenType.RPAREN)) {
                    do {
                        args.add(parseExpr());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RPAREN, "Expected ')' after arguments");
                diagnostics.add(Diagnostic.error(
                    "NGJ-UNSUPPORTED-001",
                    "Explicit type argument calls f::<T>(x) are not supported in MVP",
                    span(expr.span().startByte(), previous.span().endByte())
                ));
                expr = new CallExpr(expr, args,
                    span(expr.span().startByte(), previous.span().endByte()));
            } else if (match(TokenType.QUESTION)) {
                expr = new PropagateExpr(expr,
                    span(expr.span().startByte(), previous.span().endByte()));
            } else {
                break;
            }
        }
        
        return expr;
    }
    
    private Expr parsePropagateOpt(Expr expr) throws ParseException {
        return expr;
    }
    
    private Expr parsePrimary() throws ParseException {
        int start = current.span().startByte();
        
        // NULL-01: Reject null literal - NGJ has no null in core language
        if (match(TokenType.NULL)) {
            diagnostics.add(Diagnostic.error(
                "NGJ-NULL-001",
                "null literal is not allowed in NGJ. Use Option<T> with None() for absence.",
                previous.span()
            ));
            return new IdentExpr("__null_error__", span(start, previous.span().endByte()));
        }
        
        if (match(TokenType.INT_LITERAL)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.INT, previous.value(),
                span(start, previous.span().endByte()));
        }
        if (match(TokenType.LONG_LITERAL)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.LONG, previous.value(),
                span(start, previous.span().endByte()));
        }
        if (match(TokenType.STRING_LITERAL)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.STRING, previous.value(),
                span(start, previous.span().endByte()));
        }
        if (match(TokenType.TRUE)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.BOOL, true,
                span(start, previous.span().endByte()));
        }
        if (match(TokenType.FALSE)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.BOOL, false,
                span(start, previous.span().endByte()));
        }
        if (match(TokenType.UNIT)) {
            return new LiteralExpr(LiteralExpr.LiteralKind.UNIT, null,
                span(start, previous.span().endByte()));
        }
        
        if (match(TokenType.HOLE)) {
            return new HoleExpr((String) previous.value(),
                span(start, previous.span().endByte()));
        }
        
        if (match(TokenType.IF)) {
            return parseIfExpr(start);
        }
        
        if (match(TokenType.MATCH)) {
            return parseMatchExpr(start);
        }
        
        if (match(TokenType.FOR)) {
            return parseForExpr(start);
        }
        
        if (match(TokenType.WHILE)) {
            return parseWhileExpr(start);
        }
        
        if (check(TokenType.LBRACE)) {
            return parseBlock();
        }
        
        if (match(TokenType.LPAREN)) {
            if (match(TokenType.RPAREN)) {
                return new LiteralExpr(LiteralExpr.LiteralKind.UNIT, null,
                    span(start, previous.span().endByte()));
            }
            
            if (check(TokenType.IDENT) && peekNextIsColonOrCommaOrRparen()) {
                return parseLambdaExpr(start);
            }
            
            var expr = parseExpr();
            consume(TokenType.RPAREN, "Expected ')' after expression");
            return new ParenExpr(expr, span(start, previous.span().endByte()));
        }
        
        if (match(TokenType.IDENT)) {
            String name = previous.text();
            
            if (check(TokenType.LBRACE) && Character.isUpperCase(name.charAt(0))) {
                return parseConstructorExprLegacy(name, start);
            }
            
            return new IdentExpr(name, span(start, previous.span().endByte()));
        }
        
        throw error("NGJ-PARSE-005", "Unexpected token: " + current.type(), current.span());
    }
    
    private boolean peekNextIsColonOrCommaOrRparen() {
        Token next = peekNext();
        return next.type() == TokenType.COLON || 
               next.type() == TokenType.COMMA ||
               next.type() == TokenType.RPAREN;
    }
    
    private Expr parseIfExpr(int start) throws ParseException {
        consume(TokenType.LPAREN, "Expected '(' after 'if'");
        var condition = parseExpr();
        consume(TokenType.RPAREN, "Expected ')' after if condition");
        
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
            span(start, previous.span().endByte()));
    }
    
    private Expr parseMatchExpr(int start) throws ParseException {
        consume(TokenType.LPAREN, "Expected '(' after 'match'");
        var scrutinee = parseExpr();
        consume(TokenType.RPAREN, "Expected ')' after match expression");
        
        consume(TokenType.LBRACE, "Expected '{' after match");
        
        var arms = new ArrayList<MatchExpr.MatchArm>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            arms.add(parseMatchArm());
            match(TokenType.COMMA);
        }
        
        consume(TokenType.RBRACE, "Expected '}' after match arms");
        
        return new MatchExpr(scrutinee, arms, span(start, previous.span().endByte()));
    }
    
    private MatchExpr.MatchArm parseMatchArm() throws ParseException {
        int start = current.span().startByte();
        consume(TokenType.CASE, "Expected 'case' at start of match arm");
        
        var pattern = parsePattern();
        
        consume(TokenType.FAT_ARROW, "Expected '=>' after pattern");
        
        var body = parseBlock();
        
        return new MatchExpr.MatchArm(pattern, body, span(start, previous.span().endByte()));
    }
    
    private Pattern parsePattern() throws ParseException {
        int start = current.span().startByte();
        
        if (current.text().equals("_")) {
            advance();
            return new WildcardPattern(span(start, previous.span().endByte()));
        }
        
        if (match(TokenType.MINUS)) {
            if (match(TokenType.INT_LITERAL)) {
                int value = -((Integer) previous.value());
                var literal = new LiteralExpr(LiteralExpr.LiteralKind.INT, value, previous.span());
                return new LiteralPattern(literal, span(start, previous.span().endByte()));
            } else if (match(TokenType.LONG_LITERAL)) {
                long value = -((Long) previous.value());
                var literal = new LiteralExpr(LiteralExpr.LiteralKind.LONG, value, previous.span());
                return new LiteralPattern(literal, span(start, previous.span().endByte()));
            }
            throw error("NGJ-PARSE-009", "Expected number after '-' in pattern", current.span());
        }
        
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
            return new LiteralPattern(literal, span(start, previous.span().endByte()));
        }
        
        if (match(TokenType.IDENT)) {
            String name = previous.text();
            var typePath = new StringBuilder(name);
            
            while (match(TokenType.DOT)) {
                consume(TokenType.IDENT, "Expected identifier after '.'");
                typePath.append(".").append(previous.text());
            }
            
            if (match(TokenType.LPAREN)) {
                var patterns = new ArrayList<Pattern>();
                if (!check(TokenType.RPAREN)) {
                    do {
                        patterns.add(parsePattern());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RPAREN, "Expected ')' after constructor patterns");
                
                var fields = new ArrayList<ConstructorPattern.PatternField>();
                for (int i = 0; i < patterns.size(); i++) {
                    fields.add(new ConstructorPattern.PatternField(
                        "field" + i, patterns.get(i), patterns.get(i).span()));
                }
                
                return new ConstructorPattern(typePath.toString(), fields, span(start, previous.span().endByte()));
            }
            
            if (Character.isUpperCase(name.charAt(0))) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-MIGRATE-008",
                    "v0.3 requires parentheses for all constructor patterns: '" + name + "()'",
                    span(start, previous.span().endByte())
                ));
                return new ConstructorPattern(name, List.of(), span(start, previous.span().endByte()));
            }
            
            return new BindingPattern(name, span(start, previous.span().endByte()));
        }
        
        throw error("NGJ-PARSE-006", "Expected pattern", current.span());
    }
    
    private Expr parseForExpr(int start) throws ParseException {
        consume(TokenType.LPAREN, "Expected '(' after 'for'");
        consume(TokenType.IDENT, "Expected loop variable name");
        String varName = previous.text();
        consume(TokenType.LEFT_ARROW, "Expected '<-' in for expression");
        var iterable = parseExpr();
        consume(TokenType.RPAREN, "Expected ')' after for generator");
        
        boolean hasYield = match(TokenType.YIELD);
        
        var body = parseBlock();
        
        if (!check(TokenType.SEMICOLON) && !check(TokenType.RBRACE)) {
            diagnostics.add(Diagnostic.error(
                "NGJ-PARSE-010",
                "Expected ';' after for expression when used as statement",
                current.span()
            ));
        }
        
        return new ForExpr(varName, iterable, hasYield, body, span(start, previous.span().endByte()));
    }
    
    private Expr parseWhileExpr(int start) throws ParseException {
        consume(TokenType.LPAREN, "Expected '(' after 'while'");
        var condition = parseExpr();
        consume(TokenType.RPAREN, "Expected ')' after while condition");
        
        var body = parseBlock();
        
        if (!check(TokenType.SEMICOLON) && !check(TokenType.RBRACE)) {
            diagnostics.add(Diagnostic.error(
                "NGJ-PARSE-010",
                "Expected ';' after while expression when used as statement",
                current.span()
            ));
        }
        
        return new WhileExpr(condition, body, span(start, previous.span().endByte()));
    }
    
    private Expr parseLambdaExpr(int start) throws ParseException {
        var params = new ArrayList<Param>();
        
        if (!check(TokenType.RPAREN)) {
            do {
                int paramStart = current.span().startByte();
                consume(TokenType.IDENT, "Expected parameter name");
                String name = previous.text();
                consume(TokenType.COLON, "Expected ':' after lambda parameter name");
                var type = parseTypeExpr();
                params.add(new Param(name, type, span(paramStart, previous.span().endByte())));
            } while (match(TokenType.COMMA));
        }
        
        consume(TokenType.RPAREN, "Expected ')' after lambda parameters");
        consume(TokenType.FAT_ARROW, "Expected '=>' after lambda parameters");
        
        var body = parseBlock();
        
        return new LambdaExpr(params, body, span(start, previous.span().endByte()));
    }
    
    private Expr parseConstructorExprLegacy(String typePath, int start) throws ParseException {
        consume(TokenType.LBRACE, "Expected '{' for constructor");
        
        diagnostics.add(Diagnostic.error(
            "NGJ-MIGRATE-009",
            "v0.2 syntax 'Name { field: value }' is no longer supported. Use 'Name(value)' instead.",
            span(start, previous.span().endByte())
        ));
        
        var fields = new ArrayList<ConstructorExpr.FieldInit>();
        if (!check(TokenType.RBRACE)) {
            do {
                int fieldStart = current.span().startByte();
                consume(TokenType.IDENT, "Expected field name");
                String fieldName = previous.text();
                
                consume(TokenType.COLON, "Expected ':' after field name");
                var value = parseExpr();
                
                fields.add(new ConstructorExpr.FieldInit(fieldName, value,
                    span(fieldStart, previous.span().endByte())));
            } while (match(TokenType.COMMA));
        }
        
        consume(TokenType.RBRACE, "Expected '}' after constructor fields");
        
        return new ConstructorExpr(typePath, fields, span(start, previous.span().endByte()));
    }
    
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
        if (peekedToken != null) {
            current = peekedToken;
            peekedToken = null;
        } else {
            current = lexer.nextToken();
        }
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
    
    private SourceSpan span(int start, int end) {
        return new SourceSpan(fileName, start, end);
    }
}
