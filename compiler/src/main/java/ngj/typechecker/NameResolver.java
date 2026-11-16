package ngj.typechecker;

import ngj.ast.*;
import ngj.diagnostic.*;

import java.util.*;

/**
 * Name resolution pass: resolves identifiers to their declarations.
 */
public final class NameResolver {
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, Symbol> globalSymbols = new HashMap<>();
    private final Deque<Map<String, Symbol>> scopes = new ArrayDeque<>();
    private String currentModule;
    
    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }
    
    public void resolve(CompilationUnit unit) {
        currentModule = unit.modulePath();
        
        // Register built-in types
        registerBuiltins();
        
        // First pass: register all top-level declarations
        for (var decl : unit.declarations()) {
            registerTopLevel(decl);
        }
        
        // Second pass: resolve references in bodies
        for (var decl : unit.declarations()) {
            resolveDecl(decl);
        }
    }
    
    private void registerBuiltins() {
        // Built-in types
        globalSymbols.put("Int", new Symbol("Int", Symbol.Kind.TYPE, true));
        globalSymbols.put("Long", new Symbol("Long", Symbol.Kind.TYPE, true));
        globalSymbols.put("Bool", new Symbol("Bool", Symbol.Kind.TYPE, true));
        globalSymbols.put("String", new Symbol("String", Symbol.Kind.TYPE, true));
        globalSymbols.put("Unit", new Symbol("Unit", Symbol.Kind.TYPE, true));
        globalSymbols.put("Result", new Symbol("Result", Symbol.Kind.TYPE, true));
        globalSymbols.put("Option", new Symbol("Option", Symbol.Kind.TYPE, true));
        
        // Built-in constructors
        globalSymbols.put("Ok", new Symbol("Ok", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("Err", new Symbol("Err", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("Some", new Symbol("Some", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("None", new Symbol("None", Symbol.Kind.CONSTRUCTOR, true));
    }
    
    private void registerTopLevel(TopLevelDecl decl) {
        switch (decl) {
            case FnDecl fn -> {
                String id = currentModule + "." + fn.name();
                globalSymbols.put(fn.name(), new Symbol(id, Symbol.Kind.FUNCTION, fn.isPublic()));
            }
            case DataDecl data -> {
                String id = currentModule + "." + data.name();
                globalSymbols.put(data.name(), new Symbol(id, Symbol.Kind.TYPE, data.isPublic()));
            }
            case EnumDecl enumDecl -> {
                String id = currentModule + "." + enumDecl.name();
                globalSymbols.put(enumDecl.name(), new Symbol(id, Symbol.Kind.TYPE, enumDecl.isPublic()));
                for (var variant : enumDecl.variants()) {
                    String variantId = id + "." + variant.name();
                    globalSymbols.put(variant.name(), new Symbol(variantId, Symbol.Kind.CONSTRUCTOR, enumDecl.isPublic()));
                }
            }
        }
    }
    
    private void resolveDecl(TopLevelDecl decl) {
        switch (decl) {
            case FnDecl fn -> resolveFn(fn);
            case DataDecl data -> resolveData(data);
            case EnumDecl enumDecl -> resolveEnum(enumDecl);
        }
    }
    
    private void resolveFn(FnDecl fn) {
        pushScope();
        
        // Add parameters to scope
        for (var param : fn.params()) {
            resolveType(param.type());
            addLocal(param.name(), new Symbol(param.name(), Symbol.Kind.VARIABLE, false));
        }
        
        // Resolve return type
        resolveType(fn.returnType());
        
        // Resolve body
        resolveBlock(fn.body());
        
        popScope();
    }
    
    private void resolveData(DataDecl data) {
        for (var field : data.fields()) {
            resolveType(field.type());
        }
    }
    
    private void resolveEnum(EnumDecl enumDecl) {
        for (var variant : enumDecl.variants()) {
            for (var field : variant.fields()) {
                resolveType(field.type());
            }
        }
    }
    
    private void resolveType(TypeExpr type) {
        switch (type) {
            case SimpleTypeExpr simple -> {
                if (!globalSymbols.containsKey(simple.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-NAME-001",
                        "Unknown type: " + simple.name(),
                        simple.span()
                    ));
                }
            }
            case GenericTypeExpr generic -> {
                if (!globalSymbols.containsKey(generic.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-NAME-001",
                        "Unknown type: " + generic.name(),
                        generic.span()
                    ));
                }
                for (var arg : generic.typeArgs()) {
                    resolveType(arg);
                }
            }
            case UnitTypeExpr _ -> {} // Built-in
        }
    }
    
    private void resolveBlock(BlockExpr block) {
        pushScope();
        
        for (var stmt : block.statements()) {
            resolveStmt(stmt);
        }
        
        if (block.tailExpr() != null) {
            resolveExpr(block.tailExpr());
        }
        
        popScope();
    }
    
    private void resolveStmt(Statement stmt) {
        switch (stmt) {
            case LetStmt let -> {
                if (let.explicitType() != null) {
                    resolveType(let.explicitType());
                }
                resolveExpr(let.initializer());
                addLocal(let.name(), new Symbol(let.name(), Symbol.Kind.VARIABLE, false));
            }
            case ExprStmt expr -> resolveExpr(expr.expr());
            case ReturnStmt ret -> {
                if (ret.value() != null) {
                    resolveExpr(ret.value());
                }
            }
        }
    }
    
    private void resolveExpr(Expr expr) {
        switch (expr) {
            case LiteralExpr _ -> {}
            case IdentExpr ident -> {
                if (!lookupSymbol(ident.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-NAME-002",
                        "Unknown identifier: " + ident.name(),
                        ident.span()
                    ));
                }
            }
            case HoleExpr _ -> {} // Holes are allowed
            case BinaryExpr binary -> {
                resolveExpr(binary.left());
                resolveExpr(binary.right());
            }
            case UnaryExpr unary -> resolveExpr(unary.operand());
            case CallExpr call -> {
                resolveExpr(call.callee());
                for (var arg : call.args()) {
                    resolveExpr(arg);
                }
            }
            case FieldAccessExpr access -> resolveExpr(access.object());
            case ConstructorExpr ctor -> {
                if (!globalSymbols.containsKey(ctor.typePath())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-NAME-003",
                        "Unknown type: " + ctor.typePath(),
                        ctor.span()
                    ));
                }
                for (var field : ctor.fields()) {
                    resolveExpr(field.value());
                }
            }
            case IfExpr ifExpr -> {
                resolveExpr(ifExpr.condition());
                resolveBlock(ifExpr.thenBranch());
                if (ifExpr.elseBranch() != null) {
                    if (ifExpr.elseBranch() instanceof BlockExpr block) {
                        resolveBlock(block);
                    } else if (ifExpr.elseBranch() instanceof IfExpr nested) {
                        resolveExpr(nested);
                    }
                }
            }
            case MatchExpr matchExpr -> {
                resolveExpr(matchExpr.scrutinee());
                for (var arm : matchExpr.arms()) {
                    resolvePattern(arm.pattern());
                    pushScope();
                    bindPatternVars(arm.pattern());
                    resolveExpr(arm.body());
                    popScope();
                }
            }
            case BlockExpr block -> resolveBlock(block);
            case PropagateExpr prop -> resolveExpr(prop.inner());
            case ParenExpr paren -> resolveExpr(paren.inner());
        }
    }
    
    private void resolvePattern(Pattern pattern) {
        switch (pattern) {
            case WildcardPattern _ -> {}
            case LiteralPattern _ -> {}
            case BindingPattern _ -> {}
            case ConstructorPattern ctor -> {
                if (!globalSymbols.containsKey(ctor.typePath())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-NAME-003",
                        "Unknown constructor: " + ctor.typePath(),
                        ctor.span()
                    ));
                }
                for (var field : ctor.fields()) {
                    if (field.pattern() != null) {
                        resolvePattern(field.pattern());
                    }
                }
            }
        }
    }
    
    private void bindPatternVars(Pattern pattern) {
        switch (pattern) {
            case WildcardPattern _ -> {}
            case LiteralPattern _ -> {}
            case BindingPattern binding -> 
                addLocal(binding.name(), new Symbol(binding.name(), Symbol.Kind.VARIABLE, false));
            case ConstructorPattern ctor -> {
                for (var field : ctor.fields()) {
                    if (field.pattern() != null) {
                        bindPatternVars(field.pattern());
                    } else {
                        addLocal(field.name(), new Symbol(field.name(), Symbol.Kind.VARIABLE, false));
                    }
                }
            }
        }
    }
    
    private void pushScope() {
        scopes.push(new HashMap<>());
    }
    
    private void popScope() {
        scopes.pop();
    }
    
    private void addLocal(String name, Symbol symbol) {
        if (!scopes.isEmpty()) {
            scopes.peek().put(name, symbol);
        }
    }
    
    private boolean lookupSymbol(String name) {
        // Check local scopes first
        for (var scope : scopes) {
            if (scope.containsKey(name)) {
                return true;
            }
        }
        // Check global scope
        return globalSymbols.containsKey(name);
    }
    
    public record Symbol(String id, Kind kind, boolean isPublic) {
        public enum Kind { TYPE, FUNCTION, CONSTRUCTOR, VARIABLE }
    }
}
