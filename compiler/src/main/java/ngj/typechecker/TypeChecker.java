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
    private final Deque<Map<String, TypeExpr>> scopes = new ArrayDeque<>();
    private final Deque<Set<String>> mutableVars = new ArrayDeque<>();
    private TypeExpr currentReturnType;
    private List<String> currentEffects;
    
    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }
    
    public void check(CompilationUnit unit) {
        // Register built-in types
        registerBuiltins();
        
        // First pass: register type declarations
        for (var decl : unit.declarations()) {
            registerDecl(decl);
        }
        
        // Second pass: type check bodies
        for (var decl : unit.declarations()) {
            checkDecl(decl);
        }
    }
    
    private void registerBuiltins() {
        typeEnv.put("Int", new TypeInfo(TypeInfo.Kind.PRIMITIVE, List.of()));
        typeEnv.put("Long", new TypeInfo(TypeInfo.Kind.PRIMITIVE, List.of()));
        typeEnv.put("Bool", new TypeInfo(TypeInfo.Kind.PRIMITIVE, List.of()));
        typeEnv.put("String", new TypeInfo(TypeInfo.Kind.PRIMITIVE, List.of()));
        typeEnv.put("Unit", new TypeInfo(TypeInfo.Kind.PRIMITIVE, List.of()));
        typeEnv.put("Result", new TypeInfo(TypeInfo.Kind.GENERIC, List.of("T", "E")));
        typeEnv.put("Option", new TypeInfo(TypeInfo.Kind.GENERIC, List.of("T")));
    }
    
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
            case FnDecl _ -> {}
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
        currentEffects = fn.effects();
        
        // Check effect bound (MVP only allows empty)
        if (!fn.effects().isEmpty()) {
            diagnostics.add(Diagnostic.error(
                "NGJ-EFFECT-001",
                "Non-empty effect bound not supported in MVP: ! {" + String.join(", ", fn.effects()) + "}",
                fn.span()
            ));
        }
        
        // Add parameters to scope
        for (var param : fn.params()) {
            addLocal(param.name(), param.type());
        }
        
        // Check body
        var bodyType = checkBlock(fn.body());
        
        // Verify return type
        if (bodyType != null && !isAssignable(fn.returnType(), bodyType)) {
            diagnostics.add(Diagnostic.error(
                "NGJ-TYPE-001",
                "Return type mismatch",
                fn.body().span(),
                new ExpectedActual(fn.returnType().toString()),
                new ExpectedActual(bodyType.toString())
            ));
        }
        
        popScope();
        currentReturnType = null;
        currentEffects = null;
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
        
        for (var stmt : block.statements()) {
            checkStmt(stmt);
        }
        
        TypeExpr result = null;
        if (block.tailExpr() != null) {
            result = checkExpr(block.tailExpr());
        }
        
        popScope();
        return result;
    }
    
    private void checkStmt(Statement stmt) {
        switch (stmt) {
            case ValStmt val -> {
                var initType = checkExpr(val.initializer());
                
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
                    
                    if (varType == null) {
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
                var initType = checkExpr(var.initializer());
                
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
                    
                    if (varType == null) {
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
                var valueType = checkExpr(assign.value());
                var targetType = lookupLocal(assign.name());
                
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
                var initType = checkExpr(let.initializer());
                
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
                    
                    if (varType == null) {
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
                    var valueType = checkExpr(ret.value());
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
            
            case IdentExpr ident -> lookupLocal(ident.name());
            
            case HoleExpr hole -> {
                // Record hole context for context retrieval
                hole.setExpectedType(currentReturnType);
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
                // Check for built-in Result/Option constructors
                if (call.callee() instanceof IdentExpr ident) {
                    var name = ident.name();
                    if (name.equals("Ok") || name.equals("Err")) {
                        if (call.args().size() != 1) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-TYPE-014",
                                name + " requires exactly one argument",
                                call.span()
                            ));
                        } else {
                            var argType = checkExpr(call.args().get(0));
                            // Return Result<T, ?> or Result<?, E>
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
                    } else if (name.equals("Some")) {
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
                }
                
                checkExpr(call.callee());
                for (var arg : call.args()) {
                    checkExpr(arg);
                }
                yield null; // Would need function signature tracking
            }
            
            case FieldAccessExpr access -> {
                var objType = checkExpr(access.object());
                // Would need to lookup field type
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
        
        pushScope();
        
        TypeExpr elementType = null;
        if (iterableType instanceof GenericTypeExpr generic && generic.name().equals("List")) {
            if (!generic.typeArgs().isEmpty()) {
                elementType = generic.typeArgs().get(0);
            }
        }
        
        addLocal(forExpr.varName(), elementType, false);
        
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
        var condType = checkExpr(ifExpr.condition());
        if (condType != null && !getTypeName(condType).equals("Bool")) {
            diagnostics.add(Diagnostic.error(
                "NGJ-TYPE-010",
                "If condition must be Bool",
                ifExpr.condition().span()
            ));
        }
        
        var thenType = checkBlock(ifExpr.thenBranch());
        
        TypeExpr elseType = null;
        if (ifExpr.elseBranch() != null) {
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
        
        return thenType;
    }
    
    private TypeExpr checkMatchExpr(MatchExpr matchExpr) {
        var scrutineeType = checkExpr(matchExpr.scrutinee());
        
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
            bindPatternVars(arm.pattern());
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
    
    private void checkMatchExhaustiveness(MatchExpr matchExpr, TypeInfo typeInfo, String typeName) {
        var variants = typeInfo.variants();
        var coveredVariants = new HashSet<String>();
        boolean hasWildcard = false;
        
        for (var arm : matchExpr.arms()) {
            switch (arm.pattern()) {
                case WildcardPattern _ -> hasWildcard = true;
                case ConstructorPattern ctor -> coveredVariants.add(ctor.typePath());
                case BindingPattern _ -> hasWildcard = true; // Binding captures all
                default -> {}
            }
        }
        
        if (!hasWildcard) {
            var variantNames = variants.stream().map(VariantInfo::name).toList();
            var missing = variantNames.stream()
                .filter(v -> !coveredVariants.contains(v))
                .toList();
            
            if (!missing.isEmpty()) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-MATCH-001",
                    "Non-exhaustive match on " + typeName + ". Missing: " + String.join(", ", missing),
                    matchExpr.span()
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
    
    private void bindPatternVars(Pattern pattern) {
        switch (pattern) {
            case WildcardPattern _ -> {}
            case LiteralPattern _ -> {}
            case BindingPattern binding -> addLocal(binding.name(), null);
            case ConstructorPattern ctor -> {
                for (var field : ctor.fields()) {
                    if (field.pattern() != null) {
                        bindPatternVars(field.pattern());
                    } else {
                        addLocal(field.name(), null);
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
        public enum Kind { PRIMITIVE, GENERIC, DATA, ENUM }
        
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
