package ngj.typechecker;

import ngj.ast.*;
import ngj.diagnostic.*;
import ngj.HoleContext.Binding;

import java.util.*;
import java.util.Set;
import java.util.HashSet;

/**
 * Type checking pass: verifies type consistency and collects hole contexts.
 */
public final class TypeChecker {
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, TypeInfo> typeEnv = new HashMap<>();
    private final Map<String, FnDecl> fnEnv = new HashMap<>();
    private final Deque<Map<String, TypeExpr>> scopes = new ArrayDeque<>();
    private final Deque<Set<String>> mutableVars = new ArrayDeque<>();
    private TypeExpr currentReturnType;
    private TypeExpr currentExpectedType;
    private List<String> currentEffects;
    
    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }
    
    public void check(CompilationUnit unit) {
        checkAll(List.of(unit));
    }
    
    public void checkAll(List<CompilationUnit> units) {
        // Register built-in types
        registerBuiltins();
        
        // First pass: register type and function declarations across all units
        for (var unit : units) {
            for (var decl : unit.declarations()) {
                registerDecl(decl);
            }
        }
        
        // Second pass: type check bodies
        for (var unit : units) {
            for (var decl : unit.declarations()) {
                checkDecl(decl);
            }
        }
    }
    
    private void registerBuiltins() {
        // VALUE-03: All numeric types are value types with structural equality
        // No primitive/boxed distinction - Int/Long/Bool behave as value types
        typeEnv.put("Int", new TypeInfo(TypeInfo.Kind.VALUE_TYPE, List.of()));
        typeEnv.put("Long", new TypeInfo(TypeInfo.Kind.VALUE_TYPE, List.of()));
        typeEnv.put("Bool", new TypeInfo(TypeInfo.Kind.VALUE_TYPE, List.of()));
        typeEnv.put("Byte", new TypeInfo(TypeInfo.Kind.VALUE_TYPE, List.of()));
        typeEnv.put("Float", new TypeInfo(TypeInfo.Kind.VALUE_TYPE, List.of()));
        typeEnv.put("Double", new TypeInfo(TypeInfo.Kind.VALUE_TYPE, List.of()));
        typeEnv.put("String", new TypeInfo(TypeInfo.Kind.VALUE_TYPE, List.of()));
        typeEnv.put("Unit", new TypeInfo(TypeInfo.Kind.VALUE_TYPE, List.of()));
        
        // STDLIB-02/03: Option and Result are ordinary enums (value types)
        // with standard ADT variants
        var optionVariants = List.of(
            new VariantInfo("Some", List.of(new FieldInfo("value", null))),
            new VariantInfo("None", List.of())
        );
        typeEnv.put("Option", new TypeInfo(TypeInfo.Kind.GENERIC_ENUM, optionVariants));
        
        var resultVariants = List.of(
            new VariantInfo("Ok", List.of(new FieldInfo("value", null))),
            new VariantInfo("Err", List.of(new FieldInfo("error", null)))
        );
        typeEnv.put("Result", new TypeInfo(TypeInfo.Kind.GENERIC_ENUM, resultVariants));
        
        // STDLIB-04: List is a value type generic collection
        typeEnv.put("List", new TypeInfo(TypeInfo.Kind.GENERIC_VALUE, List.of("T")));
        
        // Standard error types
        var overflowVariants = List.of(
            new VariantInfo("IntOverflow", List.of()),
            new VariantInfo("LongOverflow", List.of())
        );
        typeEnv.put("OverflowError", new TypeInfo(TypeInfo.Kind.ENUM, overflowVariants));
        
        var rangeVariants = List.of(
            new VariantInfo("OutOfRange", List.of(new FieldInfo("message", new SimpleTypeExpr("String", null))))
        );
        typeEnv.put("RangeError", new TypeInfo(TypeInfo.Kind.ENUM, rangeVariants));
        
        var indexVariants = List.of(
            new VariantInfo("IndexOutOfBounds", List.of(
                new FieldInfo("index", new SimpleTypeExpr("Int", null)),
                new FieldInfo("length", new SimpleTypeExpr("Int", null))
            ))
        );
        typeEnv.put("IndexError", new TypeInfo(TypeInfo.Kind.ENUM, indexVariants));
        
        // Register standard library functions
        registerStdlibFunctions();
    }
    
    private void registerStdlibFunctions() {
        // STDLIB-07: Checked arithmetic operations that return Result
        stdlibFunctions.put("checkedAdd", new StdlibFn(
            List.of("Int", "Int"),
            new GenericTypeExpr("Result", List.of(
                new SimpleTypeExpr("Int", null),
                new SimpleTypeExpr("OverflowError", null)
            ), null)
        ));
        stdlibFunctions.put("checkedSub", new StdlibFn(
            List.of("Int", "Int"),
            new GenericTypeExpr("Result", List.of(
                new SimpleTypeExpr("Int", null),
                new SimpleTypeExpr("OverflowError", null)
            ), null)
        ));
        stdlibFunctions.put("checkedMul", new StdlibFn(
            List.of("Int", "Int"),
            new GenericTypeExpr("Result", List.of(
                new SimpleTypeExpr("Int", null),
                new SimpleTypeExpr("OverflowError", null)
            ), null)
        ));
        
        // STDLIB-08: List.get returns Option (not null)
        // Note: Method calls on List are handled specially in checkMethodCall
    }
    
    private final Map<String, StdlibFn> stdlibFunctions = new HashMap<>();
    
    public record StdlibFn(List<String> paramTypes, TypeExpr returnType) {}
    
    private void registerDecl(TopLevelDecl decl) {
        switch (decl) {
            case DataDecl data -> {
                var fields = data.fields().stream()
                    .map(f -> new FieldInfo(f.name(), f.type()))
                    .toList();
                typeEnv.put(data.name(), new TypeInfo(TypeInfo.Kind.DATA, fields));
            }
            case RecordDecl record -> {
                var fields = record.params().stream()
                    .map(p -> new FieldInfo(p.name(), p.type()))
                    .toList();
                typeEnv.put(record.name(), new TypeInfo(TypeInfo.Kind.DATA, fields));
            }
            case EnumDecl enumDecl -> {
                var variants = enumDecl.variants().stream()
                    .map(v -> new VariantInfo(v.name(), v.fields().stream()
                        .map(f -> new FieldInfo(f.name(), f.type()))
                        .toList()))
                    .toList();
                typeEnv.put(enumDecl.name(), new TypeInfo(TypeInfo.Kind.ENUM, variants));
            }
            case FnDecl fn -> fnEnv.put(fn.name(), fn);
        }
    }
    
    private void checkDecl(TopLevelDecl decl) {
        switch (decl) {
            case FnDecl fn -> checkFn(fn);
            case DataDecl data -> checkData(data);
            case RecordDecl record -> checkRecord(record);
            case EnumDecl enumDecl -> checkEnum(enumDecl);
        }
    }
    
    private void checkRecord(RecordDecl record) {
        for (var param : record.params()) {
            checkTypeExists(param.type());
        }
    }
    
    private void checkFn(FnDecl fn) {
        pushScope();
        currentReturnType = fn.returnType();
        currentExpectedType = fn.returnType();
        currentEffects = fn.effects();
        
        // Check effect bound (MVP only allows empty)
        if (!fn.effects().isEmpty()) {
            diagnostics.add(Diagnostic.error(
                "NGJ-EFFECT-001",
                "Non-empty effect bound not supported in MVP: effects(" + String.join(", ", fn.effects()) + ")",
                fn.span()
            ));
        }
        
        // Add parameters to scope
        for (var param : fn.params()) {
            addLocal(param.name(), param.type());
        }
        
        // Check body
        var bodyType = checkBlock(fn.body());
        boolean isUnitReturn = getTypeName(fn.returnType()).equals("Unit");
        
        if (!isUnitReturn) {
            if (fn.body().tailExpr() instanceof HoleExpr) {
                // Typed hole at function body level is incomplete, not a missing return error
            } else if (fn.body().tailExpr() != null) {
                if (!exprAlwaysReturns(fn.body().tailExpr())) {
                    if (bodyType != null && !isAssignable(fn.returnType(), bodyType)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-001",
                            "Return type mismatch",
                            fn.body().span(),
                            new ExpectedActual(fn.returnType().toString()),
                            new ExpectedActual(bodyType.toString())
                        ));
                    }
                    diagnostics.add(Diagnostic.error(
                        "NGJ-RETURN-001",
                        "Missing 'return' statement: function returning " + fn.returnType() + " must use 'return <expr>;'",
                        fn.body().tailExpr().span()
                    ));
                }
            } else if (!blockAlwaysReturns(fn.body())) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-RETURN-001",
                    "Missing 'return' statement on all control flow paths for function returning " + fn.returnType(),
                    fn.body().span()
                ));
            }
        } else if (fn.body().tailExpr() != null && !(fn.body().tailExpr() instanceof HoleExpr)) {
            if (bodyType != null && !isAssignable(fn.returnType(), bodyType)) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-TYPE-001",
                    "Return type mismatch",
                    fn.body().span(),
                    new ExpectedActual(fn.returnType().toString()),
                    new ExpectedActual(bodyType.toString())
                ));
            }
        }
        
        popScope();
        currentReturnType = null;
        currentExpectedType = null;
        currentEffects = null;
    }
    
    private boolean blockAlwaysReturns(BlockExpr block) {
        for (var stmt : block.statements()) {
            if (stmtAlwaysReturns(stmt)) {
                return true;
            }
        }
        if (block.tailExpr() != null) {
            return exprAlwaysReturns(block.tailExpr());
        }
        return false;
    }
    
    private boolean stmtAlwaysReturns(Statement stmt) {
        return switch (stmt) {
            case ReturnStmt _ -> true;
            case ExprStmt exprStmt -> exprAlwaysReturns(exprStmt.expr());
            default -> false;
        };
    }
    
    private boolean exprAlwaysReturns(Expr expr) {
        return switch (expr) {
            case HoleExpr _ -> true;
            case BlockExpr block -> blockAlwaysReturns(block);
            case IfExpr ifExpr -> ifExpr.elseBranch() != null
                && blockAlwaysReturns(ifExpr.thenBranch())
                && exprAlwaysReturns(ifExpr.elseBranch());
            case MatchExpr matchExpr -> !matchExpr.arms().isEmpty()
                && matchExpr.arms().stream().allMatch(arm -> exprAlwaysReturns(arm.body()));
            case ParenExpr paren -> exprAlwaysReturns(paren.inner());
            default -> false;
        };
    }
    
    private TypeExpr checkExprWithExpected(Expr expr, TypeExpr expected) {
        var prev = currentExpectedType;
        currentExpectedType = expected;
        try {
            return checkExpr(expr);
        } finally {
            currentExpectedType = prev;
        }
    }
    
    private void checkData(DataDecl data) {
        for (var field : data.fields()) {
            checkTypeExists(field.type());
        }
    }
    
    private void checkEnum(EnumDecl enumDecl) {
        for (var variant : enumDecl.variants()) {
            for (var field : variant.fields()) {
                checkTypeExists(field.type());
            }
        }
    }
    
    private TypeExpr checkBlock(BlockExpr block) {
        pushScope();
        var savedExpected = currentExpectedType;
        
        for (var stmt : block.statements()) {
            currentExpectedType = null;
            checkStmt(stmt);
        }
        
        currentExpectedType = savedExpected;
        TypeExpr result = null;
        if (block.tailExpr() != null) {
            result = checkExpr(block.tailExpr());
        } else if (!blockAlwaysReturns(block)) {
            result = new UnitTypeExpr(block.span());
        }
        
        popScope();
        return result;
    }
    
    private void checkStmt(Statement stmt) {
        switch (stmt) {
            case ValStmt val -> {
                var initType = checkExprWithExpected(val.initializer(), val.explicitType());
                
                TypeExpr varType;
                if (val.explicitType() != null) {
                    checkTypeExists(val.explicitType());
                    varType = val.explicitType();
                    
                    if (initType != null && !isAssignable(varType, initType)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-002",
                            "Type mismatch in val binding",
                            val.span(),
                            new ExpectedActual(varType.toString()),
                            new ExpectedActual(initType.toString())
                        ));
                    }
                } else {
                    varType = initType;
                    
                    if (varType == null && !(val.initializer() instanceof HoleExpr)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-003",
                            "Cannot infer type for val binding. Please add explicit type annotation.",
                            val.span()
                        ));
                    }
                }
                
                addLocal(val.name(), varType, false);
            }
            case VarStmt var -> {
                var initType = checkExprWithExpected(var.initializer(), var.explicitType());
                
                TypeExpr varType;
                if (var.explicitType() != null) {
                    checkTypeExists(var.explicitType());
                    varType = var.explicitType();
                    
                    if (initType != null && !isAssignable(varType, initType)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-002",
                            "Type mismatch in var binding",
                            var.span(),
                            new ExpectedActual(varType.toString()),
                            new ExpectedActual(initType.toString())
                        ));
                    }
                } else {
                    varType = initType;
                    
                    if (varType == null && !(var.initializer() instanceof HoleExpr)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-003",
                            "Cannot infer type for var binding. Please add explicit type annotation.",
                            var.span()
                        ));
                    }
                }
                
                addLocal(var.name(), varType, true);
            }
            case AssignStmt assign -> {
                var targetType = lookupLocal(assign.name());
                var valueType = checkExprWithExpected(assign.value(), targetType);
                
                if (targetType == null) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-015",
                        "Unknown variable: " + assign.name(),
                        assign.span()
                    ));
                } else if (!isMutable(assign.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-016",
                        "Cannot reassign immutable binding '" + assign.name() + "'. Use 'var' for mutable bindings.",
                        assign.span()
                    ));
                } else if (valueType != null && !isAssignable(targetType, valueType)) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-002",
                        "Type mismatch in assignment",
                        assign.span(),
                        new ExpectedActual(targetType.toString()),
                        new ExpectedActual(valueType.toString())
                    ));
                }
            }
            case LetStmt let -> {
                var initType = checkExprWithExpected(let.initializer(), let.explicitType());
                
                TypeExpr varType;
                if (let.explicitType() != null) {
                    checkTypeExists(let.explicitType());
                    varType = let.explicitType();
                    
                    if (initType != null && !isAssignable(varType, initType)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-002",
                            "Type mismatch in let binding",
                            let.span(),
                            new ExpectedActual(varType.toString()),
                            new ExpectedActual(initType.toString())
                        ));
                    }
                } else {
                    varType = initType;
                    
                    if (varType == null && !(let.initializer() instanceof HoleExpr)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-003",
                            "Cannot infer type for let binding. Please add explicit type annotation.",
                            let.span()
                        ));
                    }
                }
                
                addLocal(let.name(), varType, false);
            }
            case ExprStmt exprStmt -> {
                var exprType = checkExpr(exprStmt.expr());
                if (exprType != null && !getTypeName(exprType).equals("Unit")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-017",
                        "Expression statement must have Unit type. Did you forget to use the value?",
                        exprStmt.span(),
                        new ExpectedActual("Unit"),
                        new ExpectedActual(exprType.toString())
                    ));
                }
            }
            case ReturnStmt ret -> {
                if (ret.value() != null) {
                    var valueType = checkExprWithExpected(ret.value(), currentReturnType);
                    if (currentReturnType != null && valueType != null && 
                        !isAssignable(currentReturnType, valueType)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-001",
                            "Return type mismatch",
                            ret.span(),
                            new ExpectedActual(currentReturnType.toString()),
                            new ExpectedActual(valueType.toString())
                        ));
                    }
                } else if (currentReturnType != null && !getTypeName(currentReturnType).equals("Unit")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-001",
                        "Return type mismatch: bare 'return;' is only valid in Unit functions",
                        ret.span(),
                        new ExpectedActual(currentReturnType.toString()),
                        new ExpectedActual("Unit")
                    ));
                }
            }
        }
    }
    
    private TypeExpr checkExpr(Expr expr) {
        return switch (expr) {
            case LiteralExpr lit -> switch (lit.kind()) {
                case INT -> new SimpleTypeExpr("Int", lit.span());
                case LONG -> new SimpleTypeExpr("Long", lit.span());
                case BOOL -> new SimpleTypeExpr("Bool", lit.span());
                case STRING -> new SimpleTypeExpr("String", lit.span());
                case UNIT -> new UnitTypeExpr(lit.span());
            };
            
            case IdentExpr ident -> {
                var name = ident.name();
                // Reject bare Option/Result variant names without parentheses
                if (name.equals("None") || name.equals("Some") || name.equals("Ok") || name.equals("Err")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-022",
                        "'" + name + "' must be called with parentheses: " + name + "()",
                        ident.span()
                    ));
                    yield null;
                }
                yield lookupLocal(name);
            }
            
            case HoleExpr hole -> {
                // Record hole context for context retrieval
                hole.setExpectedType(currentExpectedType != null ? currentExpectedType : currentReturnType);
                hole.setAllowedEffects(currentEffects != null ? currentEffects : List.of());
                
                // Add bindings from current scope
                for (var scope : scopes) {
                    for (var entry : scope.entrySet()) {
                        if (entry.getValue() != null) {
                            hole.addBinding(new Binding(entry.getKey(), entry.getValue(), "owned"));
                        }
                    }
                }
                
                diagnostics.add(Diagnostic.incomplete(
                    "NGJ-HOLE-001",
                    "Unresolved hole: ?" + hole.name(),
                    hole.span()
                ));
                
                yield null; // Type unknown
            }
            
            case BinaryExpr binary -> checkBinaryExpr(binary);
            
            case UnaryExpr unary -> checkUnaryExpr(unary);
            
            case CallExpr call -> {
                // Check for built-in Result/Option/List constructors
                if (call.callee() instanceof IdentExpr ident) {
                    var name = ident.name();
                    
                    // STDLIB-03: Result constructors
                    if (name.equals("Ok") || name.equals("Err")) {
                        if (call.args().size() != 1) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                name + " requires exactly one argument",
                                call.span()
                            ));
                        } else {
                            var argType = checkExpr(call.args().get(0));
                            if (name.equals("Ok") && argType != null) {
                                yield new GenericTypeExpr("Result", 
                                    List.of(argType, new SimpleTypeExpr("_", call.span())),
                                    call.span());
                            } else if (name.equals("Err") && argType != null) {
                                yield new GenericTypeExpr("Result",
                                    List.of(new SimpleTypeExpr("_", call.span()), argType),
                                    call.span());
                            }
                        }
                        yield new GenericTypeExpr("Result", List.of(), call.span());
                    } 
                    
                    // STDLIB-02: Option constructors
                    else if (name.equals("Some")) {
                        if (call.args().size() != 1) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                "Some requires exactly one argument",
                                call.span()
                            ));
                        } else {
                            var argType = checkExpr(call.args().get(0));
                            if (argType != null) {
                                yield new GenericTypeExpr("Option", List.of(argType), call.span());
                            }
                        }
                        yield new GenericTypeExpr("Option", List.of(), call.span());
                    } else if (name.equals("None")) {
                        // STDLIB-02: None() with no payload
                        if (!call.args().isEmpty()) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                "None takes no arguments",
                                call.span()
                            ));
                        }
                        yield new GenericTypeExpr("Option", 
                            List.of(new SimpleTypeExpr("_", call.span())), call.span());
                    } 
                    
                    // STDLIB-04: List constructors
                    else if (name.equals("Nil")) {
                        if (!call.args().isEmpty()) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                "Nil takes no arguments (use listOf for non-empty lists)",
                                call.span()
                            ));
                        }
                        yield new GenericTypeExpr("List",
                            List.of(new SimpleTypeExpr("_", call.span())), call.span());
                    } else if (name.equals("listOf")) {
                        if (call.args().isEmpty()) {
                            yield new GenericTypeExpr("List",
                                List.of(new SimpleTypeExpr("_", call.span())), call.span());
                        } else {
                            TypeExpr elemType = null;
                            for (var arg : call.args()) {
                                var argType = checkExpr(arg);
                                if (elemType == null) {
                                    elemType = argType;
                                } else if (argType != null && !isAssignable(elemType, argType)) {
                                    diagnostics.add(Diagnostic.error(
                                        "NGJ-TYPE-023",
                                        "listOf requires all elements to have the same type",
                                        arg.span(),
                                        new ExpectedActual(elemType.toString()),
                                        new ExpectedActual(argType.toString())
                                    ));
                                }
                            }
                            if (elemType != null) {
                                yield new GenericTypeExpr("List", List.of(elemType), call.span());
                            }
                            yield new GenericTypeExpr("List", List.of(), call.span());
                        }
                    } else if (name.equals("Cons")) {
                        // STDLIB-04: Cons(head, tail) -> List<T>
                        if (call.args().size() != 2) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                "Cons requires exactly two arguments (head, tail)",
                                call.span()
                            ));
                            yield new GenericTypeExpr("List", List.of(), call.span());
                        }
                        var headType = checkExpr(call.args().get(0));
                        var tailType = checkExpr(call.args().get(1));
                        if (tailType != null && tailType instanceof GenericTypeExpr tailGeneric) {
                            if (!tailGeneric.name().equals("List")) {
                                diagnostics.add(Diagnostic.error(
                                    "NGJ-TYPE-024",
                                    "Second argument to Cons must be a List",
                                    call.args().get(1).span()
                                ));
                            }
                        }
                        if (headType != null) {
                            yield new GenericTypeExpr("List", List.of(headType), call.span());
                        }
                        yield new GenericTypeExpr("List", List.of(), call.span());
                    }
                    
                    // STDLIB-07: Checked arithmetic operations
                    else if (stdlibFunctions.containsKey(name)) {
                        var fn = stdlibFunctions.get(name);
                        if (call.args().size() != fn.paramTypes().size()) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                name + " requires " + fn.paramTypes().size() + " arguments",
                                call.span()
                            ));
                        } else {
                            for (int i = 0; i < call.args().size(); i++) {
                                var argType = checkExpr(call.args().get(i));
                                var expectedType = fn.paramTypes().get(i);
                                if (argType != null && !getTypeName(argType).equals(expectedType)) {
                                    diagnostics.add(Diagnostic.error(
                                        "NGJ-TYPE-002",
                                        "Type mismatch in argument " + (i + 1),
                                        call.args().get(i).span(),
                                        new ExpectedActual(expectedType),
                                        new ExpectedActual(argType.toString())
                                    ));
                                }
                            }
                        }
                        yield fn.returnType();
                    }
                    
                    // STDLIB-05: Ensure no null-returning functions
                    // Standard library functions that return Option instead of null
                    else if (name.equals("head")) {
                        // STDLIB-08: head returns Option<T>
                        if (call.args().size() != 1) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                "head requires exactly one argument (a List)",
                                call.span()
                            ));
                        } else {
                            var listType = checkExpr(call.args().get(0));
                            if (listType instanceof GenericTypeExpr listGeneric && 
                                listGeneric.name().equals("List") && !listGeneric.typeArgs().isEmpty()) {
                                yield new GenericTypeExpr("Option", 
                                    List.of(listGeneric.typeArgs().get(0)), call.span());
                            }
                        }
                        yield new GenericTypeExpr("Option", 
                            List.of(new SimpleTypeExpr("_", call.span())), call.span());
                    } else if (name.equals("get")) {
                        // STDLIB-08: get returns Option<T>
                        if (call.args().size() != 2) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                "get requires two arguments (list, index)",
                                call.span()
                            ));
                        } else {
                            var listType = checkExpr(call.args().get(0));
                            var indexType = checkExpr(call.args().get(1));
                            if (indexType != null && !getTypeName(indexType).equals("Int")) {
                                diagnostics.add(Diagnostic.error(
                                    "NGJ-TYPE-002",
                                    "Index must be Int",
                                    call.args().get(1).span(),
                                    new ExpectedActual("Int"),
                                    new ExpectedActual(indexType.toString())
                                ));
                            }
                            if (listType instanceof GenericTypeExpr listGeneric && 
                                listGeneric.name().equals("List") && !listGeneric.typeArgs().isEmpty()) {
                                yield new GenericTypeExpr("Option", 
                                    List.of(listGeneric.typeArgs().get(0)), call.span());
                            }
                        }
                        yield new GenericTypeExpr("Option", 
                            List.of(new SimpleTypeExpr("_", call.span())), call.span());
                    } else if (name.equals("getOrElse")) {
                        // Option.getOrElse(opt, default) -> T
                        if (call.args().size() != 2) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                "getOrElse requires two arguments (option, default)",
                                call.span()
                            ));
                        } else {
                            var optType = checkExpr(call.args().get(0));
                            var defaultType = checkExpr(call.args().get(1));
                            if (optType instanceof GenericTypeExpr optGeneric && 
                                optGeneric.name().equals("Option") && !optGeneric.typeArgs().isEmpty()) {
                                var innerType = optGeneric.typeArgs().get(0);
                                if (defaultType != null && !isAssignable(innerType, defaultType)) {
                                    diagnostics.add(Diagnostic.error(
                                        "NGJ-TYPE-002",
                                        "Default value type must match Option inner type",
                                        call.args().get(1).span(),
                                        new ExpectedActual(innerType.toString()),
                                        new ExpectedActual(defaultType.toString())
                                    ));
                                }
                                yield innerType;
                            }
                        }
                        yield null;
                    } else if (name.equals("isSome") || name.equals("isNone") || 
                               name.equals("isOk") || name.equals("isErr") || 
                               name.equals("isEmpty")) {
                        // Boolean predicates
                        if (call.args().size() != 1) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                name + " requires exactly one argument",
                                call.span()
                            ));
                        } else {
                            checkExpr(call.args().get(0));
                        }
                        yield new SimpleTypeExpr("Bool", call.span());
                    } else if (name.equals("length")) {
                        // List.length -> Int
                        if (call.args().size() != 1) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                "length requires exactly one argument (a List)",
                                call.span()
                            ));
                        } else {
                            checkExpr(call.args().get(0));
                        }
                        yield new SimpleTypeExpr("Int", call.span());
                    } else if (name.equals("toOption")) {
                        // Result.toOption -> Option<T>
                        if (call.args().size() != 1) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                "toOption requires exactly one argument (a Result)",
                                call.span()
                            ));
                        } else {
                            var resType = checkExpr(call.args().get(0));
                            if (resType instanceof GenericTypeExpr resGeneric && 
                                resGeneric.name().equals("Result") && !resGeneric.typeArgs().isEmpty()) {
                                yield new GenericTypeExpr("Option", 
                                    List.of(resGeneric.typeArgs().get(0)), call.span());
                            }
                        }
                        yield new GenericTypeExpr("Option", 
                            List.of(new SimpleTypeExpr("_", call.span())), call.span());
                    } else if (name.equals("toResult")) {
                        // Option.toResult(opt, err) -> Result<T, E>
                        if (call.args().size() != 2) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                "toResult requires two arguments (option, error)",
                                call.span()
                            ));
                        } else {
                            var optType = checkExpr(call.args().get(0));
                            var errType = checkExpr(call.args().get(1));
                            if (optType instanceof GenericTypeExpr optGeneric && 
                                optGeneric.name().equals("Option") && !optGeneric.typeArgs().isEmpty()) {
                                if (errType != null) {
                                    yield new GenericTypeExpr("Result", 
                                        List.of(optGeneric.typeArgs().get(0), errType), call.span());
                                }
                            }
                        }
                        yield new GenericTypeExpr("Result", List.of(), call.span());
                    } else if (fnEnv.containsKey(name)) {
                        var fn = fnEnv.get(name);
                        if (call.args().size() != fn.params().size()) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                name + " requires " + fn.params().size() + " arguments",
                                call.span()
                            ));
                        } else {
                            for (int i = 0; i < call.args().size(); i++) {
                                var expectedParamType = fn.params().get(i).type();
                                var argType = checkExprWithExpected(call.args().get(i), expectedParamType);
                                if (argType != null && !isAssignable(expectedParamType, argType)) {
                                    diagnostics.add(Diagnostic.error(
                                        "NGJ-TYPE-002",
                                        "Type mismatch in argument " + (i + 1) + " of " + name,
                                        call.args().get(i).span(),
                                        new ExpectedActual(expectedParamType.toString()),
                                        new ExpectedActual(argType.toString())
                                    ));
                                }
                            }
                        }
                        yield fn.returnType();
                    } else if (typeEnv.containsKey(name) && typeEnv.get(name).kind() == TypeInfo.Kind.DATA) {
                        var fields = typeEnv.get(name).fields();
                        if (call.args().size() != fields.size()) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                name + " requires " + fields.size() + " arguments",
                                call.span()
                            ));
                        } else {
                            for (int i = 0; i < call.args().size(); i++) {
                                var expectedFieldType = fields.get(i).type();
                                var argType = checkExprWithExpected(call.args().get(i), expectedFieldType);
                                if (argType != null && expectedFieldType != null && !isAssignable(expectedFieldType, argType)) {
                                    diagnostics.add(Diagnostic.error(
                                        "NGJ-TYPE-002",
                                        "Type mismatch in field '" + fields.get(i).name() + "' of " + name,
                                        call.args().get(i).span(),
                                        new ExpectedActual(expectedFieldType.toString()),
                                        new ExpectedActual(argType.toString())
                                    ));
                                }
                            }
                        }
                        yield new SimpleTypeExpr(name, call.span());
                    } else {
                        // Check if name is an unqualified enum variant constructor
                        for (var entry : typeEnv.entrySet()) {
                            if (entry.getValue().kind() == TypeInfo.Kind.ENUM) {
                                for (var variant : entry.getValue().variants()) {
                                    if (variant.name().equals(name)) {
                                        var fields = variant.fields();
                                        if (call.args().size() != fields.size()) {
                                            diagnostics.add(Diagnostic.error(
                                                "NGJ-TYPE-014",
                                                name + " requires " + fields.size() + " arguments",
                                                call.span()
                                            ));
                                        } else {
                                            for (int i = 0; i < call.args().size(); i++) {
                                                var expectedFieldType = fields.get(i).type();
                                                var argType = checkExprWithExpected(call.args().get(i), expectedFieldType);
                                                if (argType != null && expectedFieldType != null && !isAssignable(expectedFieldType, argType)) {
                                                    diagnostics.add(Diagnostic.error(
                                                        "NGJ-TYPE-002",
                                                        "Type mismatch in field '" + fields.get(i).name() + "' of " + name,
                                                        call.args().get(i).span(),
                                                        new ExpectedActual(expectedFieldType.toString()),
                                                        new ExpectedActual(argType.toString())
                                                    ));
                                                }
                                            }
                                        }
                                        yield new SimpleTypeExpr(entry.getKey(), call.span());
                                    }
                                }
                            }
                        }
                    }
                } else if (call.callee() instanceof FieldAccessExpr access && access.object() instanceof IdentExpr enumIdent) {
                    var enumName = enumIdent.name();
                    var variantName = access.fieldName();
                    var typeInfo = typeEnv.get(enumName);
                    if (typeInfo != null && typeInfo.kind() == TypeInfo.Kind.ENUM) {
                        for (var variant : typeInfo.variants()) {
                            if (variant.name().equals(variantName)) {
                                var fields = variant.fields();
                                if (call.args().size() != fields.size()) {
                                    diagnostics.add(Diagnostic.error(
                                        "NGJ-TYPE-014",
                                        enumName + "." + variantName + " requires " + fields.size() + " arguments",
                                        call.span()
                                    ));
                                } else {
                                    for (int i = 0; i < call.args().size(); i++) {
                                        var expectedFieldType = fields.get(i).type();
                                        var argType = checkExprWithExpected(call.args().get(i), expectedFieldType);
                                        if (argType != null && expectedFieldType != null && !isAssignable(expectedFieldType, argType)) {
                                            diagnostics.add(Diagnostic.error(
                                                "NGJ-TYPE-002",
                                                "Type mismatch in field '" + fields.get(i).name() + "' of " + enumName + "." + variantName,
                                                call.args().get(i).span(),
                                                new ExpectedActual(expectedFieldType.toString()),
                                                new ExpectedActual(argType.toString())
                                            ));
                                        }
                                    }
                                }
                                yield new SimpleTypeExpr(enumName, call.span());
                            }
                        }
                    }
                }
                
                checkExpr(call.callee());
                for (var arg : call.args()) {
                    checkExpr(arg);
                }
                yield null;
            }
            
            case FieldAccessExpr access -> {
                var objType = checkExpr(access.object());
                if (objType != null) {
                    var typeName = getTypeName(objType);
                    var typeInfo = typeEnv.get(typeName);
                    if (typeInfo != null && typeInfo.kind() == TypeInfo.Kind.DATA) {
                        for (var field : typeInfo.fields()) {
                            if (field.name().equals(access.fieldName())) {
                                yield field.type();
                            }
                        }
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-025",
                            "Unknown field '" + access.fieldName() + "' on type " + typeName,
                            access.span()
                        ));
                    }
                }
                yield null;
            }
            
            case ConstructorExpr ctor -> {
                var typeInfo = typeEnv.get(ctor.typePath());
                if (typeInfo == null) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-004",
                        "Unknown type: " + ctor.typePath(),
                        ctor.span()
                    ));
                    yield null;
                }
                
                for (var field : ctor.fields()) {
                    checkExpr(field.value());
                }
                
                yield new SimpleTypeExpr(ctor.typePath(), ctor.span());
            }
            
            case IfExpr ifExpr -> checkIfExpr(ifExpr);
            
            case MatchExpr matchExpr -> checkMatchExpr(matchExpr);
            
            case BlockExpr block -> checkBlock(block);
            
            case PropagateExpr prop -> checkPropagateExpr(prop);
            
            case ParenExpr paren -> checkExpr(paren.inner());
            
            case ForExpr forExpr -> checkForExpr(forExpr);
            
            case WhileExpr whileExpr -> checkWhileExpr(whileExpr);
            
            case LambdaExpr lambda -> checkLambdaExpr(lambda);
        };
    }
    
    private TypeExpr checkForExpr(ForExpr forExpr) {
        var iterableType = checkExpr(forExpr.iterable());
        
        TypeExpr elementType = null;
        if (iterableType instanceof GenericTypeExpr generic && generic.name().equals("List")) {
            if (!generic.typeArgs().isEmpty()) {
                elementType = generic.typeArgs().get(0);
            }
        } else if (iterableType != null) {
            diagnostics.add(Diagnostic.error(
                "NGJ-TYPE-026",
                "for loop requires List type, got: " + iterableType,
                forExpr.iterable().span()
            ));
        }
        
        // Collect outer var names before entering for body scope
        var outerVars = collectOuterMutableVars();
        
        pushScope();
        
        addLocal(forExpr.varName(), elementType, false);
        
        // Check for outer var capture and ? propagation in body
        checkForBodyRestrictions(forExpr.body(), outerVars, forExpr.span());
        
        var bodyType = checkBlock(forExpr.body());
        
        popScope();
        
        if (forExpr.hasYield()) {
            if (bodyType != null) {
                return new GenericTypeExpr("List", List.of(bodyType), forExpr.span());
            }
            return new GenericTypeExpr("List", List.of(), forExpr.span());
        } else {
            if (bodyType != null && !getTypeName(bodyType).equals("Unit")) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-TYPE-018",
                    "for without yield must have Unit body type",
                    forExpr.body().span(),
                    new ExpectedActual("Unit"),
                    new ExpectedActual(bodyType.toString())
                ));
            }
            return new UnitTypeExpr(forExpr.span());
        }
    }
    
    private Set<String> collectOuterMutableVars() {
        var result = new HashSet<String>();
        for (var mutSet : mutableVars) {
            result.addAll(mutSet);
        }
        return result;
    }
    
    private void checkForBodyRestrictions(BlockExpr body, Set<String> outerVars, SourceSpan forSpan) {
        // Check for outer var capture (assignment to outer var)
        checkForOuterVarCapture(body, outerVars);
        
        // Check for ? propagation in for body
        checkForPropagate(body, forSpan);
    }
    
    private void checkForOuterVarCapture(BlockExpr body, Set<String> outerVars) {
        for (var stmt : body.statements()) {
            if (stmt instanceof AssignStmt assign) {
                if (outerVars.contains(assign.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-CAPTURE-001",
                        "Cannot capture and modify outer 'var' '" + assign.name() + "' inside for body. For bodies may only capture read-only 'val' bindings.",
                        assign.span()
                    ));
                }
            }
            checkStmtForOuterVarCapture(stmt, outerVars);
        }
        if (body.tailExpr() != null) {
            checkExprForOuterVarCapture(body.tailExpr(), outerVars);
        }
    }
    
    private void checkStmtForOuterVarCapture(Statement stmt, Set<String> outerVars) {
        switch (stmt) {
            case ExprStmt exprStmt -> checkExprForOuterVarCapture(exprStmt.expr(), outerVars);
            case ValStmt val -> checkExprForOuterVarCapture(val.initializer(), outerVars);
            case VarStmt var -> checkExprForOuterVarCapture(var.initializer(), outerVars);
            case AssignStmt assign -> checkExprForOuterVarCapture(assign.value(), outerVars);
            case LetStmt let -> checkExprForOuterVarCapture(let.initializer(), outerVars);
            case ReturnStmt ret -> { if (ret.value() != null) checkExprForOuterVarCapture(ret.value(), outerVars); }
        }
    }
    
    private void checkExprForOuterVarCapture(Expr expr, Set<String> outerVars) {
        switch (expr) {
            case BlockExpr block -> checkForOuterVarCapture(block, outerVars);
            case IfExpr ifExpr -> {
                checkExprForOuterVarCapture(ifExpr.condition(), outerVars);
                checkForOuterVarCapture(ifExpr.thenBranch(), outerVars);
                if (ifExpr.elseBranch() != null) {
                    checkExprForOuterVarCapture(ifExpr.elseBranch(), outerVars);
                }
            }
            case MatchExpr matchExpr -> {
                checkExprForOuterVarCapture(matchExpr.scrutinee(), outerVars);
                for (var arm : matchExpr.arms()) {
                    checkExprForOuterVarCapture(arm.body(), outerVars);
                }
            }
            case WhileExpr whileExpr -> {
                checkExprForOuterVarCapture(whileExpr.condition(), outerVars);
                checkForOuterVarCapture(whileExpr.body(), outerVars);
            }
            case BinaryExpr binary -> {
                checkExprForOuterVarCapture(binary.left(), outerVars);
                checkExprForOuterVarCapture(binary.right(), outerVars);
            }
            case UnaryExpr unary -> checkExprForOuterVarCapture(unary.operand(), outerVars);
            case CallExpr call -> {
                checkExprForOuterVarCapture(call.callee(), outerVars);
                for (var arg : call.args()) {
                    checkExprForOuterVarCapture(arg, outerVars);
                }
            }
            case PropagateExpr prop -> checkExprForOuterVarCapture(prop.inner(), outerVars);
            case ParenExpr paren -> checkExprForOuterVarCapture(paren.inner(), outerVars);
            case FieldAccessExpr access -> checkExprForOuterVarCapture(access.object(), outerVars);
            case ConstructorExpr ctor -> {
                for (var field : ctor.fields()) {
                    checkExprForOuterVarCapture(field.value(), outerVars);
                }
            }
            default -> {}
        }
    }
    
    private void checkForPropagate(BlockExpr body, SourceSpan forSpan) {
        for (var stmt : body.statements()) {
            checkStmtForPropagate(stmt, forSpan);
        }
        if (body.tailExpr() != null) {
            checkExprForPropagate(body.tailExpr(), forSpan);
        }
    }
    
    private void checkStmtForPropagate(Statement stmt, SourceSpan forSpan) {
        switch (stmt) {
            case ExprStmt exprStmt -> checkExprForPropagate(exprStmt.expr(), forSpan);
            case ValStmt val -> checkExprForPropagate(val.initializer(), forSpan);
            case VarStmt var -> checkExprForPropagate(var.initializer(), forSpan);
            case AssignStmt assign -> checkExprForPropagate(assign.value(), forSpan);
            case LetStmt let -> checkExprForPropagate(let.initializer(), forSpan);
            case ReturnStmt ret -> {
                diagnostics.add(Diagnostic.error(
                    "NGJ-PROP-002",
                    "return statement is not allowed inside for body. For bodies are callback boundaries.",
                    ret.span()
                ));
                if (ret.value() != null) checkExprForPropagate(ret.value(), forSpan);
            }
        }
    }
    
    private void checkExprForPropagate(Expr expr, SourceSpan forSpan) {
        switch (expr) {
            case PropagateExpr prop -> {
                diagnostics.add(Diagnostic.error(
                    "NGJ-PROP-002",
                    "? operator is not allowed inside for body. Use explicit match or move the propagation outside the for loop.",
                    prop.span()
                ));
                checkExprForPropagate(prop.inner(), forSpan);
            }
            case LambdaExpr _ -> {} // Lambda is a separate propagation boundary, don't recurse
            case BlockExpr block -> checkForPropagate(block, forSpan);
            case IfExpr ifExpr -> {
                checkExprForPropagate(ifExpr.condition(), forSpan);
                checkForPropagate(ifExpr.thenBranch(), forSpan);
                if (ifExpr.elseBranch() != null) {
                    checkExprForPropagate(ifExpr.elseBranch(), forSpan);
                }
            }
            case MatchExpr matchExpr -> {
                checkExprForPropagate(matchExpr.scrutinee(), forSpan);
                for (var arm : matchExpr.arms()) {
                    checkExprForPropagate(arm.body(), forSpan);
                }
            }
            case WhileExpr whileExpr -> {
                checkExprForPropagate(whileExpr.condition(), forSpan);
                checkForPropagate(whileExpr.body(), forSpan);
            }
            case BinaryExpr binary -> {
                checkExprForPropagate(binary.left(), forSpan);
                checkExprForPropagate(binary.right(), forSpan);
            }
            case UnaryExpr unary -> checkExprForPropagate(unary.operand(), forSpan);
            case CallExpr call -> {
                checkExprForPropagate(call.callee(), forSpan);
                for (var arg : call.args()) {
                    checkExprForPropagate(arg, forSpan);
                }
            }
            case ParenExpr paren -> checkExprForPropagate(paren.inner(), forSpan);
            case FieldAccessExpr access -> checkExprForPropagate(access.object(), forSpan);
            case ConstructorExpr ctor -> {
                for (var field : ctor.fields()) {
                    checkExprForPropagate(field.value(), forSpan);
                }
            }
            case ForExpr nested -> {
                checkExprForPropagate(nested.iterable(), forSpan);
                checkForPropagate(nested.body(), forSpan);
            }
            default -> {}
        }
    }
    
    private TypeExpr checkWhileExpr(WhileExpr whileExpr) {
        var condType = checkExpr(whileExpr.condition());
        if (condType != null && !getTypeName(condType).equals("Bool")) {
            diagnostics.add(Diagnostic.error(
                "NGJ-TYPE-010",
                "While condition must be Bool",
                whileExpr.condition().span()
            ));
        }
        
        var bodyType = checkBlock(whileExpr.body());
        if (bodyType != null && !getTypeName(bodyType).equals("Unit")) {
            diagnostics.add(Diagnostic.error(
                "NGJ-TYPE-019",
                "while body must have Unit type",
                whileExpr.body().span(),
                new ExpectedActual("Unit"),
                new ExpectedActual(bodyType.toString())
            ));
        }
        
        return new UnitTypeExpr(whileExpr.span());
    }
    
    private TypeExpr checkLambdaExpr(LambdaExpr lambda) {
        pushScope();
        
        for (var param : lambda.params()) {
            checkTypeExists(param.type());
            addLocal(param.name(), param.type(), false);
        }
        
        var bodyType = checkBlock(lambda.body());
        
        popScope();
        
        return bodyType;
    }
    
    private TypeExpr checkBinaryExpr(BinaryExpr binary) {
        var leftType = checkExpr(binary.left());
        var rightType = checkExpr(binary.right());
        
        return switch (binary.op()) {
            case ADD, SUB, MUL, DIV, MOD -> {
                if (leftType != null && rightType != null) {
                    var leftName = getTypeName(leftType);
                    var rightName = getTypeName(rightType);
                    
                    if (!leftName.equals(rightName)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-005",
                            "Binary operation requires same types",
                            binary.span(),
                            new ExpectedActual(leftName),
                            new ExpectedActual(rightName)
                        ));
                    }
                    
                    if (!leftName.equals("Int") && !leftName.equals("Long")) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-006",
                            "Arithmetic operation requires Int or Long",
                            binary.span()
                        ));
                    }
                }
                yield leftType;
            }
            
            case EQ, NE, LT, LE, GT, GE -> {
                yield new SimpleTypeExpr("Bool", binary.span());
            }
            
            case AND, OR -> {
                if (leftType != null && !getTypeName(leftType).equals("Bool")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-007",
                        "Logical operation requires Bool",
                        binary.left().span()
                    ));
                }
                if (rightType != null && !getTypeName(rightType).equals("Bool")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-007",
                        "Logical operation requires Bool",
                        binary.right().span()
                    ));
                }
                yield new SimpleTypeExpr("Bool", binary.span());
            }
        };
    }
    
    private TypeExpr checkUnaryExpr(UnaryExpr unary) {
        var operandType = checkExpr(unary.operand());
        
        return switch (unary.op()) {
            case NEG -> {
                if (operandType != null) {
                    var name = getTypeName(operandType);
                    if (!name.equals("Int") && !name.equals("Long")) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-008",
                            "Negation requires Int or Long",
                            unary.span()
                        ));
                    }
                }
                yield operandType;
            }
            case NOT -> {
                if (operandType != null && !getTypeName(operandType).equals("Bool")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-009",
                        "Logical not requires Bool",
                        unary.span()
                    ));
                }
                yield new SimpleTypeExpr("Bool", unary.span());
            }
        };
    }
    
    private TypeExpr checkIfExpr(IfExpr ifExpr) {
        var savedExpected = currentExpectedType;
        var condType = checkExprWithExpected(ifExpr.condition(), new SimpleTypeExpr("Bool", ifExpr.condition().span()));
        if (condType != null && !getTypeName(condType).equals("Bool")) {
            diagnostics.add(Diagnostic.error(
                "NGJ-TYPE-010",
                "If condition must be Bool",
                ifExpr.condition().span()
            ));
        }
        
        currentExpectedType = savedExpected;
        var thenType = checkBlock(ifExpr.thenBranch());
        
        TypeExpr elseType = null;
        if (ifExpr.elseBranch() != null) {
            currentExpectedType = savedExpected;
            elseType = checkExpr(ifExpr.elseBranch());
            
            if (thenType != null && elseType != null && !isAssignable(thenType, elseType)) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-TYPE-011",
                    "If branches must have same type",
                    ifExpr.span(),
                    new ExpectedActual(thenType.toString()),
                    new ExpectedActual(elseType.toString())
                ));
            }
            return thenType != null ? thenType : elseType;
        } else {
            if (thenType != null && !getTypeName(thenType).equals("Unit")) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-TYPE-021",
                    "If without else must have Unit then-branch type, or add an else branch",
                    ifExpr.thenBranch().span(),
                    new ExpectedActual("Unit"),
                    new ExpectedActual(thenType.toString())
                ));
            }
            return new UnitTypeExpr(ifExpr.span());
        }
    }
    
    private TypeExpr checkMatchExpr(MatchExpr matchExpr) {
        var savedExpected = currentExpectedType;
        var scrutineeType = checkExprWithExpected(matchExpr.scrutinee(), null);
        
        // Check exhaustiveness for enum types
        if (scrutineeType != null) {
            var typeName = getTypeName(scrutineeType);
            var typeInfo = typeEnv.get(typeName);
            
            if (typeInfo != null && typeInfo.kind() == TypeInfo.Kind.ENUM) {
                checkMatchExhaustiveness(matchExpr, typeInfo, typeName);
            }
        }
        
        TypeExpr resultType = null;
        for (var arm : matchExpr.arms()) {
            pushScope();
            bindPatternVars(arm.pattern(), scrutineeType);
            currentExpectedType = savedExpected;
            var armType = checkExpr(arm.body());
            popScope();
            
            if (resultType == null) {
                resultType = armType;
            } else if (armType != null && !isAssignable(resultType, armType)) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-TYPE-012",
                    "Match arms must have same type",
                    arm.span(),
                    new ExpectedActual(resultType.toString()),
                    new ExpectedActual(armType.toString())
                ));
            }
        }
        
        return resultType;
    }
    
    /**
     * ENUM-02: Exhaustiveness checking with stable JSON diagnostics naming each missing variant.
     * This is critical for the AI fix loop - the diagnostic must clearly identify
     * which variants are missing so the AI can add the appropriate match arms.
     */
    private void checkMatchExhaustiveness(MatchExpr matchExpr, TypeInfo typeInfo, String typeName) {
        var variants = typeInfo.variants();
        var coveredVariants = new HashSet<String>();
        boolean hasWildcard = false;
        
        for (var arm : matchExpr.arms()) {
            switch (arm.pattern()) {
                case WildcardPattern _ -> hasWildcard = true;
                case ConstructorPattern ctor -> {
                    var path = ctor.typePath();
                    var shortName = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    coveredVariants.add(shortName);
                }
                case BindingPattern _ -> hasWildcard = true;
                default -> {}
            }
        }
        
        if (!hasWildcard) {
            var variantNames = variants.stream().map(VariantInfo::name).toList();
            var missing = variantNames.stream()
                .filter(v -> !coveredVariants.contains(v))
                .toList();
            
            if (!missing.isEmpty()) {
                // Use specialized exhaustiveness error with structured missing variant info
                diagnostics.add(Diagnostic.exhaustivenessError(
                    "NGJ-MATCH-001",
                    "Non-exhaustive match on " + typeName + ". Missing variants: " + String.join(", ", missing),
                    matchExpr.span(),
                    typeName,
                    missing
                ));
            }
        }
    }
    
    private TypeExpr checkPropagateExpr(PropagateExpr prop) {
        var innerType = checkExpr(prop.inner());
        
        if (innerType != null) {
            var typeName = getTypeName(innerType);
            if (typeName.equals("Option")) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-TYPE-020",
                    "? operator is not supported on Option in v0.3. Use match or convert to Result explicitly.",
                    prop.span()
                ));
            } else if (!typeName.equals("Result")) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-TYPE-013",
                    "? operator requires Result type",
                    prop.span()
                ));
            }
            
            if (innerType instanceof GenericTypeExpr generic && 
                currentReturnType instanceof GenericTypeExpr returnGeneric) {
                
                if (generic.name().equals("Result") && returnGeneric.name().equals("Result")) {
                    if (generic.typeArgs().size() >= 2 && returnGeneric.typeArgs().size() >= 2) {
                        var innerErr = generic.typeArgs().get(1);
                        var returnErr = returnGeneric.typeArgs().get(1);
                        
                        if (!isAssignable(returnErr, innerErr)) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-PROP-001",
                                "Cannot propagate error: different error types require explicit match",
                                prop.span(),
                                new ExpectedActual(returnErr.toString()),
                                new ExpectedActual(innerErr.toString())
                            ));
                        }
                    }
                }
            }
        }
        
        if (innerType instanceof GenericTypeExpr generic && !generic.typeArgs().isEmpty()) {
            return generic.typeArgs().get(0);
        }
        
        return null;
    }
    
    private void bindPatternVars(Pattern pattern, TypeExpr scrutineeType) {
        switch (pattern) {
            case WildcardPattern _ -> {}
            case LiteralPattern _ -> {}
            case BindingPattern binding -> addLocal(binding.name(), scrutineeType);
            case ConstructorPattern ctor -> {
                var path = ctor.typePath();
                var shortName = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                List<TypeExpr> fieldTypes = new ArrayList<>();
                
                if (scrutineeType != null) {
                    var typeName = getTypeName(scrutineeType);
                    var typeInfo = typeEnv.get(typeName);
                    if (typeInfo != null && typeInfo.kind() == TypeInfo.Kind.ENUM) {
                        for (var variant : typeInfo.variants()) {
                            if (variant.name().equals(shortName)) {
                                for (var f : variant.fields()) {
                                    fieldTypes.add(f.type());
                                }
                                break;
                            }
                        }
                    } else if (typeInfo != null && typeInfo.kind() == TypeInfo.Kind.DATA) {
                        for (var f : typeInfo.fields()) {
                            fieldTypes.add(f.type());
                        }
                    }
                    
                    if (fieldTypes.isEmpty() && scrutineeType instanceof GenericTypeExpr gen) {
                        if (gen.name().equals("Option") && shortName.equals("Some") && !gen.typeArgs().isEmpty()) {
                            fieldTypes.add(gen.typeArgs().get(0));
                        } else if (gen.name().equals("Result")) {
                            if (shortName.equals("Ok") && gen.typeArgs().size() >= 1) {
                                fieldTypes.add(gen.typeArgs().get(0));
                            } else if (shortName.equals("Err") && gen.typeArgs().size() >= 2) {
                                fieldTypes.add(gen.typeArgs().get(1));
                            }
                        }
                    }
                }
                
                for (int i = 0; i < ctor.fields().size(); i++) {
                    var field = ctor.fields().get(i);
                    TypeExpr fieldType = i < fieldTypes.size() ? fieldTypes.get(i) : null;
                    if (field.pattern() != null) {
                        bindPatternVars(field.pattern(), fieldType);
                    } else {
                        addLocal(field.name(), fieldType);
                    }
                }
            }
        }
    }
    
    private void checkTypeExists(TypeExpr type) {
        switch (type) {
            case SimpleTypeExpr simple -> {
                if (!typeEnv.containsKey(simple.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-004",
                        "Unknown type: " + simple.name(),
                        simple.span()
                    ));
                }
            }
            case GenericTypeExpr generic -> {
                if (!typeEnv.containsKey(generic.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-TYPE-004",
                        "Unknown type: " + generic.name(),
                        generic.span()
                    ));
                }
                for (var arg : generic.typeArgs()) {
                    checkTypeExists(arg);
                }
            }
            case UnitTypeExpr _ -> {}
        }
    }
    
    private boolean isAssignable(TypeExpr target, TypeExpr source) {
        if (target == null || source == null) return true;
        
        var targetName = getTypeName(target);
        var sourceName = getTypeName(source);
        
        // Wildcard type `_` matches anything
        if (targetName.equals("_") || sourceName.equals("_")) {
            return true;
        }
        
        if (targetName.equals(sourceName)) {
            if (target instanceof GenericTypeExpr targetGen && source instanceof GenericTypeExpr sourceGen) {
                if (targetGen.typeArgs().size() != sourceGen.typeArgs().size()) return false;
                for (int i = 0; i < targetGen.typeArgs().size(); i++) {
                    if (!isAssignable(targetGen.typeArgs().get(i), sourceGen.typeArgs().get(i))) {
                        return false;
                    }
                }
            }
            return true;
        }
        
        return false;
    }
    
    private String getTypeName(TypeExpr type) {
        return switch (type) {
            case SimpleTypeExpr simple -> simple.name();
            case GenericTypeExpr generic -> generic.name();
            case UnitTypeExpr _ -> "Unit";
        };
    }
    
    private void pushScope() {
        scopes.push(new HashMap<>());
        mutableVars.push(new HashSet<>());
    }
    
    private void popScope() {
        scopes.pop();
        mutableVars.pop();
    }
    
    private void addLocal(String name, TypeExpr type) {
        addLocal(name, type, false);
    }
    
    private void addLocal(String name, TypeExpr type, boolean isMutable) {
        if (!scopes.isEmpty()) {
            scopes.peek().put(name, type);
            if (isMutable && !mutableVars.isEmpty()) {
                mutableVars.peek().add(name);
            }
        }
    }
    
    private boolean isMutable(String name) {
        for (var mutSet : mutableVars) {
            if (mutSet.contains(name)) {
                return true;
            }
        }
        return false;
    }
    
    private TypeExpr lookupLocal(String name) {
        for (var scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return null;
    }
    
    public record TypeInfo(Kind kind, Object data) {
        public enum Kind { 
            PRIMITIVE,       // Legacy (unused)
            VALUE_TYPE,      // Built-in value types: Int, Long, Bool, etc.
            GENERIC,         // Legacy generic (unused)
            GENERIC_VALUE,   // Generic value type: List<T>
            GENERIC_ENUM,    // Generic enum: Option<T>, Result<T,E>
            DATA,            // User-defined data/record (value type)
            ENUM             // User-defined enum (value type)
        }
        
        @SuppressWarnings("unchecked")
        public List<FieldInfo> fields() {
            return kind == Kind.DATA ? (List<FieldInfo>) data : List.of();
        }
        
        @SuppressWarnings("unchecked")
        public List<VariantInfo> variants() {
            return kind == Kind.ENUM ? (List<VariantInfo>) data : List.of();
        }
    }
    
    public record FieldInfo(String name, TypeExpr type) {}
    public record VariantInfo(String name, List<FieldInfo> fields) {}
}
