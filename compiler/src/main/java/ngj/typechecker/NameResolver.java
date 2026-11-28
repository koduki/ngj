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
        resolveAll(List.of(unit));
    }
    
    public void resolveAll(List<CompilationUnit> units) {
        // Register built-in types
        registerBuiltins();
        
        // First pass: register all top-level declarations across all units
        for (var unit : units) {
            currentModule = unit.modulePath();
            for (var decl : unit.declarations()) {
                registerTopLevel(decl);
            }
        }
        
        // Second pass: resolve imports and references in bodies
        for (var unit : units) {
            currentModule = unit.modulePath();
            for (var imp : unit.imports()) {
                for (var importedName : imp.names()) {
                    String fullId = imp.modulePath().isEmpty() ? importedName : imp.modulePath() + "." + importedName;
                    globalSymbols.putIfAbsent(importedName, new Symbol(fullId, Symbol.Kind.FUNCTION, true));
                }
            }
            for (var decl : unit.declarations()) {
                resolveDecl(decl);
            }
        }
    }
    
    private void registerBuiltins() {
        // Built-in value types (STDLIB-01)
        globalSymbols.put("Int", new Symbol("Int", Symbol.Kind.TYPE, true));
        globalSymbols.put("Long", new Symbol("Long", Symbol.Kind.TYPE, true));
        globalSymbols.put("Bool", new Symbol("Bool", Symbol.Kind.TYPE, true));
        globalSymbols.put("Byte", new Symbol("Byte", Symbol.Kind.TYPE, true));
        globalSymbols.put("Float", new Symbol("Float", Symbol.Kind.TYPE, true));
        globalSymbols.put("Double", new Symbol("Double", Symbol.Kind.TYPE, true));
        globalSymbols.put("String", new Symbol("String", Symbol.Kind.TYPE, true));
        globalSymbols.put("Unit", new Symbol("Unit", Symbol.Kind.TYPE, true));
        
        // Generic types (STDLIB-02, STDLIB-03, STDLIB-04)
        globalSymbols.put("Result", new Symbol("Result", Symbol.Kind.TYPE, true));
        globalSymbols.put("Option", new Symbol("Option", Symbol.Kind.TYPE, true));
        globalSymbols.put("List", new Symbol("List", Symbol.Kind.TYPE, true));
        
        // Standard error types and variants (STDLIB-07)
        globalSymbols.put("OverflowError", new Symbol("OverflowError", Symbol.Kind.TYPE, true));
        globalSymbols.put("IntOverflow", new Symbol("OverflowError.IntOverflow", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("OverflowError.IntOverflow", new Symbol("OverflowError.IntOverflow", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("LongOverflow", new Symbol("OverflowError.LongOverflow", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("OverflowError.LongOverflow", new Symbol("OverflowError.LongOverflow", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("RangeError", new Symbol("RangeError", Symbol.Kind.TYPE, true));
        globalSymbols.put("OutOfRange", new Symbol("RangeError.OutOfRange", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("RangeError.OutOfRange", new Symbol("RangeError.OutOfRange", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("IndexError", new Symbol("IndexError", Symbol.Kind.TYPE, true));
        globalSymbols.put("IndexOutOfBounds", new Symbol("IndexError.IndexOutOfBounds", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("IndexError.IndexOutOfBounds", new Symbol("IndexError.IndexOutOfBounds", Symbol.Kind.CONSTRUCTOR, true));
        
        // Built-in constructors (STDLIB-02, STDLIB-03)
        globalSymbols.put("Ok", new Symbol("Ok", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("Result.Ok", new Symbol("Ok", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("Err", new Symbol("Err", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("Result.Err", new Symbol("Err", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("Some", new Symbol("Some", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("Option.Some", new Symbol("Some", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("None", new Symbol("None", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("Option.None", new Symbol("None", Symbol.Kind.CONSTRUCTOR, true));
        
        // List constructors (STDLIB-04)
        globalSymbols.put("Nil", new Symbol("Nil", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("Cons", new Symbol("Cons", Symbol.Kind.CONSTRUCTOR, true));
        globalSymbols.put("listOf", new Symbol("listOf", Symbol.Kind.FUNCTION, true));
        
        // Checked arithmetic functions (STDLIB-07)
        globalSymbols.put("checkedAdd", new Symbol("checkedAdd", Symbol.Kind.FUNCTION, true));
        globalSymbols.put("checkedSub", new Symbol("checkedSub", Symbol.Kind.FUNCTION, true));
        globalSymbols.put("checkedMul", new Symbol("checkedMul", Symbol.Kind.FUNCTION, true));
        
        // List operations (STDLIB-08)
        globalSymbols.put("head", new Symbol("head", Symbol.Kind.FUNCTION, true));
        globalSymbols.put("get", new Symbol("get", Symbol.Kind.FUNCTION, true));
        globalSymbols.put("length", new Symbol("length", Symbol.Kind.FUNCTION, true));
        globalSymbols.put("isEmpty", new Symbol("isEmpty", Symbol.Kind.FUNCTION, true));
        
        // Option/Result utility functions
        globalSymbols.put("isSome", new Symbol("isSome", Symbol.Kind.FUNCTION, true));
        globalSymbols.put("isNone", new Symbol("isNone", Symbol.Kind.FUNCTION, true));
        globalSymbols.put("isOk", new Symbol("isOk", Symbol.Kind.FUNCTION, true));
        globalSymbols.put("isErr", new Symbol("isErr", Symbol.Kind.FUNCTION, true));
        globalSymbols.put("getOrElse", new Symbol("getOrElse", Symbol.Kind.FUNCTION, true));
        globalSymbols.put("toOption", new Symbol("toOption", Symbol.Kind.FUNCTION, true));
        globalSymbols.put("toResult", new Symbol("toResult", Symbol.Kind.FUNCTION, true));

        // Phase 2-4 stdlib, capability, effect, and verification types
        for (String typeName : List.of(
            "Secret", "Untrusted", "Validated", "Arena", "OwnedBytes", "Bytes",
            "Slice", "Task", "MutableList", "Budget", "Pair",
            "DbRead", "DbWrite", "FsRead", "FsWrite", "Net", "PaymentWrite", "Declassify",
            "RefinementError", "ValidationError", "TaskError", "ScopeError", "MathInt"
        )) {
            globalSymbols.put(typeName, new Symbol(typeName, Symbol.Kind.TYPE, true));
        }

        // Phase 2-4 stdlib functions and error constructors
        for (String fnName : List.of(
            "refine", "declassify", "validate", "close", "split", "spend", "join",
            "alloc", "slice", "sliceExact", "len", "map", "filter", "push", "pop", "assert"
        )) {
            globalSymbols.put(fnName, new Symbol(fnName, Symbol.Kind.FUNCTION, true));
        }
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
            case RecordDecl record -> {
                String id = currentModule + "." + record.name();
                globalSymbols.put(record.name(), new Symbol(id, Symbol.Kind.TYPE, record.isPublic()));
            }
            case EnumDecl enumDecl -> {
                String id = currentModule + "." + enumDecl.name();
                globalSymbols.put(enumDecl.name(), new Symbol(id, Symbol.Kind.TYPE, enumDecl.isPublic()));
                for (var variant : enumDecl.variants()) {
                    String variantId = id + "." + variant.name();
                    var sym = new Symbol(variantId, Symbol.Kind.CONSTRUCTOR, enumDecl.isPublic());
                    globalSymbols.put(variant.name(), sym);
                    globalSymbols.put(enumDecl.name() + "." + variant.name(), sym);
                }
            }
            case TypeAliasDecl alias -> {
                String id = currentModule + "." + alias.name();
                globalSymbols.put(alias.name(), new Symbol(id, Symbol.Kind.TYPE, alias.isPublic()));
            }
            case ProtocolDecl protocol -> {
                String id = currentModule + "." + protocol.name();
                globalSymbols.put(protocol.name(), new Symbol(id, Symbol.Kind.TYPE, protocol.isPublic()));
                if (protocol.initialState() != null && !protocol.initialState().isEmpty()) {
                    globalSymbols.putIfAbsent(protocol.initialState(),
                        new Symbol(id + "." + protocol.initialState(), Symbol.Kind.TYPE, protocol.isPublic()));
                }
                for (var st : protocol.states()) {
                    globalSymbols.putIfAbsent(st, new Symbol(id + "." + st, Symbol.Kind.TYPE, protocol.isPublic()));
                }
                for (var fst : protocol.finalStates()) {
                    globalSymbols.putIfAbsent(fst, new Symbol(id + "." + fst, Symbol.Kind.TYPE, protocol.isPublic()));
                }
            }
            case SpecFnDecl specFn -> {
                String id = currentModule + "." + specFn.name();
                globalSymbols.put(specFn.name(), new Symbol(id, Symbol.Kind.FUNCTION, specFn.isPublic()));
            }
            case LemmaDecl lemma -> {
                String id = currentModule + "." + lemma.name();
                globalSymbols.put(lemma.name(), new Symbol(id, Symbol.Kind.FUNCTION, lemma.isPublic()));
            }
        }
    }
    
    private void resolveDecl(TopLevelDecl decl) {
        switch (decl) {
            case FnDecl fn -> resolveFn(fn);
            case DataDecl data -> resolveData(data);
            case RecordDecl record -> resolveRecord(record);
            case EnumDecl enumDecl -> resolveEnum(enumDecl);
            case TypeAliasDecl alias -> resolveTypeAlias(alias);
            case ProtocolDecl protocol -> resolveProtocol(protocol);
            case SpecFnDecl specFn -> resolveSpecFn(specFn);
            case LemmaDecl lemma -> resolveLemma(lemma);
        }
    }
    
    private void resolveRecord(RecordDecl record) {
        pushScope();
        for (var param : record.params()) {
            if (param.type() instanceof RefinedTypeExpr ref && ref.binderName() == null) {
                addLocal(param.name(), new Symbol(param.name(), Symbol.Kind.VARIABLE, false));
            }
            resolveType(param.type());
            addLocal(param.name(), new Symbol(param.name(), Symbol.Kind.VARIABLE, false));
        }
        popScope();
    }
    
    private void resolveFn(FnDecl fn) {
        pushScope();
        
        // Left-to-right sequential parameter scope for dependent parameter bounds
        for (var param : fn.params()) {
            if (param.type() instanceof RefinedTypeExpr ref && ref.binderName() == null) {
                addLocal(param.name(), new Symbol(param.name(), Symbol.Kind.VARIABLE, false));
            }
            resolveType(param.type());
            addLocal(param.name(), new Symbol(param.name(), Symbol.Kind.VARIABLE, false));
        }
        
        // Resolve return type
        resolveType(fn.returnType());

        // Resolve contract clauses
        for (var contract : fn.contracts()) {
            if (contract.kind() == ContractClause.Kind.ENSURES) {
                pushScope();
                addLocal("result", new Symbol("result", Symbol.Kind.VARIABLE, false));
                resolveExpr(contract.condition());
                popScope();
            } else {
                resolveExpr(contract.condition());
            }
        }
        
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

    private void resolveTypeAlias(TypeAliasDecl alias) {
        pushScope();
        for (var tp : alias.typeParams()) {
            addLocal(tp, new Symbol(tp, Symbol.Kind.TYPE, false));
        }
        resolveType(alias.targetType());
        popScope();
    }

    private void resolveProtocol(ProtocolDecl protocol) {
        // Parent protocol and transition effects are validated by ProtocolChecker
    }

    private void resolveSpecFn(SpecFnDecl specFn) {
        pushScope();
        for (var param : specFn.params()) {
            resolveType(param.type());
            addLocal(param.name(), new Symbol(param.name(), Symbol.Kind.VARIABLE, false));
        }
        resolveType(specFn.returnType());
        if (specFn.body() != null) {
            resolveBlock(specFn.body());
        }
        popScope();
    }

    private void resolveLemma(LemmaDecl lemma) {
        pushScope();
        for (var param : lemma.params()) {
            resolveType(param.type());
            addLocal(param.name(), new Symbol(param.name(), Symbol.Kind.VARIABLE, false));
        }
        for (var contract : lemma.contracts()) {
            resolveExpr(contract.condition());
        }
        if (lemma.body() != null) {
            resolveBlock(lemma.body());
        }
        popScope();
    }
    
    private void resolveType(TypeExpr type) {
        if (type == null) return;
        switch (type) {
            case SimpleTypeExpr simple -> {
                if (!lookupSymbol(simple.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-NAME-001",
                        "Unknown type: " + simple.name(),
                        simple.span()
                    ));
                }
            }
            case GenericTypeExpr generic -> {
                if (!lookupSymbol(generic.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-NAME-001",
                        "Unknown type: " + generic.name(),
                        generic.span()
                    ));
                }
                if (!generic.name().equals("Budget")) {
                    for (var arg : generic.typeArgs()) {
                        resolveType(arg);
                    }
                }
            }
            case UnitTypeExpr _ -> {} // Built-in
            case CapabilityTypeExpr cap -> resolveType(cap.inner());
            case CaptureSetTypeExpr _ -> {} // Capture targets are symbolic capability/region names
            case RefinedTypeExpr refined -> {
                resolveType(refined.baseType());
                pushScope();
                if (refined.binderName() != null) {
                    addLocal(refined.binderName(), new Symbol(refined.binderName(), Symbol.Kind.VARIABLE, false));
                }
                if (refined.predicate() != null) {
                    resolveExpr(refined.predicate());
                }
                popScope();
            }
            case RangeTypeExpr range -> {
                resolveType(range.baseType());
                if (range.lowerBound() != null) {
                    resolveExpr(range.lowerBound());
                }
                if (range.upperBound() != null) {
                    resolveExpr(range.upperBound());
                }
            }
            case FunctionTypeExpr fnType -> {
                for (var p : fnType.paramTypes()) {
                    resolveType(p);
                }
                resolveType(fnType.returnType());
            }
            case TupleTypeExpr tuple -> {
                for (var elem : tuple.elements()) {
                    resolveType(elem);
                }
            }
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
            case ValStmt val -> {
                if (val.explicitType() != null) {
                    resolveType(val.explicitType());
                }
                resolveExpr(val.initializer());
                addLocal(val.name(), new Symbol(val.name(), Symbol.Kind.VARIABLE, false));
            }
            case VarStmt var -> {
                if (var.explicitType() != null) {
                    resolveType(var.explicitType());
                }
                resolveExpr(var.initializer());
                addLocal(var.name(), new Symbol(var.name(), Symbol.Kind.VARIABLE, false));
            }
            case AssignStmt assign -> {
                if (!lookupSymbol(assign.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-NAME-002",
                        "Unknown identifier: " + assign.name(),
                        assign.span()
                    ));
                }
                resolveExpr(assign.value());
            }
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
            case TupleDestructureStmt destruct -> {
                for (var t : destruct.explicitTypes()) {
                    if (t != null) {
                        resolveType(t);
                    }
                }
                resolveExpr(destruct.initializer());
                for (var name : destruct.names()) {
                    addLocal(name, new Symbol(name, Symbol.Kind.VARIABLE, false));
                }
            }
            case InvariantStmt inv -> resolveExpr(inv.condition());
            case ProofStmt proof -> resolveBlock(proof.body());
            case AssumeStmt assume -> resolveExpr(assume.condition());
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
                boolean isBudgetCall = call.callee() instanceof FieldAccessExpr fa
                    && fa.object() instanceof IdentExpr id && id.name().equals("Budget");
                if (!isBudgetCall) {
                    for (var typeArg : call.typeArgs()) {
                        resolveType(typeArg);
                    }
                }
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
            case ForExpr forExpr -> {
                resolveExpr(forExpr.iterable());
                pushScope();
                addLocal(forExpr.varName(), new Symbol(forExpr.varName(), Symbol.Kind.VARIABLE, false));
                resolveBlock(forExpr.body());
                popScope();
            }
            case WhileExpr whileExpr -> {
                resolveExpr(whileExpr.condition());
                resolveBlock(whileExpr.body());
            }
            case LambdaExpr lambda -> {
                pushScope();
                for (var param : lambda.params()) {
                    resolveType(param.type());
                    addLocal(param.name(), new Symbol(param.name(), Symbol.Kind.VARIABLE, false));
                }
                resolveBlock(lambda.body());
                popScope();
            }
            case ScopeExpr scope -> resolveBlock(scope.body());
            case ForkExpr fork -> resolveBlock(fork.body());
            case ConsumeExpr consume -> resolveExpr(consume.target());
            case TupleExpr tuple -> {
                for (var elem : tuple.elements()) {
                    resolveExpr(elem);
                }
            }
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
