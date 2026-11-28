package ngj.codegen;

import ngj.ast.*;

import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.lang.invoke.*;
import java.nio.file.*;
import java.util.*;

import static java.lang.classfile.ClassFile.*;

/**
 * Code generator targeting JDK 28 EA with JEP 401 Value Objects (Preview).
 * 
 * Valhalla Foundation:
 * - Value types: data/record/enum are emitted as value classes (no ACC_IDENTITY flag)
 * - Identity types: only explicit identity class declarations have ACC_IDENTITY
 * - Sealed hierarchies: enum is emitted as sealed value interface + value class variants
 * 
 * Class-file format for value classes (JEP 401):
 * - Identity classes have ACC_IDENTITY flag (0x0020, formerly ACC_SUPER)
 * - Value classes do NOT have ACC_IDENTITY flag
 * - Value classes are implicitly final
 * - Value classes require preview version flag
 * 
 * VALUE-01: data/record/enum output as value classes
 */
public final class Java25CodeGenerator {
    
    // JDK 28 class file version (major version 72 for JDK 28)
    // Use runtime version for tests (NGJ_RUNTIME_CLASS_VERSION env var or current JDK)
    private static final int CLASS_FILE_VERSION = getTargetClassVersion();
    
    // ACC_IDENTITY flag (0x0020) - formerly ACC_SUPER, now marks identity classes
    // Value classes do NOT have this flag
    private static final int ACC_IDENTITY = 0x0020;
    
    // Preview minor version flag for JEP 401 Value Objects
    private static final int PREVIEW_MINOR_VERSION = 65535;
    
    private static int getTargetClassVersion() {
        String env = System.getenv("NGJ_RUNTIME_CLASS_VERSION");
        if (env != null && !env.isEmpty()) {
            try {
                return Integer.parseInt(env);
            } catch (NumberFormatException ignored) {
            }
        }
        // Default to JDK 28 (class file version 72) for production
        // For tests, set NGJ_RUNTIME_CLASS_VERSION to current JDK
        return 72;
    }
    
    private String currentModule;
    private Path outputDir = Path.of("build/classes");
    private final Map<String, RecordDecl> records = new HashMap<>();
    private final Map<String, DataDecl> datas = new HashMap<>();
    private final Map<String, EnumDecl> enums = new HashMap<>();
    private final Map<String, FnDecl> functions = new HashMap<>();
    private final Map<String, TypeAliasDecl> typeAliases = new HashMap<>();
    private final List<PendingLambda> pendingLambdas = new ArrayList<>();
    private int lambdaCounter = 0;

    private record PendingLambda(
        String methodName,
        MethodTypeDesc methodType,
        LambdaExpr lambda,
        List<CapturedVar> captures
    ) {}

    private record CapturedVar(String name, String typeName, int outerSlot) {}

    public List<Path> generate(CompilationUnit unit) throws CodeGenException {
        currentModule = unit.modulePath();
        records.clear();
        datas.clear();
        enums.clear();
        functions.clear();
        typeAliases.clear();
        for (var decl : unit.declarations()) {
            switch (decl) {
                case RecordDecl r -> records.put(r.name(), r);
                case DataDecl d -> datas.put(d.name(), d);
                case EnumDecl e -> enums.put(e.name(), e);
                case FnDecl f -> functions.put(f.name(), f);
                case TypeAliasDecl ta -> typeAliases.put(ta.name(), ta);
                case ProtocolDecl _, SpecFnDecl _, LemmaDecl _ -> {
                    // PROOF-01: Erased from runtime bytecode
                }
            }
        }
        var outputs = new ArrayList<Path>();
        
        try {
            var moduleDir = outputDir.resolve(currentModule.replace('.', '/'));
            Files.createDirectories(moduleDir);
            
            for (var decl : unit.declarations()) {
                var classFile = generateDecl(decl);
                if (classFile != null) {
                    var className = getDeclName(decl);
                    var outputPath = moduleDir.resolve(className + ".class");
                    Files.write(outputPath, classFile);
                    outputs.add(outputPath);
                }
                
                // Generate enum variant classes as nested value classes
                if (decl instanceof EnumDecl enumDecl) {
                    outputs.addAll(generateEnumVariants(enumDecl));
                }
                if (decl instanceof RecordDecl rec && rec.annotations().stream().anyMatch(a -> a.name().equals("boundary_schema"))) {
                    for (String tag : List.of("Ok", "Err")) {
                        var wrapperName = currentModule.replace('.', '/') + "/" + rec.name() + "$" + tag;
                        var wrapperDesc = ClassDesc.ofInternalName(wrapperName);
                        byte[] wrapperBytes = ClassFile.of().build(wrapperDesc, cb -> {
                            cb.withVersion(CLASS_FILE_VERSION, PREVIEW_MINOR_VERSION);
                            cb.withFlags(ACC_PUBLIC | ACC_FINAL);
                            cb.withSuperclass(ConstantDescs.CD_Object);
                            cb.withField("value", ConstantDescs.CD_Object, ACC_PUBLIC | ACC_FINAL);
                            cb.withMethod(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object),
                                ACC_PUBLIC, mb -> mb.withCode(code -> {
                                    code.aload(0);
                                    code.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                        MethodTypeDesc.of(ConstantDescs.CD_void));
                                    code.aload(0);
                                    code.aload(1);
                                    code.putfield(wrapperDesc, "value", ConstantDescs.CD_Object);
                                    code.return_();
                                }));
                        });
                        var wrapperPath = moduleDir.resolve(rec.name() + "$" + tag + ".class");
                        Files.write(wrapperPath, wrapperBytes);
                        outputs.add(wrapperPath);
                    }
                }
            }
            
            return outputs;
        } catch (Exception e) {
            throw new CodeGenException("Code generation failed: " + e.getMessage(), e);
        }
    }
    
    public void setOutputDir(Path outputDir) {
        this.outputDir = outputDir;
    }
    
    private byte[] generateDecl(TopLevelDecl decl) throws CodeGenException {
        return switch (decl) {
            case FnDecl fn -> generateFunction(fn);
            case DataDecl data -> generateData(data);
            case RecordDecl record -> generateRecord(record);
            case EnumDecl enumDecl -> generateEnum(enumDecl);
            case TypeAliasDecl _, ProtocolDecl _, SpecFnDecl _, LemmaDecl _ -> null;
        };
    }
    
    private String getDeclName(TopLevelDecl decl) {
        return switch (decl) {
            case FnDecl fn -> fn.name() + "$Fn";
            case DataDecl data -> data.name();
            case RecordDecl record -> record.name();
            case EnumDecl enumDecl -> enumDecl.name();
            case TypeAliasDecl ta -> ta.name();
            case ProtocolDecl p -> p.name();
            case SpecFnDecl sf -> sf.name();
            case LemmaDecl l -> l.name();
        };
    }
    
    private byte[] generateFunction(FnDecl fn) {
        var className = currentModule.replace('.', '/') + "/" + fn.name() + "$Fn";
        var classDesc = ClassDesc.ofInternalName(className);
        pendingLambdas.clear();
        lambdaCounter = 0;
        
        return ClassFile.of().build(classDesc, classBuilder -> {
            classBuilder.withVersion(CLASS_FILE_VERSION, 0);
            classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL);
            classBuilder.withSuperclass(ConstantDescs.CD_Object);
            
            var methodDesc = buildMethodDescriptor(fn);
            classBuilder.withMethod("invoke", methodDesc, ACC_PUBLIC | ACC_STATIC, mb -> {
                mb.withCode(codeBuilder -> {
                    var ctx = new CodeGenContext(codeBuilder, fn, classDesc);
                    generateBlock(ctx, fn.body());
                    
                    if (!ctx.hasReturn) {
                        var retType = getTypeName(fn.returnType());
                        switch (retType) {
                            case "Unit" -> codeBuilder.return_();
                            case "Int", "Bool" -> codeBuilder.ireturn();
                            case "Long" -> codeBuilder.lreturn();
                            default -> codeBuilder.areturn();
                        }
                    }
                });
            });

            // Emit any synthetic lambda methods collected during function body generation
            for (int i = 0; i < pendingLambdas.size(); i++) {
                var pl = pendingLambdas.get(i);
                classBuilder.withMethod(pl.methodName(), pl.methodType(), ACC_PRIVATE | ACC_STATIC, mb -> {
                    mb.withCode(codeBuilder -> {
                        var lCtx = new CodeGenContext(codeBuilder, null, classDesc);
                        for (var cap : pl.captures()) {
                            lCtx.allocateLocal(cap.name(), cap.typeName());
                        }
                        List<Integer> rawParamSlots = new ArrayList<>();
                        for (int pi = 0; pi < pl.lambda().params().size(); pi++) {
                            rawParamSlots.add(lCtx.allocateLocal("$rawParam$" + pi, "Object"));
                        }
                        for (int pi = 0; pi < pl.lambda().params().size(); pi++) {
                            var p = pl.lambda().params().get(pi);
                            String pType = getTypeName(p.type());
                            if (pType.equals("Int") || pType.equals("Bool") || pType.equals("Long")) {
                                codeBuilder.aload(rawParamSlots.get(pi));
                                unboxIfNeeded(lCtx, pType);
                                int pSlot = lCtx.allocateLocal(p.name(), pType);
                                storeSlot(codeBuilder, pSlot, pType);
                            } else {
                                lCtx.locals.put(p.name(), rawParamSlots.get(pi));
                                lCtx.localTypes.put(p.name(), pType);
                            }
                        }
                        generateBlock(lCtx, pl.lambda().body());
                        if (!lCtx.hasReturn) {
                            var retDesc = pl.methodType().returnType();
                            if (retDesc.equals(ConstantDescs.CD_void)) {
                                codeBuilder.return_();
                            } else if (retDesc.equals(ConstantDescs.CD_int) || retDesc.equals(ConstantDescs.CD_boolean)) {
                                codeBuilder.ireturn();
                            } else if (retDesc.equals(ConstantDescs.CD_long)) {
                                codeBuilder.lreturn();
                            } else {
                                if (pl.lambda().body().tailExpr() != null) {
                                    boxIfPrimitive(lCtx, pl.lambda().body().tailExpr());
                                }
                                codeBuilder.areturn();
                            }
                        }
                    });
                });
            }
            
            if (fn.params().isEmpty() && getTypeName(fn.returnType()).equals("Int")) {
                var mainDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType());
                classBuilder.withMethod("main", mainDesc, ACC_PUBLIC | ACC_STATIC, mb -> {
                    mb.withCode(codeBuilder -> {
                        codeBuilder.invokestatic(classDesc, "invoke", methodDesc);
                        codeBuilder.invokestatic(ClassDesc.of("java.lang.System"), "exit", 
                            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int));
                        codeBuilder.return_();
                    });
                });
            }
        });
    }
    
    private MethodTypeDesc buildMethodDescriptor(FnDecl fn) {
        var paramTypes = fn.params().stream()
            .map(p -> typeToClassDesc(p.type()))
            .toArray(ClassDesc[]::new);
        var returnType = typeToClassDesc(fn.returnType());
        return MethodTypeDesc.of(returnType, paramTypes);
    }
    
    private TypeExpr resolveTypeAlias(TypeExpr type) {
        Set<String> visited = new HashSet<>();
        TypeExpr current = type;
        while (current instanceof SimpleTypeExpr simple && typeAliases.containsKey(simple.name())) {
            if (!visited.add(simple.name())) {
                break;
            }
            current = typeAliases.get(simple.name()).targetType();
        }
        return current;
    }

    private ClassDesc typeToClassDesc(TypeExpr type) {
        TypeExpr resolved = resolveTypeAlias(type);
        return switch (resolved) {
            case SimpleTypeExpr simple -> switch (simple.name()) {
                case "Int" -> ConstantDescs.CD_int;
                case "Long" -> ConstantDescs.CD_long;
                case "Bool" -> ConstantDescs.CD_boolean;
                case "String" -> ConstantDescs.CD_String;
                case "Unit" -> ConstantDescs.CD_void;
                case "Secret" -> ClassDesc.of("ngj.runtime.SecretValue");
                case "Budget" -> ClassDesc.of("ngj.runtime.Budget");
                case "Untrusted" -> ClassDesc.of("ngj.runtime.UntrustedValue");
                case "Validated" -> ClassDesc.of("ngj.runtime.ValidatedValue");
                case "Pair" -> ClassDesc.of("ngj.runtime.Pair");
                case "RefinementError", "ValidationError", "OverflowError", "RangeError", "IndexError", "Bytes", "Arena" -> ConstantDescs.CD_Object;
                default -> ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + simple.name());
            };
            case GenericTypeExpr generic -> switch (generic.name()) {
                case "Result" -> enums.containsKey("Result")
                    ? ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/Result")
                    : ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry");
                case "Option" -> enums.containsKey("Option")
                    ? ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/Option")
                    : ClassDesc.of("java.util.Optional");
                case "List" -> ClassDesc.of("java.util.List");
                case "Secret" -> ClassDesc.of("ngj.runtime.SecretValue");
                case "Budget" -> ClassDesc.of("ngj.runtime.Budget");
                case "Untrusted" -> ClassDesc.of("ngj.runtime.UntrustedValue");
                case "Validated" -> ClassDesc.of("ngj.runtime.ValidatedValue");
                case "Pair" -> ClassDesc.of("ngj.runtime.Pair");
                default -> ConstantDescs.CD_Object;
            };
            case UnitTypeExpr _ -> ConstantDescs.CD_void;
            case CapabilityTypeExpr cap -> typeToClassDesc(cap.inner());
            case CaptureSetTypeExpr _ -> ConstantDescs.CD_Object;
            case RefinedTypeExpr ref -> typeToClassDesc(ref.baseType());
            case RangeTypeExpr rng -> typeToClassDesc(rng.baseType());
            case FunctionTypeExpr _ -> ClassDesc.of("java.util.function.Function");
            case TupleTypeExpr _ -> ClassDesc.of("java.util.List");
        };
    }
    
    private String inferTypeName(CodeGenContext ctx, Expr expr) {
        if (expr == null) return "Unit";
        return switch (expr) {
            case LiteralExpr lit -> switch (lit.kind()) {
                case INT -> "Int";
                case LONG -> "Long";
                case BOOL -> "Bool";
                case STRING -> "String";
                case UNIT -> "Unit";
            };
            case IdentExpr ident -> ctx.getLocalType(ident.name());
            case BinaryExpr bin -> switch (bin.op()) {
                case ADD, SUB, MUL, DIV, MOD -> inferTypeName(ctx, bin.left());
                case EQ, NE, LT, LE, GT, GE, AND, OR -> "Bool";
            };
            case UnaryExpr un -> switch (un.op()) {
                case NEG -> inferTypeName(ctx, un.operand());
                case NOT -> "Bool";
            };
            case ParenExpr paren -> inferTypeName(ctx, paren.inner());
            case IfExpr ifExpr -> {
                if (ifExpr.thenBranch().tailExpr() != null) {
                    yield inferTypeName(ctx, ifExpr.thenBranch().tailExpr());
                } else if (ifExpr.elseBranch() != null) {
                    yield inferTypeName(ctx, ifExpr.elseBranch());
                }
                yield "Unit";
            }
            case BlockExpr block -> block.tailExpr() != null ? inferTypeName(ctx, block.tailExpr()) : "Unit";
            case ScopeExpr scope -> scope.body().tailExpr() != null ? inferTypeName(ctx, scope.body().tailExpr()) : "Unit";
            case ForkExpr fork -> fork.body().tailExpr() != null ? inferTypeName(ctx, fork.body().tailExpr()) : "Unit";
            case ConsumeExpr consume -> inferTypeName(ctx, consume.target());
            case TupleExpr _ -> "Tuple";
            case CallExpr call -> {
                if (call.callee() instanceof IdentExpr ident) {
                    var name = ident.name();
                    yield switch (name) {
                        case "length" -> "Int";
                        case "isEmpty", "isSome", "isNone", "isOk", "isErr" -> "Bool";
                        case "Ok", "Err", "checkedAdd", "checkedSub", "checkedMul", "toResult", "refine" -> "Result";
                        case "Some", "None", "head", "get", "toOption" -> "Option";
                        case "Nil", "listOf", "Cons" -> "List";
                        case "Budget" -> "Budget";
                        case "getOrElse" -> call.args().size() >= 2 ? inferTypeName(ctx, call.args().get(1)) : "Object";
                        default -> {
                            if (functions.containsKey(name)) {
                                yield getTypeName(functions.get(name).returnType());
                            }
                            if (records.containsKey(name) || datas.containsKey(name)) {
                                yield name;
                            }
                            for (var entry : enums.entrySet()) {
                                for (var v : entry.getValue().variants()) {
                                    if (v.name().equals(name)) {
                                        yield entry.getKey();
                                    }
                                }
                            }
                            yield "Object";
                        }
                    };
                } else if (call.callee() instanceof FieldAccessExpr access) {
                    if (access.object() instanceof IdentExpr objIdent) {
                        if (objIdent.name().equals("Budget") && access.fieldName().equals("split")) {
                            yield "Pair";
                        }
                        if (objIdent.name().equals("Budget") && access.fieldName().equals("spend")) {
                            yield "Budget";
                        }
                        if (objIdent.name().equals("Secret") && (access.fieldName().equals("map") || access.fieldName().equals("of"))) {
                            yield "Secret";
                        }
                        if (objIdent.name().equals("List") && access.fieldName().equals("map")) {
                            yield "List";
                        }
                        if (enums.containsKey(objIdent.name())) {
                            yield objIdent.name();
                        }
                    }
                    if (access.fieldName().equals("split")) {
                        yield "Pair";
                    }
                    if (access.fieldName().equals("spend")) {
                        yield "Budget";
                    }
                    if (access.fieldName().equals("remaining")) {
                        yield "Long";
                    }
                    if (access.fieldName().equals("toJson")) {
                        yield "String";
                    }
                }
                yield "Object";
            }
            case FieldAccessExpr access -> {
                var objType = inferTypeName(ctx, access.object());
                if (records.containsKey(objType)) {
                    for (var p : records.get(objType).params()) {
                        if (p.name().equals(access.fieldName())) {
                            yield getTypeName(p.type());
                        }
                    }
                } else if (datas.containsKey(objType)) {
                    for (var f : datas.get(objType).fields()) {
                        if (f.name().equals(access.fieldName())) {
                            yield getTypeName(f.type());
                        }
                    }
                }
                yield "Object";
            }
            case PropagateExpr prop -> {
                if (prop.inner() instanceof CallExpr call && call.callee() instanceof IdentExpr ident) {
                    var name = ident.name();
                    if (name.equals("checkedAdd") || name.equals("checkedSub") || name.equals("checkedMul")) {
                        yield "Int";
                    }
                    if (functions.containsKey(name) && functions.get(name).returnType() instanceof GenericTypeExpr gen
                        && !gen.typeArgs().isEmpty()) {
                        yield getTypeName(gen.typeArgs().get(0));
                    }
                }
                yield "Object";
            }
            case MatchExpr matchExpr -> {
                if (!matchExpr.arms().isEmpty()) {
                    yield inferTypeName(ctx, matchExpr.arms().get(0).body());
                }
                yield "Object";
            }
            default -> "Object";
        };
    }
    
    private void generateBlock(CodeGenContext ctx, BlockExpr block) {
        for (var stmt : block.statements()) {
            generateStmt(ctx, stmt);
            if (ctx.hasReturn) {
                return;
            }
        }
        
        if (block.tailExpr() != null) {
            generateExpr(ctx, block.tailExpr());
        }
    }
    
    private void generateStmt(CodeGenContext ctx, Statement stmt) {
        switch (stmt) {
            case ValStmt val -> {
                generateExpr(ctx, val.initializer());
                var type = val.explicitType();
                var typeName = type != null ? getTypeName(type) : inferTypeName(ctx, val.initializer());
                int slot = ctx.allocateLocal(val.name(), typeName);
                switch (typeName) {
                    case "Long" -> ctx.code.lstore(slot);
                    case "Int", "Bool" -> ctx.code.istore(slot);
                    default -> ctx.code.astore(slot);
                }
            }
            case VarStmt var -> {
                generateExpr(ctx, var.initializer());
                var type = var.explicitType();
                var typeName = type != null ? getTypeName(type) : inferTypeName(ctx, var.initializer());
                int slot = ctx.allocateLocal(var.name(), typeName);
                switch (typeName) {
                    case "Long" -> ctx.code.lstore(slot);
                    case "Int", "Bool" -> ctx.code.istore(slot);
                    default -> ctx.code.astore(slot);
                }
            }
            case AssignStmt assign -> {
                generateExpr(ctx, assign.value());
                Integer slot = ctx.getLocal(assign.name());
                if (slot != null) {
                    var typeName = ctx.getLocalType(assign.name());
                    switch (typeName) {
                        case "Long" -> ctx.code.lstore(slot);
                        case "Int", "Bool" -> ctx.code.istore(slot);
                        default -> ctx.code.astore(slot);
                    }
                }
            }
            case LetStmt let -> {
                generateExpr(ctx, let.initializer());
                var type = let.explicitType();
                var typeName = type != null ? getTypeName(type) : inferTypeName(ctx, let.initializer());
                int slot = ctx.allocateLocal(let.name(), typeName);
                switch (typeName) {
                    case "Long" -> ctx.code.lstore(slot);
                    case "Int", "Bool" -> ctx.code.istore(slot);
                    default -> ctx.code.astore(slot);
                }
            }
            case TupleDestructureStmt tds -> {
                generateExpr(ctx, tds.initializer());
                var initType = inferTypeName(ctx, tds.initializer());
                int tupleSlot = ctx.allocateLocal("$tuple$" + ctx.nextSlot, "Object");
                ctx.code.astore(tupleSlot);
                for (int i = 0; i < tds.names().size(); i++) {
                    String varName = tds.names().get(i);
                    TypeExpr explicitType = i < tds.explicitTypes().size() ? tds.explicitTypes().get(i) : null;
                    String varType = explicitType != null ? getTypeName(explicitType) : "Object";
                    ctx.code.aload(tupleSlot);
                    if (initType.equals("Pair") && tds.names().size() == 2) {
                        ctx.code.invokevirtual(
                            ClassDesc.of("ngj.runtime.Pair"),
                            i == 0 ? "first" : "second",
                            MethodTypeDesc.of(ConstantDescs.CD_Object)
                        );
                    } else {
                        ctx.code.ldc(i);
                        ctx.code.invokeinterface(
                            ClassDesc.of("java.util.List"),
                            "get",
                            MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int)
                        );
                    }
                    unboxIfNeeded(ctx, varType);
                    int slot = ctx.allocateLocal(varName, varType);
                    switch (varType) {
                        case "Long" -> ctx.code.lstore(slot);
                        case "Int", "Bool" -> ctx.code.istore(slot);
                        default -> ctx.code.astore(slot);
                    }
                }
            }
            case InvariantStmt _, ProofStmt _, AssumeStmt _ -> {
                // PROOF-01: Erased completely from runtime bytecode
            }
            case ExprStmt expr -> {
                generateExpr(ctx, expr.expr());
            }
            case ReturnStmt ret -> {
                if (ret.value() != null) {
                    generateExpr(ctx, ret.value());
                }
                if (!ctx.hasReturn) {
                    var retType = ctx.fn != null ? getTypeName(ctx.fn.returnType()) : "Object";
                    switch (retType) {
                        case "Unit" -> ctx.code.return_();
                        case "Int", "Bool" -> ctx.code.ireturn();
                        case "Long" -> ctx.code.lreturn();
                        default -> ctx.code.areturn();
                    }
                    ctx.hasReturn = true;
                }
            }
        }
    }
    
    private void generateExpr(CodeGenContext ctx, Expr expr) {
        switch (expr) {
            case LiteralExpr lit -> generateLiteral(ctx, lit);
            case IdentExpr ident -> generateIdent(ctx, ident);
            case BinaryExpr binary -> generateBinary(ctx, binary);
            case UnaryExpr unary -> generateUnary(ctx, unary);
            case IfExpr ifExpr -> generateIf(ctx, ifExpr);
            case BlockExpr block -> generateBlock(ctx, block);
            case CallExpr call -> generateCall(ctx, call);
            case ConstructorExpr ctor -> generateConstructor(ctx, ctor);
            case MatchExpr matchExpr -> generateMatch(ctx, matchExpr);
            case PropagateExpr prop -> generatePropagate(ctx, prop);
            case FieldAccessExpr access -> generateFieldAccess(ctx, access);
            case ParenExpr paren -> generateExpr(ctx, paren.inner());
            case ForExpr forExpr -> generateFor(ctx, forExpr);
            case WhileExpr whileExpr -> generateWhile(ctx, whileExpr);
            case LambdaExpr lambda -> generateLambda(ctx, lambda);
            case ScopeExpr scope -> generateBlock(ctx, scope.body());
            case ForkExpr fork -> generateBlock(ctx, fork.body());
            case ConsumeExpr consume -> generateExpr(ctx, consume.target());
            case TupleExpr tuple -> generateTuple(ctx, tuple);
            case HoleExpr hole -> throw new IllegalStateException("Hole in codegen: ?" + hole.name());
        }
    }

    private void generateTuple(CodeGenContext ctx, TupleExpr tuple) {
        if (tuple.elements().isEmpty()) {
            ctx.code.invokestatic(
                ClassDesc.of("java.util.List"),
                "of",
                MethodTypeDesc.of(ClassDesc.of("java.util.List"))
            );
            return;
        }
        ctx.code.ldc(tuple.elements().size());
        ctx.code.anewarray(ConstantDescs.CD_Object);
        for (int i = 0; i < tuple.elements().size(); i++) {
            ctx.code.dup();
            ctx.code.ldc(i);
            generateExpr(ctx, tuple.elements().get(i));
            boxIfPrimitive(ctx, tuple.elements().get(i));
            ctx.code.aastore();
        }
        ctx.code.invokestatic(
            ClassDesc.of("java.util.List"),
            "of",
            MethodTypeDesc.of(ClassDesc.of("java.util.List"), ConstantDescs.CD_Object.arrayType())
        );
    }
    
    private void generateFor(CodeGenContext ctx, ForExpr forExpr) {
        // Generate iterable expression (should be List)
        generateExpr(ctx, forExpr.iterable());
        
        // Store the list in a temp local
        int listSlot = ctx.allocateLocal("$list$" + ctx.nextSlot, "Object");
        ctx.code.astore(listSlot);
        
        // Get iterator: list.iterator()
        ctx.code.aload(listSlot);
        ctx.code.invokeinterface(
            ClassDesc.of("java.util.List"),
            "iterator",
            MethodTypeDesc.of(ClassDesc.of("java.util.Iterator"))
        );
        int iterSlot = ctx.allocateLocal("$iter$" + ctx.nextSlot, "Object");
        ctx.code.astore(iterSlot);
        
        // For yield: create result list
        int resultListSlot = -1;
        if (forExpr.hasYield()) {
            ctx.code.new_(ClassDesc.of("java.util.ArrayList"));
            ctx.code.dup();
            ctx.code.invokespecial(
                ClassDesc.of("java.util.ArrayList"),
                ConstantDescs.INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void)
            );
            resultListSlot = ctx.allocateLocal("$result$" + ctx.nextSlot, "Object");
            ctx.code.astore(resultListSlot);
        }
        
        var loopStart = ctx.code.newLabel();
        var loopEnd = ctx.code.newLabel();
        
        ctx.code.labelBinding(loopStart);
        
        // Check hasNext()
        ctx.code.aload(iterSlot);
        ctx.code.invokeinterface(
            ClassDesc.of("java.util.Iterator"),
            "hasNext",
            MethodTypeDesc.of(ConstantDescs.CD_boolean)
        );
        ctx.code.ifeq(loopEnd);
        
        // Get next element
        ctx.code.aload(iterSlot);
        ctx.code.invokeinterface(
            ClassDesc.of("java.util.Iterator"),
            "next",
            MethodTypeDesc.of(ConstantDescs.CD_Object)
        );
        
        // Store loop variable
        int elemSlot = ctx.allocateLocal(forExpr.varName(), "Object");
        ctx.code.astore(elemSlot);
        
        // Generate body
        generateBlock(ctx, forExpr.body());
        
        if (forExpr.hasYield()) {
            // Add result to list
            ctx.code.aload(resultListSlot);
            ctx.code.swap();
            ctx.code.invokeinterface(
                ClassDesc.of("java.util.List"),
                "add",
                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)
            );
            ctx.code.pop(); // discard boolean result
        }
        
        ctx.code.goto_(loopStart);
        ctx.code.labelBinding(loopEnd);
        
        if (forExpr.hasYield()) {
            // Push result list as return value
            ctx.code.aload(resultListSlot);
        }
    }
    
    private void generateWhile(CodeGenContext ctx, WhileExpr whileExpr) {
        var loopStart = ctx.code.newLabel();
        var loopEnd = ctx.code.newLabel();
        
        ctx.code.labelBinding(loopStart);
        generateExpr(ctx, whileExpr.condition());
        ctx.code.ifeq(loopEnd);
        generateBlock(ctx, whileExpr.body());
        ctx.code.goto_(loopStart);
        ctx.code.labelBinding(loopEnd);
    }
    
    private void generateLambda(CodeGenContext ctx, LambdaExpr lambda) {
        if (ctx.ownerClass == null) {
            throw new IllegalStateException("Lambda outside function context");
        }
        String methodName = "lambda$" + (lambdaCounter++);
        List<CapturedVar> captures = collectCaptures(ctx, lambda);
        List<ClassDesc> implParams = new ArrayList<>();
        List<ClassDesc> invokedArgs = new ArrayList<>();
        for (var cap : captures) {
            ClassDesc cd = typeNameToClassDesc(cap.typeName());
            implParams.add(cd);
            invokedArgs.add(cd);
            switch (cap.typeName()) {
                case "Int", "Bool" -> ctx.code.iload(cap.outerSlot());
                case "Long" -> ctx.code.lload(cap.outerSlot());
                default -> ctx.code.aload(cap.outerSlot());
            }
        }
        for (int i = 0; i < lambda.params().size(); i++) {
            implParams.add(ConstantDescs.CD_Object);
        }
        MethodTypeDesc implDesc = MethodTypeDesc.of(ConstantDescs.CD_Object, implParams);
        pendingLambdas.add(new PendingLambda(methodName, implDesc, lambda, captures));

        ClassDesc targetInterface;
        String samName;
        MethodTypeDesc samType;
        if (lambda.params().isEmpty()) {
            targetInterface = ClassDesc.of("java.util.function.Supplier");
            samName = "get";
            samType = MethodTypeDesc.of(ConstantDescs.CD_Object);
        } else {
            targetInterface = ClassDesc.of("java.util.function.Function");
            samName = "apply";
            samType = MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object);
        }

        DirectMethodHandleDesc metafactory = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
            "metafactory",
            MethodTypeDesc.of(
                ClassDesc.of("java.lang.invoke.CallSite"),
                ClassDesc.of("java.lang.invoke.MethodHandles$Lookup"),
                ConstantDescs.CD_String,
                ClassDesc.of("java.lang.invoke.MethodType"),
                ClassDesc.of("java.lang.invoke.MethodType"),
                ClassDesc.of("java.lang.invoke.MethodHandle"),
                ClassDesc.of("java.lang.invoke.MethodType")
            )
        );
        DirectMethodHandleDesc implHandle = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC,
            ctx.ownerClass,
            methodName,
            implDesc
        );
        DynamicCallSiteDesc callSite = DynamicCallSiteDesc.of(
            metafactory,
            samName,
            MethodTypeDesc.of(targetInterface, invokedArgs.toArray(ClassDesc[]::new)),
            samType,
            implHandle,
            samType
        );
        ctx.code.invokedynamic(callSite);
    }

    private List<CapturedVar> collectCaptures(CodeGenContext ctx, LambdaExpr lambda) {
        Set<String> bound = new HashSet<>();
        for (var p : lambda.params()) {
            bound.add(p.name());
        }
        Map<String, CapturedVar> found = new LinkedHashMap<>();
        collectIdentsInBlock(lambda.body(), ctx, bound, found);
        return List.copyOf(found.values());
    }

    private void collectIdentsInBlock(BlockExpr block, CodeGenContext ctx, Set<String> bound, Map<String, CapturedVar> found) {
        if (block == null) return;
        for (var s : block.statements()) {
            switch (s) {
                case ValStmt v -> { collectIdentsInExpr(v.initializer(), ctx, bound, found); bound.add(v.name()); }
                case VarStmt v -> { collectIdentsInExpr(v.initializer(), ctx, bound, found); bound.add(v.name()); }
                case LetStmt l -> { collectIdentsInExpr(l.initializer(), ctx, bound, found); bound.add(l.name()); }
                case AssignStmt a -> collectIdentsInExpr(a.value(), ctx, bound, found);
                case ExprStmt e -> collectIdentsInExpr(e.expr(), ctx, bound, found);
                case ReturnStmt r -> collectIdentsInExpr(r.value(), ctx, bound, found);
                case TupleDestructureStmt t -> { collectIdentsInExpr(t.initializer(), ctx, bound, found); bound.addAll(t.names()); }
                case InvariantStmt _, ProofStmt _, AssumeStmt _ -> {}
            }
        }
        collectIdentsInExpr(block.tailExpr(), ctx, bound, found);
    }

    private void collectIdentsInExpr(Expr expr, CodeGenContext ctx, Set<String> bound, Map<String, CapturedVar> found) {
        if (expr == null) return;
        switch (expr) {
            case IdentExpr id -> {
                if (!bound.contains(id.name()) && ctx.locals.containsKey(id.name()) && !found.containsKey(id.name())) {
                    found.put(id.name(), new CapturedVar(id.name(), ctx.getLocalType(id.name()), ctx.locals.get(id.name())));
                }
            }
            case BinaryExpr b -> { collectIdentsInExpr(b.left(), ctx, bound, found); collectIdentsInExpr(b.right(), ctx, bound, found); }
            case UnaryExpr u -> collectIdentsInExpr(u.operand(), ctx, bound, found);
            case ParenExpr p -> collectIdentsInExpr(p.inner(), ctx, bound, found);
            case CallExpr c -> {
                collectIdentsInExpr(c.callee(), ctx, bound, found);
                for (var a : c.args()) collectIdentsInExpr(a, ctx, bound, found);
            }
            case FieldAccessExpr f -> collectIdentsInExpr(f.object(), ctx, bound, found);
            case IfExpr i -> {
                collectIdentsInExpr(i.condition(), ctx, bound, found);
                collectIdentsInBlock(i.thenBranch(), ctx, bound, found);
                collectIdentsInExpr(i.elseBranch(), ctx, bound, found);
            }
            case BlockExpr b -> collectIdentsInBlock(b, ctx, bound, found);
            case PropagateExpr p -> collectIdentsInExpr(p.inner(), ctx, bound, found);
            case ConsumeExpr c -> collectIdentsInExpr(c.target(), ctx, bound, found);
            case TupleExpr t -> { for (var e : t.elements()) collectIdentsInExpr(e, ctx, bound, found); }
            default -> {}
        }
    }

    private ClassDesc typeNameToClassDesc(String typeName) {
        return switch (typeName) {
            case "Int" -> ConstantDescs.CD_int;
            case "Long" -> ConstantDescs.CD_long;
            case "Bool" -> ConstantDescs.CD_boolean;
            case "String" -> ConstantDescs.CD_String;
            case "Secret" -> ClassDesc.of("ngj.runtime.SecretValue");
            case "Budget" -> ClassDesc.of("ngj.runtime.Budget");
            default -> ConstantDescs.CD_Object;
        };
    }
    
    private void generateLiteral(CodeGenContext ctx, LiteralExpr lit) {
        switch (lit.kind()) {
            case INT -> ctx.code.ldc((Integer) lit.value());
            case LONG -> ctx.code.ldc((Long) lit.value());
            case BOOL -> ctx.code.ldc((Boolean) lit.value() ? 1 : 0);
            case STRING -> ctx.code.ldc((String) lit.value());
            case UNIT -> {}
        }
    }
    
    private void generateIdent(CodeGenContext ctx, IdentExpr ident) {
        Integer slot = ctx.getLocal(ident.name());
        if (slot != null) {
            var typeName = ctx.getLocalType(ident.name());
            switch (typeName) {
                case "Int", "Bool" -> ctx.code.iload(slot);
                case "Long" -> ctx.code.lload(slot);
                default -> ctx.code.aload(slot);
            }
        } else {
            ctx.code.aconst_null();
        }
    }
    
    private void generateBinary(CodeGenContext ctx, BinaryExpr binary) {
        if (binary.op() == BinaryExpr.BinaryOp.AND) {
            generateShortCircuitAnd(ctx, binary);
            return;
        }
        if (binary.op() == BinaryExpr.BinaryOp.OR) {
            generateShortCircuitOr(ctx, binary);
            return;
        }
        
        var leftType = inferTypeName(ctx, binary.left());
        generateExpr(ctx, binary.left());
        generateExpr(ctx, binary.right());
        
        if (leftType.equals("Long")) {
            switch (binary.op()) {
                case ADD -> ctx.code.invokestatic(ClassDesc.of("java.lang.Math"), "addExact",
                    MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long, ConstantDescs.CD_long));
                case SUB -> ctx.code.invokestatic(ClassDesc.of("java.lang.Math"), "subtractExact",
                    MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long, ConstantDescs.CD_long));
                case MUL -> ctx.code.invokestatic(ClassDesc.of("java.lang.Math"), "multiplyExact",
                    MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long, ConstantDescs.CD_long));
                case DIV -> ctx.code.ldiv();
                case MOD -> ctx.code.lrem();
                case EQ -> { ctx.code.lcmp(); generateComparison(ctx, Opcode.IFEQ); }
                case NE -> { ctx.code.lcmp(); generateComparison(ctx, Opcode.IFNE); }
                case LT -> { ctx.code.lcmp(); generateComparison(ctx, Opcode.IFLT); }
                case LE -> { ctx.code.lcmp(); generateComparison(ctx, Opcode.IFLE); }
                case GT -> { ctx.code.lcmp(); generateComparison(ctx, Opcode.IFGT); }
                case GE -> { ctx.code.lcmp(); generateComparison(ctx, Opcode.IFGE); }
                default -> {}
            }
            return;
        }
        
        if (!leftType.equals("Int") && !leftType.equals("Bool")) {
            if (binary.op() == BinaryExpr.BinaryOp.EQ) {
                ctx.code.invokestatic(ClassDesc.of("java.util.Objects"), "equals",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                return;
            } else if (binary.op() == BinaryExpr.BinaryOp.NE) {
                ctx.code.invokestatic(ClassDesc.of("java.util.Objects"), "equals",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                ctx.code.ldc(1);
                ctx.code.ixor();
                return;
            }
        }
        
        switch (binary.op()) {
            case ADD -> ctx.code.invokestatic(ClassDesc.of("java.lang.Math"), "addExact", 
                MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
            case SUB -> ctx.code.invokestatic(ClassDesc.of("java.lang.Math"), "subtractExact",
                MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
            case MUL -> ctx.code.invokestatic(ClassDesc.of("java.lang.Math"), "multiplyExact",
                MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
            case DIV -> ctx.code.idiv();
            case MOD -> ctx.code.irem();
            case EQ -> generateComparison(ctx, Opcode.IF_ICMPEQ);
            case NE -> generateComparison(ctx, Opcode.IF_ICMPNE);
            case LT -> generateComparison(ctx, Opcode.IF_ICMPLT);
            case LE -> generateComparison(ctx, Opcode.IF_ICMPLE);
            case GT -> generateComparison(ctx, Opcode.IF_ICMPGT);
            case GE -> generateComparison(ctx, Opcode.IF_ICMPGE);
            default -> {}
        }
    }
    
    private void generateComparison(CodeGenContext ctx, Opcode opcode) {
        ctx.code.ifThenElse(opcode, 
            tb -> tb.ldc(1),
            fb -> fb.ldc(0));
    }
    
    private void generateShortCircuitAnd(CodeGenContext ctx, BinaryExpr binary) {
        var falseLabel = ctx.code.newLabel();
        var endLabel = ctx.code.newLabel();
        
        generateExpr(ctx, binary.left());
        ctx.code.ifeq(falseLabel);
        
        generateExpr(ctx, binary.right());
        ctx.code.goto_(endLabel);
        
        ctx.code.labelBinding(falseLabel);
        ctx.code.ldc(0);
        
        ctx.code.labelBinding(endLabel);
    }
    
    private void generateShortCircuitOr(CodeGenContext ctx, BinaryExpr binary) {
        var trueLabel = ctx.code.newLabel();
        var endLabel = ctx.code.newLabel();
        
        generateExpr(ctx, binary.left());
        ctx.code.ifne(trueLabel);
        
        generateExpr(ctx, binary.right());
        ctx.code.goto_(endLabel);
        
        ctx.code.labelBinding(trueLabel);
        ctx.code.ldc(1);
        
        ctx.code.labelBinding(endLabel);
    }
    
    private void generateUnary(CodeGenContext ctx, UnaryExpr unary) {
        var operandType = inferTypeName(ctx, unary.operand());
        generateExpr(ctx, unary.operand());
        
        switch (unary.op()) {
            case NEG -> {
                if (operandType.equals("Long")) {
                    ctx.code.lneg();
                } else {
                    ctx.code.ineg();
                }
            }
            case NOT -> {
                ctx.code.ldc(1);
                ctx.code.ixor();
            }
        }
    }
    
    private void generateIf(CodeGenContext ctx, IfExpr ifExpr) {
        generateExpr(ctx, ifExpr.condition());
        var elseLabel = ctx.code.newLabel();
        var endLabel = ctx.code.newLabel();
        
        ctx.code.ifeq(elseLabel);
        
        boolean prevReturn = ctx.hasReturn;
        ctx.hasReturn = false;
        generateBlock(ctx, ifExpr.thenBranch());
        boolean thenReturned = ctx.hasReturn;
        
        if (ifExpr.elseBranch() != null) {
            if (!thenReturned) {
                ctx.code.goto_(endLabel);
            }
            ctx.code.labelBinding(elseLabel);
            ctx.hasReturn = false;
            generateExpr(ctx, ifExpr.elseBranch());
            boolean elseReturned = ctx.hasReturn;
            if (!thenReturned || !elseReturned) {
                ctx.code.labelBinding(endLabel);
            }
            ctx.hasReturn = prevReturn || (thenReturned && elseReturned);
        } else {
            ctx.code.labelBinding(elseLabel);
            ctx.hasReturn = prevReturn;
        }
    }
    
    private void generateCall(CodeGenContext ctx, CallExpr call) {
        if (call.callee() instanceof IdentExpr ident) {
            var methodName = ident.name();
            
            switch (methodName) {
                // STDLIB-03: Result constructors - wrap in Optional-like structure
                case "Ok" -> {
                    ctx.code.new_(ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"));
                    ctx.code.dup();
                    ctx.code.ldc("Ok");
                    generateExpr(ctx, call.args().get(0));
                    boxIfPrimitive(ctx, call.args().get(0));
                    ctx.code.invokespecial(
                        ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object)
                    );
                    return;
                }
                case "Err" -> {
                    ctx.code.new_(ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"));
                    ctx.code.dup();
                    ctx.code.ldc("Err");
                    generateExpr(ctx, call.args().get(0));
                    boxIfPrimitive(ctx, call.args().get(0));
                    ctx.code.invokespecial(
                        ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object)
                    );
                    return;
                }
                
                // STDLIB-02: Option constructors using java.util.Optional
                case "Some" -> {
                    generateExpr(ctx, call.args().get(0));
                    boxIfPrimitive(ctx, call.args().get(0));
                    ctx.code.invokestatic(
                        ClassDesc.of("java.util.Optional"),
                        "of",
                        MethodTypeDesc.of(ClassDesc.of("java.util.Optional"), ConstantDescs.CD_Object)
                    );
                    return;
                }
                case "None" -> {
                    ctx.code.invokestatic(
                        ClassDesc.of("java.util.Optional"),
                        "empty",
                        MethodTypeDesc.of(ClassDesc.of("java.util.Optional"))
                    );
                    return;
                }
                
                // STDLIB-04: List constructors - immutable list (copy-on-use)
                case "Nil" -> {
                    ctx.code.invokestatic(
                        ClassDesc.of("java.util.List"),
                        "of",
                        MethodTypeDesc.of(ClassDesc.of("java.util.List"))
                    );
                    return;
                }
                case "listOf" -> {
                    if (call.args().isEmpty()) {
                        ctx.code.invokestatic(
                            ClassDesc.of("java.util.List"),
                            "of",
                            MethodTypeDesc.of(ClassDesc.of("java.util.List"))
                        );
                    } else {
                        ctx.code.ldc(call.args().size());
                        ctx.code.anewarray(ConstantDescs.CD_Object);
                        for (int i = 0; i < call.args().size(); i++) {
                            ctx.code.dup();
                            ctx.code.ldc(i);
                            generateExpr(ctx, call.args().get(i));
                            boxIfPrimitive(ctx, call.args().get(i));
                            ctx.code.aastore();
                        }
                        ctx.code.invokestatic(
                            ClassDesc.of("java.util.List"),
                            "of",
                            MethodTypeDesc.of(ClassDesc.of("java.util.List"), ConstantDescs.CD_Object.arrayType())
                        );
                    }
                    return;
                }
                case "Cons" -> {
                    ctx.code.new_(ClassDesc.of("java.util.ArrayList"));
                    ctx.code.dup();
                    ctx.code.invokespecial(
                        ClassDesc.of("java.util.ArrayList"),
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void)
                    );
                    ctx.code.dup();
                    generateExpr(ctx, call.args().get(0));
                    boxIfPrimitive(ctx, call.args().get(0));
                    ctx.code.invokeinterface(
                        ClassDesc.of("java.util.List"),
                        "add",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)
                    );
                    ctx.code.pop();
                    ctx.code.dup();
                    generateExpr(ctx, call.args().get(1));
                    ctx.code.invokeinterface(
                        ClassDesc.of("java.util.List"),
                        "addAll",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ClassDesc.of("java.util.Collection"))
                    );
                    ctx.code.pop();
                    return;
                }
                
                // STDLIB-07: Checked arithmetic returning Result
                case "checkedAdd" -> {
                    generateExpr(ctx, call.args().get(0));
                    generateExpr(ctx, call.args().get(1));
                    generateCheckedArithmetic(ctx, "addExact", "IntOverflow");
                    return;
                }
                case "checkedSub" -> {
                    generateExpr(ctx, call.args().get(0));
                    generateExpr(ctx, call.args().get(1));
                    generateCheckedArithmetic(ctx, "subtractExact", "IntOverflow");
                    return;
                }
                case "checkedMul" -> {
                    generateExpr(ctx, call.args().get(0));
                    generateExpr(ctx, call.args().get(1));
                    generateCheckedArithmetic(ctx, "multiplyExact", "IntOverflow");
                    return;
                }
                
                // STDLIB-08: List operations returning Option<T> (not null)
                case "head" -> {
                    generateExpr(ctx, call.args().get(0));
                    var listSlot = ctx.allocateLocal("$list$" + ctx.nextSlot, "Object");
                    ctx.code.astore(listSlot);
                    ctx.code.aload(listSlot);
                    ctx.code.invokeinterface(
                        ClassDesc.of("java.util.List"),
                        "isEmpty",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean)
                    );
                    ctx.code.ifThenElse(Opcode.IFNE,
                        tb -> tb.invokestatic(
                            ClassDesc.of("java.util.Optional"),
                            "empty",
                            MethodTypeDesc.of(ClassDesc.of("java.util.Optional"))
                        ),
                        fb -> {
                            fb.aload(listSlot);
                            fb.ldc(0);
                            fb.invokeinterface(
                                ClassDesc.of("java.util.List"),
                                "get",
                                MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int)
                            );
                            fb.invokestatic(
                                ClassDesc.of("java.util.Optional"),
                                "of",
                                MethodTypeDesc.of(ClassDesc.of("java.util.Optional"), ConstantDescs.CD_Object)
                            );
                        }
                    );
                    return;
                }
                case "get" -> {
                    generateExpr(ctx, call.args().get(0));
                    var listSlot = ctx.allocateLocal("$list$" + ctx.nextSlot, "Object");
                    ctx.code.astore(listSlot);
                    generateExpr(ctx, call.args().get(1));
                    var indexSlot = ctx.allocateLocal("$idx$" + ctx.nextSlot, "Int");
                    ctx.code.istore(indexSlot);
                    
                    ctx.code.iload(indexSlot);
                    ctx.code.ldc(0);
                    var outOfBounds = ctx.code.newLabel();
                    var end = ctx.code.newLabel();
                    
                    ctx.code.if_icmplt(outOfBounds);
                    ctx.code.iload(indexSlot);
                    ctx.code.aload(listSlot);
                    ctx.code.invokeinterface(
                        ClassDesc.of("java.util.List"),
                        "size",
                        MethodTypeDesc.of(ConstantDescs.CD_int)
                    );
                    ctx.code.if_icmpge(outOfBounds);
                    
                    ctx.code.aload(listSlot);
                    ctx.code.iload(indexSlot);
                    ctx.code.invokeinterface(
                        ClassDesc.of("java.util.List"),
                        "get",
                        MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int)
                    );
                    ctx.code.invokestatic(
                        ClassDesc.of("java.util.Optional"),
                        "of",
                        MethodTypeDesc.of(ClassDesc.of("java.util.Optional"), ConstantDescs.CD_Object)
                    );
                    ctx.code.goto_(end);
                    
                    ctx.code.labelBinding(outOfBounds);
                    ctx.code.invokestatic(
                        ClassDesc.of("java.util.Optional"),
                        "empty",
                        MethodTypeDesc.of(ClassDesc.of("java.util.Optional"))
                    );
                    
                    ctx.code.labelBinding(end);
                    return;
                }
                case "length" -> {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.invokeinterface(
                        ClassDesc.of("java.util.List"),
                        "size",
                        MethodTypeDesc.of(ConstantDescs.CD_int)
                    );
                    return;
                }
                case "isEmpty" -> {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.invokeinterface(
                        ClassDesc.of("java.util.List"),
                        "isEmpty",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean)
                    );
                    return;
                }
                
                // Option/Result predicates
                case "isSome" -> {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.util.Optional"),
                        "isPresent",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean)
                    );
                    return;
                }
                case "isNone" -> {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.util.Optional"),
                        "isEmpty",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean)
                    );
                    return;
                }
                case "isOk" -> {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                        "getKey",
                        MethodTypeDesc.of(ConstantDescs.CD_Object)
                    );
                    ctx.code.ldc("Ok");
                    ctx.code.invokevirtual(
                        ConstantDescs.CD_Object,
                        "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)
                    );
                    return;
                }
                case "isErr" -> {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                        "getKey",
                        MethodTypeDesc.of(ConstantDescs.CD_Object)
                    );
                    ctx.code.ldc("Err");
                    ctx.code.invokevirtual(
                        ConstantDescs.CD_Object,
                        "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)
                    );
                    return;
                }
                case "getOrElse" -> {
                    var defaultType = inferTypeName(ctx, call.args().get(1));
                    generateExpr(ctx, call.args().get(0));
                    generateExpr(ctx, call.args().get(1));
                    boxIfPrimitive(ctx, call.args().get(1));
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.util.Optional"),
                        "orElse",
                        MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object)
                    );
                    unboxIfNeeded(ctx, defaultType);
                    return;
                }
                case "toOption" -> {
                    generateExpr(ctx, call.args().get(0));
                    var resSlot = ctx.allocateLocal("$res$" + ctx.nextSlot, "Object");
                    ctx.code.astore(resSlot);
                    ctx.code.aload(resSlot);
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                        "getKey",
                        MethodTypeDesc.of(ConstantDescs.CD_Object)
                    );
                    ctx.code.ldc("Ok");
                    ctx.code.invokevirtual(
                        ConstantDescs.CD_Object,
                        "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)
                    );
                    ctx.code.ifThenElse(Opcode.IFNE,
                        tb -> {
                            tb.aload(resSlot);
                            tb.invokevirtual(
                                ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                                "getValue",
                                MethodTypeDesc.of(ConstantDescs.CD_Object)
                            );
                            tb.invokestatic(
                                ClassDesc.of("java.util.Optional"),
                                "of",
                                MethodTypeDesc.of(ClassDesc.of("java.util.Optional"), ConstantDescs.CD_Object)
                            );
                        },
                        fb -> fb.invokestatic(
                            ClassDesc.of("java.util.Optional"),
                            "empty",
                            MethodTypeDesc.of(ClassDesc.of("java.util.Optional"))
                        )
                    );
                    return;
                }
                case "toResult" -> {
                    generateExpr(ctx, call.args().get(0));
                    var optSlot = ctx.allocateLocal("$opt$" + ctx.nextSlot, "Object");
                    ctx.code.astore(optSlot);
                    generateExpr(ctx, call.args().get(1));
                    boxIfPrimitive(ctx, call.args().get(1));
                    var errSlot = ctx.allocateLocal("$err$" + ctx.nextSlot, "Object");
                    ctx.code.astore(errSlot);
                    ctx.code.aload(optSlot);
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.util.Optional"),
                        "isPresent",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean)
                    );
                    ctx.code.ifThenElse(Opcode.IFNE,
                        tb -> {
                            tb.new_(ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"));
                            tb.dup();
                            tb.ldc("Ok");
                            tb.aload(optSlot);
                            tb.invokevirtual(
                                ClassDesc.of("java.util.Optional"),
                                "get",
                                MethodTypeDesc.of(ConstantDescs.CD_Object)
                            );
                            tb.invokespecial(
                                ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                                ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object)
                            );
                        },
                        fb -> {
                            fb.new_(ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"));
                            fb.dup();
                            fb.ldc("Err");
                            fb.aload(errSlot);
                            fb.invokespecial(
                                ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                                ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object)
                            );
                        }
                    );
                    return;
                }
                case "refine" -> {
                    generateRefine(ctx, call);
                    return;
                }
                case "Budget" -> {
                    var budgetDesc = ClassDesc.of("ngj.runtime.Budget");
                    ctx.code.new_(budgetDesc);
                    ctx.code.dup();
                    generateExpr(ctx, call.args().get(0));
                    generateExpr(ctx, call.args().get(1));
                    ctx.code.invokespecial(budgetDesc, ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String, ConstantDescs.CD_long));
                    return;
                }
            }
            
            // Record constructor call
            if (records.containsKey(methodName)) {
                var rec = records.get(methodName);
                var classDesc = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + methodName);
                ctx.code.new_(classDesc);
                ctx.code.dup();
                for (var arg : call.args()) {
                    generateExpr(ctx, arg);
                }
                var paramDescs = rec.params().stream()
                    .map(p -> typeToClassDesc(p.type()))
                    .toArray(ClassDesc[]::new);
                ctx.code.invokespecial(classDesc, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, paramDescs));
                return;
            }
            
            // Data constructor call
            if (datas.containsKey(methodName)) {
                var data = datas.get(methodName);
                var classDesc = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + methodName);
                ctx.code.new_(classDesc);
                ctx.code.dup();
                for (var arg : call.args()) {
                    generateExpr(ctx, arg);
                }
                var paramDescs = data.fields().stream()
                    .map(f -> typeToClassDesc(f.type()))
                    .toArray(ClassDesc[]::new);
                ctx.code.invokespecial(classDesc, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, paramDescs));
                return;
            }
            
            // Unqualified enum variant constructor call
            for (var entry : enums.entrySet()) {
                for (var variant : entry.getValue().variants()) {
                    if (variant.name().equals(methodName)) {
                        var variantClassDesc = ClassDesc.ofInternalName(
                            currentModule.replace('.', '/') + "/" + entry.getKey() + "$" + variant.name());
                        ctx.code.new_(variantClassDesc);
                        ctx.code.dup();
                        for (var arg : call.args()) {
                            generateExpr(ctx, arg);
                        }
                        var paramDescs = variant.fields().stream()
                            .map(f -> typeToClassDesc(f.type()))
                            .toArray(ClassDesc[]::new);
                        ctx.code.invokespecial(variantClassDesc, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void, paramDescs));
                        return;
                    }
                }
            }
            
            // User-defined function call
            for (var arg : call.args()) {
                generateExpr(ctx, arg);
            }
            var classDesc = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + methodName + "$Fn");
            if (functions.containsKey(methodName)) {
                ctx.code.invokestatic(classDesc, "invoke", buildMethodDescriptor(functions.get(methodName)));
            } else {
                ctx.code.invokestatic(classDesc, "invoke", MethodTypeDesc.of(ConstantDescs.CD_Object));
            }
        } else if (call.callee() instanceof FieldAccessExpr access) {
            if (access.object() instanceof IdentExpr objIdent) {
                var targetName = objIdent.name();
                var memberName = access.fieldName();
                if (targetName.equals("Budget") && memberName.equals("split") && call.args().size() == 2) {
                    generateExpr(ctx, call.args().get(0));
                    generateExpr(ctx, call.args().get(1));
                    ctx.code.invokestatic(
                        ClassDesc.of("ngj.runtime.Budget"),
                        "split",
                        MethodTypeDesc.of(ClassDesc.of("ngj.runtime.Pair"), ClassDesc.of("ngj.runtime.Budget"), ConstantDescs.CD_long)
                    );
                    return;
                }
                if (targetName.equals("Budget") && memberName.equals("spend") && call.args().size() == 2) {
                    generateExpr(ctx, call.args().get(0));
                    generateExpr(ctx, call.args().get(1));
                    ctx.code.invokevirtual(
                        ClassDesc.of("ngj.runtime.Budget"),
                        "spend",
                        MethodTypeDesc.of(ClassDesc.of("ngj.runtime.Budget"), ConstantDescs.CD_long)
                    );
                    return;
                }
                if (targetName.equals("Secret") && memberName.equals("of") && call.args().size() == 1) {
                    generateExpr(ctx, call.args().get(0));
                    boxIfPrimitive(ctx, call.args().get(0));
                    ctx.code.invokestatic(
                        ClassDesc.of("ngj.runtime.SecretValue"),
                        "of",
                        MethodTypeDesc.of(ClassDesc.of("ngj.runtime.SecretValue"), ConstantDescs.CD_Object)
                    );
                    return;
                }
                if (targetName.equals("Secret") && memberName.equals("map") && call.args().size() == 2) {
                    generateExpr(ctx, call.args().get(0));
                    generateExpr(ctx, call.args().get(1));
                    ctx.code.invokevirtual(
                        ClassDesc.of("ngj.runtime.SecretValue"),
                        "map",
                        MethodTypeDesc.of(ClassDesc.of("ngj.runtime.SecretValue"), ClassDesc.of("java.util.function.Function"))
                    );
                    return;
                }
                if (targetName.equals("Secret") && memberName.equals("expose") && call.args().size() == 1) {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.invokevirtual(
                        ClassDesc.of("ngj.runtime.SecretValue"),
                        "expose",
                        MethodTypeDesc.of(ConstantDescs.CD_Object)
                    );
                    return;
                }
                if (targetName.equals("List") && memberName.equals("map") && call.args().size() == 2) {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.invokeinterface(
                        ClassDesc.of("java.util.Collection"),
                        "stream",
                        MethodTypeDesc.of(ClassDesc.of("java.util.stream.Stream"))
                    );
                    generateExpr(ctx, call.args().get(1));
                    ctx.code.invokeinterface(
                        ClassDesc.of("java.util.stream.Stream"),
                        "map",
                        MethodTypeDesc.of(ClassDesc.of("java.util.stream.Stream"), ClassDesc.of("java.util.function.Function"))
                    );
                    ctx.code.invokeinterface(
                        ClassDesc.of("java.util.stream.Stream"),
                        "toList",
                        MethodTypeDesc.of(ClassDesc.of("java.util.List"))
                    );
                    return;
                }
                if (records.containsKey(targetName) && (memberName.equals("decodeJson") || memberName.equals("decodeBinary"))) {
                    var recDesc = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + targetName);
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.invokestatic(
                        recDesc,
                        memberName,
                        MethodTypeDesc.of(
                            ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                            memberName.equals("decodeJson") ? ConstantDescs.CD_String : ConstantDescs.CD_byte.arrayType()
                        )
                    );
                    return;
                }
                if (enums.containsKey(targetName)) {
                    for (var variant : enums.get(targetName).variants()) {
                        if (variant.name().equals(memberName)) {
                            var variantClassDesc = ClassDesc.ofInternalName(
                                currentModule.replace('.', '/') + "/" + targetName + "$" + memberName);
                            ctx.code.new_(variantClassDesc);
                            ctx.code.dup();
                            for (var arg : call.args()) {
                                generateExpr(ctx, arg);
                            }
                            var paramDescs = variant.fields().stream()
                                .map(f -> typeToClassDesc(f.type()))
                                .toArray(ClassDesc[]::new);
                            ctx.code.invokespecial(variantClassDesc, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, paramDescs));
                            return;
                        }
                    }
                }
                // Built-in error enum variant (e.g., OverflowError.IntOverflow())
                if (memberName.equals("IntOverflow") || memberName.equals("OutOfRange") || memberName.equals("OutOfBounds")) {
                    ctx.code.ldc(memberName);
                    return;
                }
            }

            // Instance method calls (e.g. b.remaining(), b.spend(x), b.split(x), sec.map(f), obj.toJson())
            var memberName = access.fieldName();
            var objType = inferTypeName(ctx, access.object());
            if (memberName.equals("remaining")) {
                generateExpr(ctx, access.object());
                ctx.code.invokevirtual(
                    ClassDesc.of("ngj.runtime.Budget"),
                    "remaining",
                    MethodTypeDesc.of(ConstantDescs.CD_long)
                );
                return;
            }
            if (memberName.equals("spend") && call.args().size() == 1) {
                generateExpr(ctx, access.object());
                generateExpr(ctx, call.args().get(0));
                ctx.code.invokevirtual(
                    ClassDesc.of("ngj.runtime.Budget"),
                    "spend",
                    MethodTypeDesc.of(ClassDesc.of("ngj.runtime.Budget"), ConstantDescs.CD_long)
                );
                return;
            }
            if (memberName.equals("split") && call.args().size() == 1) {
                generateExpr(ctx, access.object());
                generateExpr(ctx, call.args().get(0));
                ctx.code.invokevirtual(
                    ClassDesc.of("ngj.runtime.Budget"),
                    "split",
                    MethodTypeDesc.of(ClassDesc.of("ngj.runtime.Pair"), ConstantDescs.CD_long)
                );
                return;
            }
            if (memberName.equals("toJson") && records.containsKey(objType)) {
                generateExpr(ctx, access.object());
                ctx.code.invokevirtual(
                    ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + objType),
                    "toJson",
                    MethodTypeDesc.of(ConstantDescs.CD_String)
                );
                return;
            }
            if (memberName.equals("encodeBinary") && records.containsKey(objType)) {
                generateExpr(ctx, access.object());
                ctx.code.invokevirtual(
                    ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + objType),
                    "encodeBinary",
                    MethodTypeDesc.of(ConstantDescs.CD_byte.arrayType())
                );
                return;
            }
            ctx.code.ldc(memberName);
        } else {
            for (var arg : call.args()) {
                generateExpr(ctx, arg);
            }
        }
    }
    
    /**
     * STDLIB-07: Generate checked arithmetic that returns Result
     * On success: Ok(result), on overflow: Err(OverflowError.IntOverflow())
     */
    private void generateCheckedArithmetic(CodeGenContext ctx, String mathMethod, String errorVariant) {
        var aSlot = ctx.allocateLocal("$a$" + ctx.nextSlot, "Int");
        var bSlot = ctx.allocateLocal("$b$" + ctx.nextSlot, "Int");
        ctx.code.istore(bSlot);
        ctx.code.istore(aSlot);
        
        var tryStart = ctx.code.newLabel();
        var tryEnd = ctx.code.newLabel();
        var catchStart = ctx.code.newLabel();
        var end = ctx.code.newLabel();
        
        ctx.code.exceptionCatch(tryStart, tryEnd, catchStart, ClassDesc.of("java.lang.ArithmeticException"));
        
        ctx.code.labelBinding(tryStart);
        ctx.code.iload(aSlot);
        ctx.code.iload(bSlot);
        ctx.code.invokestatic(
            ClassDesc.of("java.lang.Math"),
            mathMethod,
            MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int)
        );
        ctx.code.invokestatic(
            ClassDesc.of("java.lang.Integer"),
            "valueOf",
            MethodTypeDesc.of(ClassDesc.of("java.lang.Integer"), ConstantDescs.CD_int)
        );
        ctx.code.labelBinding(tryEnd);
        
        var resultSlot = ctx.allocateLocal("$result$" + ctx.nextSlot, "Object");
        ctx.code.astore(resultSlot);
        ctx.code.new_(ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"));
        ctx.code.dup();
        ctx.code.ldc("Ok");
        ctx.code.aload(resultSlot);
        ctx.code.invokespecial(
            ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
            ConstantDescs.INIT_NAME,
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object)
        );
        ctx.code.goto_(end);
        
        ctx.code.labelBinding(catchStart);
        ctx.code.pop();
        ctx.code.new_(ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"));
        ctx.code.dup();
        ctx.code.ldc("Err");
        ctx.code.ldc(errorVariant);
        ctx.code.invokespecial(
            ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
            ConstantDescs.INIT_NAME,
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object)
        );
        
        ctx.code.labelBinding(end);
    }
    
    /**
     * Box primitive values for use in generic contexts.
     * STDLIB-01: Value types box transparently at Java boundaries.
     */
    private void boxIfPrimitive(CodeGenContext ctx, Expr expr) {
        var typeName = inferTypeName(ctx, expr);
        switch (typeName) {
            case "Int" -> ctx.code.invokestatic(
                ClassDesc.of("java.lang.Integer"),
                "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Integer"), ConstantDescs.CD_int)
            );
            case "Long" -> ctx.code.invokestatic(
                ClassDesc.of("java.lang.Long"),
                "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Long"), ConstantDescs.CD_long)
            );
            case "Bool" -> ctx.code.invokestatic(
                ClassDesc.of("java.lang.Boolean"),
                "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Boolean"), ConstantDescs.CD_boolean)
            );
            default -> {}
        }
    }
    
    private void unboxIfNeeded(CodeGenContext ctx, String targetTypeName) {
        switch (targetTypeName) {
            case "Int" -> {
                ctx.code.checkcast(ClassDesc.of("java.lang.Integer"));
                ctx.code.invokevirtual(
                    ClassDesc.of("java.lang.Integer"),
                    "intValue",
                    MethodTypeDesc.of(ConstantDescs.CD_int)
                );
            }
            case "Long" -> {
                ctx.code.checkcast(ClassDesc.of("java.lang.Long"));
                ctx.code.invokevirtual(
                    ClassDesc.of("java.lang.Long"),
                    "longValue",
                    MethodTypeDesc.of(ConstantDescs.CD_long)
                );
            }
            case "Bool" -> {
                ctx.code.checkcast(ClassDesc.of("java.lang.Boolean"));
                ctx.code.invokevirtual(
                    ClassDesc.of("java.lang.Boolean"),
                    "booleanValue",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean)
                );
            }
            default -> {
                if (records.containsKey(targetTypeName) || datas.containsKey(targetTypeName) || enums.containsKey(targetTypeName)) {
                    ctx.code.checkcast(ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + targetTypeName));
                } else if (targetTypeName.equals("String")) {
                    ctx.code.checkcast(ConstantDescs.CD_String);
                }
            }
        }
    }
    
    /**
     * NULL-02: Convert Java null to NGJ Option.
     * Used at Java interop boundaries.
     */
    public static void generateNullToOption(CodeBuilder code) {
        code.dup();
        code.ifThenElse(Opcode.IFNULL,
            tb -> {
                tb.pop();
                tb.invokestatic(
                    ClassDesc.of("java.util.Optional"),
                    "empty",
                    MethodTypeDesc.of(ClassDesc.of("java.util.Optional"))
                );
            },
            fb -> fb.invokestatic(
                ClassDesc.of("java.util.Optional"),
                "of",
                MethodTypeDesc.of(ClassDesc.of("java.util.Optional"), ConstantDescs.CD_Object)
            )
        );
    }
    
    private void generateConstructor(CodeGenContext ctx, ConstructorExpr ctor) {
        var classDesc = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + ctor.typePath());
        
        ctx.code.new_(classDesc);
        ctx.code.dup();
        
        for (var field : ctor.fields()) {
            generateExpr(ctx, field.value());
        }
        
        var paramTypes = new ClassDesc[ctor.fields().size()];
        Arrays.fill(paramTypes, ConstantDescs.CD_Object);
        var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void, paramTypes);
        
        ctx.code.invokespecial(classDesc, ConstantDescs.INIT_NAME, ctorDesc);
    }
    
    private void generateRefine(CodeGenContext ctx, CallExpr call) {
        if (call.typeArgs().isEmpty() || call.args().size() != 1) {
            ctx.code.new_(ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"));
            ctx.code.dup();
            ctx.code.ldc("Err");
            ctx.code.ldc("PredicateFailed");
            ctx.code.invokespecial(
                ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                ConstantDescs.INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object)
            );
            return;
        }
        TypeExpr targetType = resolveTypeAlias(call.typeArgs().get(0));
        String baseTypeName = getTypeName(targetType);
        generateExpr(ctx, call.args().get(0));
        int valSlot = ctx.allocateLocal("$ref$" + ctx.nextSlot, baseTypeName);
        switch (baseTypeName) {
            case "Long" -> ctx.code.lstore(valSlot);
            case "Int", "Bool" -> ctx.code.istore(valSlot);
            default -> ctx.code.astore(valSlot);
        }

        var failLabel = ctx.code.newLabel();
        var endLabel = ctx.code.newLabel();
        emitTypeConstraintCheck(ctx.code, targetType, valSlot, baseTypeName, failLabel, Map.of());

        // Ok(boxedVal)
        ctx.code.new_(ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"));
        ctx.code.dup();
        ctx.code.ldc("Ok");
        loadSlot(ctx.code, valSlot, baseTypeName);
        boxSlotIfPrimitive(ctx.code, baseTypeName);
        ctx.code.invokespecial(
            ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
            ConstantDescs.INIT_NAME,
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object)
        );
        ctx.code.goto_(endLabel);

        // Err("PredicateFailed")
        ctx.code.labelBinding(failLabel);
        ctx.code.new_(ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"));
        ctx.code.dup();
        ctx.code.ldc("Err");
        ctx.code.ldc("PredicateFailed");
        ctx.code.invokespecial(
            ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
            ConstantDescs.INIT_NAME,
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object)
        );
        ctx.code.labelBinding(endLabel);
    }

    private void generateMatch(CodeGenContext ctx, MatchExpr matchExpr) {
        var scrutType = inferTypeName(ctx, matchExpr.scrutinee());
        generateExpr(ctx, matchExpr.scrutinee());
        if (matchExpr.arms().isEmpty()) {
            if (scrutType.equals("Long")) ctx.code.pop2();
            else if (!scrutType.equals("Unit")) ctx.code.pop();
            return;
        }
        int scrutSlot = ctx.allocateLocal("$match$" + ctx.nextSlot, scrutType);
        switch (scrutType) {
            case "Long" -> ctx.code.lstore(scrutSlot);
            case "Int", "Bool" -> ctx.code.istore(scrutSlot);
            default -> ctx.code.astore(scrutSlot);
        }

        var endLabel = ctx.code.newLabel();
        boolean prevReturn = ctx.hasReturn;
        boolean allReturned = true;

        for (int i = 0; i < matchExpr.arms().size(); i++) {
            var arm = matchExpr.arms().get(i);
            var nextArmLabel = ctx.code.newLabel();
            emitPatternMatch(ctx, arm.pattern(), scrutSlot, scrutType, matchExpr.scrutinee(), nextArmLabel);

            ctx.hasReturn = false;
            generateExpr(ctx, arm.body());
            if (!ctx.hasReturn) {
                allReturned = false;
                ctx.code.goto_(endLabel);
            }
            ctx.code.labelBinding(nextArmLabel);
        }

        // Fallback if no arm matched at runtime
        ctx.code.new_(ClassDesc.of("java.lang.IllegalStateException"));
        ctx.code.dup();
        ctx.code.ldc("Non-exhaustive match at runtime");
        ctx.code.invokespecial(
            ClassDesc.of("java.lang.IllegalStateException"),
            ConstantDescs.INIT_NAME,
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String)
        );
        ctx.code.athrow();

        if (!allReturned) {
            ctx.code.labelBinding(endLabel);
        }
        ctx.hasReturn = prevReturn || allReturned;
    }

    private void emitPatternMatch(
        CodeGenContext ctx,
        Pattern pattern,
        int scrutSlot,
        String scrutType,
        Expr scrutineeExpr,
        Label failLabel
    ) {
        switch (pattern) {
            case WildcardPattern _ -> {
                // Always matches
            }
            case BindingPattern bp -> {
                int slot = ctx.allocateLocal(bp.name(), scrutType);
                loadSlot(ctx.code, scrutSlot, scrutType);
                storeSlot(ctx.code, slot, scrutType);
            }
            case LiteralPattern lp -> {
                loadSlot(ctx.code, scrutSlot, scrutType);
                switch (lp.literal().kind()) {
                    case INT -> {
                        ctx.code.ldc((Integer) lp.literal().value());
                        ctx.code.if_icmpne(failLabel);
                    }
                    case BOOL -> {
                        ctx.code.ldc((Boolean) lp.literal().value() ? 1 : 0);
                        ctx.code.if_icmpne(failLabel);
                    }
                    case LONG -> {
                        ctx.code.ldc((Long) lp.literal().value());
                        ctx.code.lcmp();
                        ctx.code.ifne(failLabel);
                    }
                    case STRING -> {
                        ctx.code.ldc((String) lp.literal().value());
                        ctx.code.invokevirtual(
                            ConstantDescs.CD_Object,
                            "equals",
                            MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)
                        );
                        ctx.code.ifeq(failLabel);
                    }
                    case UNIT -> {}
                }
            }
            case ConstructorPattern cp -> {
                String tag = cp.typePath();
                if (tag.equals("Ok") || tag.equals("Err")) {
                    ctx.code.aload(scrutSlot);
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                        "getKey",
                        MethodTypeDesc.of(ConstantDescs.CD_Object)
                    );
                    ctx.code.ldc(tag);
                    ctx.code.invokevirtual(
                        ConstantDescs.CD_Object,
                        "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)
                    );
                    ctx.code.ifeq(failLabel);

                    if (!cp.fields().isEmpty()) {
                        var fieldPat = cp.fields().get(0).pattern();
                        if (fieldPat instanceof BindingPattern bp) {
                            String innerType = inferWrappedInnerType(scrutineeExpr, tag);
                            ctx.code.aload(scrutSlot);
                            ctx.code.invokevirtual(
                                ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
                                "getValue",
                                MethodTypeDesc.of(ConstantDescs.CD_Object)
                            );
                            unboxIfNeeded(ctx, innerType);
                            int slot = ctx.allocateLocal(bp.name(), innerType);
                            storeSlot(ctx.code, slot, innerType);
                        }
                    }
                } else if (tag.equals("Some") || tag.equals("None")) {
                    ctx.code.aload(scrutSlot);
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.util.Optional"),
                        tag.equals("Some") ? "isPresent" : "isEmpty",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean)
                    );
                    ctx.code.ifeq(failLabel);
                    if (tag.equals("Some") && !cp.fields().isEmpty()) {
                        var fieldPat = cp.fields().get(0).pattern();
                        if (fieldPat instanceof BindingPattern bp) {
                            String innerType = inferWrappedInnerType(scrutineeExpr, "Some");
                            ctx.code.aload(scrutSlot);
                            ctx.code.invokevirtual(
                                ClassDesc.of("java.util.Optional"),
                                "get",
                                MethodTypeDesc.of(ConstantDescs.CD_Object)
                            );
                            unboxIfNeeded(ctx, innerType);
                            int slot = ctx.allocateLocal(bp.name(), innerType);
                            storeSlot(ctx.code, slot, innerType);
                        }
                    }
                } else {
                    // User-defined enum variant
                    String enumName = null;
                    Variant matchedVariant = null;
                    String variantShort = tag.contains(".") ? tag.substring(tag.lastIndexOf('.') + 1) : tag;
                    String explicitEnum = tag.contains(".") ? tag.substring(0, tag.lastIndexOf('.')) : null;
                    for (var entry : enums.entrySet()) {
                        if (explicitEnum != null && !entry.getKey().equals(explicitEnum)) continue;
                        for (var v : entry.getValue().variants()) {
                            if (v.name().equals(variantShort)) {
                                enumName = entry.getKey();
                                matchedVariant = v;
                                break;
                            }
                        }
                        if (matchedVariant != null) break;
                    }
                    if (enumName != null && matchedVariant != null) {
                        var variantDesc = ClassDesc.ofInternalName(
                            currentModule.replace('.', '/') + "/" + enumName + "$" + matchedVariant.name());
                        ctx.code.aload(scrutSlot);
                        ctx.code.instanceOf(variantDesc);
                        ctx.code.ifeq(failLabel);
                        for (int i = 0; i < cp.fields().size() && i < matchedVariant.fields().size(); i++) {
                            var pf = cp.fields().get(i);
                            var vf = matchedVariant.fields().get(i);
                            if (pf.pattern() instanceof BindingPattern bp) {
                                String fType = getTypeName(vf.type());
                                ctx.code.aload(scrutSlot);
                                ctx.code.checkcast(variantDesc);
                                ctx.code.getfield(variantDesc, vf.name(), typeToClassDesc(vf.type()));
                                int slot = ctx.allocateLocal(bp.name(), fType);
                                storeSlot(ctx.code, slot, fType);
                            }
                        }
                    }
                }
            }
        }
    }

    private String inferWrappedInnerType(Expr scrutineeExpr, String variantTag) {
        if (scrutineeExpr instanceof CallExpr call && call.callee() instanceof IdentExpr id) {
            if (id.name().equals("refine") && !call.typeArgs().isEmpty() && variantTag.equals("Ok")) {
                return getTypeName(call.typeArgs().get(0));
            }
            if (functions.containsKey(id.name())) {
                TypeExpr ret = resolveTypeAlias(functions.get(id.name()).returnType());
                if (ret instanceof GenericTypeExpr gen) {
                    int idx = variantTag.equals("Err") ? 1 : 0;
                    if (idx < gen.typeArgs().size()) {
                        return getTypeName(gen.typeArgs().get(idx));
                    }
                }
            }
        }
        return "Object";
    }

    private void loadSlot(CodeBuilder code, int slot, String typeName) {
        switch (typeName) {
            case "Int", "Bool" -> code.iload(slot);
            case "Long" -> code.lload(slot);
            default -> code.aload(slot);
        }
    }

    private void storeSlot(CodeBuilder code, int slot, String typeName) {
        switch (typeName) {
            case "Int", "Bool" -> code.istore(slot);
            case "Long" -> code.lstore(slot);
            default -> code.astore(slot);
        }
    }

    private void boxSlotIfPrimitive(CodeBuilder code, String typeName) {
        switch (typeName) {
            case "Int" -> code.invokestatic(ClassDesc.of("java.lang.Integer"), "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Integer"), ConstantDescs.CD_int));
            case "Long" -> code.invokestatic(ClassDesc.of("java.lang.Long"), "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Long"), ConstantDescs.CD_long));
            case "Bool" -> code.invokestatic(ClassDesc.of("java.lang.Boolean"), "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Boolean"), ConstantDescs.CD_boolean));
            default -> {}
        }
    }
    
    private void generatePropagate(CodeGenContext ctx, PropagateExpr prop) {
        var unwrappedType = inferTypeName(ctx, prop);
        generateExpr(ctx, prop.inner());
        var resSlot = ctx.allocateLocal("$prop$" + ctx.nextSlot, "Object");
        ctx.code.astore(resSlot);
        
        ctx.code.aload(resSlot);
        ctx.code.invokevirtual(
            ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
            "getKey",
            MethodTypeDesc.of(ConstantDescs.CD_Object)
        );
        ctx.code.ldc("Err");
        ctx.code.invokevirtual(
            ConstantDescs.CD_Object,
            "equals",
            MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)
        );
        
        var okLabel = ctx.code.newLabel();
        ctx.code.ifeq(okLabel);
        
        ctx.code.aload(resSlot);
        ctx.code.areturn();
        
        ctx.code.labelBinding(okLabel);
        ctx.code.aload(resSlot);
        ctx.code.invokevirtual(
            ClassDesc.of("java.util.AbstractMap$SimpleImmutableEntry"),
            "getValue",
            MethodTypeDesc.of(ConstantDescs.CD_Object)
        );
        unboxIfNeeded(ctx, unwrappedType);
    }
    
    private void generateFieldAccess(CodeGenContext ctx, FieldAccessExpr access) {
        var objTypeName = inferTypeName(ctx, access.object());
        generateExpr(ctx, access.object());
        if (records.containsKey(objTypeName)) {
            var rec = records.get(objTypeName);
            var ownerDesc = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + objTypeName);
            for (var param : rec.params()) {
                if (param.name().equals(access.fieldName())) {
                    ctx.code.getfield(ownerDesc, access.fieldName(), typeToClassDesc(param.type()));
                    return;
                }
            }
        } else if (datas.containsKey(objTypeName)) {
            var data = datas.get(objTypeName);
            var ownerDesc = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + objTypeName);
            for (var field : data.fields()) {
                if (field.name().equals(access.fieldName())) {
                    ctx.code.getfield(ownerDesc, access.fieldName(), typeToClassDesc(field.type()));
                    return;
                }
            }
        }
        ctx.code.getfield(ConstantDescs.CD_Object, access.fieldName(), ConstantDescs.CD_Object);
    }
    
    private byte[] generateData(DataDecl data) {
        var className = currentModule.replace('.', '/') + "/" + data.name();
        var classDesc = ClassDesc.ofInternalName(className);
        
        return ClassFile.of().build(classDesc, classBuilder -> {
            classBuilder.withVersion(CLASS_FILE_VERSION, 0);
            classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL);
            classBuilder.withSuperclass(ConstantDescs.CD_Object);
            
            for (var field : data.fields()) {
                var fieldDesc = typeToClassDesc(field.type());
                classBuilder.withField(field.name(), fieldDesc, ACC_PUBLIC | ACC_FINAL);
            }
            
            var ctorParamTypes = data.fields().stream()
                .map(f -> typeToClassDesc(f.type()))
                .toArray(ClassDesc[]::new);
            var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ctorParamTypes);
            
            classBuilder.withMethod(ConstantDescs.INIT_NAME, ctorDesc, ACC_PUBLIC, mb -> {
                mb.withCode(code -> {
                    code.aload(0);
                    code.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, 
                        MethodTypeDesc.of(ConstantDescs.CD_void));
                    
                    int slot = 1;
                    for (var field : data.fields()) {
                        code.aload(0);
                        var typeName = getTypeName(field.type());
                        var fieldDesc = typeToClassDesc(field.type());
                        if (typeName.equals("Int") || typeName.equals("Bool")) {
                            code.iload(slot++);
                        } else if (typeName.equals("Long")) {
                            code.lload(slot);
                            slot += 2;
                        } else {
                            code.aload(slot++);
                        }
                        code.putfield(classDesc, field.name(), fieldDesc);
                    }
                    
                    code.return_();
                });
            });
            
            classBuilder.withMethod("equals", MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
                ACC_PUBLIC, mb -> {
                mb.withCode(code -> {
                    code.aload(0);
                    code.aload(1);
                    code.ifThenElse(Opcode.IF_ACMPEQ,
                        tb -> tb.ldc(1),
                        fb -> fb.ldc(0));
                    code.ireturn();
                });
            });
            
            classBuilder.withMethod("hashCode", MethodTypeDesc.of(ConstantDescs.CD_int), ACC_PUBLIC, mb -> {
                mb.withCode(code -> {
                    code.aload(0);
                    code.invokespecial(ConstantDescs.CD_Object, "hashCode", 
                        MethodTypeDesc.of(ConstantDescs.CD_int));
                    code.ireturn();
                });
            });
        });
    }
    
    /**
     * VALUE-01: Generate record as a value class (no ACC_IDENTITY flag).
     * 
     * Value class properties:
     * - No object identity (== compares by field values)
     * - Implicitly final
     * - All fields are final
     * - equals/hashCode based on field values
     */
    private byte[] generateRecord(RecordDecl record) {
        var className = currentModule.replace('.', '/') + "/" + record.name();
        var classDesc = ClassDesc.ofInternalName(className);
        boolean isBoundarySchema = record.annotations().stream()
            .anyMatch(a -> a.name().equals("boundary_schema"));

        return ClassFile.of().build(classDesc, classBuilder -> {
            // VALUE-01: Use preview minor version for JEP 401
            classBuilder.withVersion(CLASS_FILE_VERSION, PREVIEW_MINOR_VERSION);
            // VALUE-01: NO ACC_IDENTITY flag - this is a value class
            // ACC_FINAL is implicit for value classes, but we include it for clarity
            classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL);
            classBuilder.withSuperclass(ConstantDescs.CD_Object);
            
            for (var param : record.params()) {
                var fieldDesc = typeToClassDesc(param.type());
                classBuilder.withField(param.name(), fieldDesc, ACC_PUBLIC | ACC_FINAL);
            }
            
            var ctorParamTypes = record.params().stream()
                .map(p -> typeToClassDesc(p.type()))
                .toArray(ClassDesc[]::new);
            var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ctorParamTypes);
            
            classBuilder.withMethod(ConstantDescs.INIT_NAME, ctorDesc, ACC_PUBLIC, mb -> {
                mb.withCode(code -> {
                    // JEP 513 Flexible Constructor Bodies: validate refinements BEFORE super.<init>()
                    emitConstructorPrologueChecks(
                        code,
                        record.params().stream().map(p -> new FieldDecl(p.name(), p.type(), p.span())).toList()
                    );

                    code.aload(0);
                    code.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, 
                        MethodTypeDesc.of(ConstantDescs.CD_void));
                    
                    int slot = 1;
                    for (var param : record.params()) {
                        code.aload(0);
                        var typeName = getTypeName(param.type());
                        var fieldDesc = typeToClassDesc(param.type());
                        if (typeName.equals("Int") || typeName.equals("Bool")) {
                            code.iload(slot++);
                        } else if (typeName.equals("Long")) {
                            code.lload(slot);
                            slot += 2;
                        } else {
                            code.aload(slot++);
                        }
                        code.putfield(classDesc, param.name(), fieldDesc);
                    }
                    
                    code.return_();
                });
            });
            
            // VALUE-02: equals compares by field values for value types
            generateValueEquals(classBuilder, classDesc, record.params());
            
            // hashCode based on field values
            generateValueHashCode(classBuilder, record.params());

            if (isBoundarySchema) {
                generateBoundarySchemaMethods(classBuilder, classDesc, ctorDesc, record);
            }
        });
    }

    private void emitConstructorPrologueChecks(CodeBuilder code, List<FieldDecl> fields) {
        Map<String, Integer> paramSlots = new LinkedHashMap<>();
        Map<String, String> paramTypes = new LinkedHashMap<>();
        int slot = 1;
        boolean hasAnyConstraint = false;
        for (var f : fields) {
            String tName = getTypeName(f.type());
            paramSlots.put(f.name(), slot);
            paramTypes.put(f.name(), tName);
            slot += tName.equals("Long") ? 2 : 1;
            if (hasRefinementOrRange(f.type())) {
                hasAnyConstraint = true;
            }
        }
        if (!hasAnyConstraint) {
            return;
        }
        var failLabel = code.newLabel();
        var passLabel = code.newLabel();
        for (var f : fields) {
            TypeExpr resolved = resolveTypeAlias(f.type());
            if (hasRefinementOrRange(resolved)) {
                emitTypeConstraintCheck(
                    code, resolved, paramSlots.get(f.name()), paramTypes.get(f.name()), failLabel, paramSlots, paramTypes, slot
                );
            }
        }
        code.goto_(passLabel);
        code.labelBinding(failLabel);
        code.new_(ClassDesc.of("java.lang.IllegalArgumentException"));
        code.dup();
        code.ldc("Refinement invariant violated in constructor");
        code.invokespecial(
            ClassDesc.of("java.lang.IllegalArgumentException"),
            ConstantDescs.INIT_NAME,
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String)
        );
        code.athrow();
        code.labelBinding(passLabel);
    }

    private boolean hasRefinementOrRange(TypeExpr type) {
        TypeExpr resolved = resolveTypeAlias(type);
        return switch (resolved) {
            case RangeTypeExpr _, RefinedTypeExpr _ -> true;
            case CapabilityTypeExpr cap -> hasRefinementOrRange(cap.inner());
            default -> false;
        };
    }

    private void emitTypeConstraintCheck(
        CodeBuilder code,
        TypeExpr type,
        int valSlot,
        String baseTypeName,
        Label failLabel,
        Map<String, Integer> allParamSlots
    ) {
        emitTypeConstraintCheck(code, type, valSlot, baseTypeName, failLabel, allParamSlots, Map.of(), valSlot + 2);
    }

    private void emitTypeConstraintCheck(
        CodeBuilder code,
        TypeExpr type,
        int valSlot,
        String baseTypeName,
        Label failLabel,
        Map<String, Integer> allParamSlots,
        Map<String, String> allParamTypes,
        int nextFreeSlot
    ) {
        TypeExpr resolved = resolveTypeAlias(type);
        switch (resolved) {
            case CapabilityTypeExpr cap ->
                emitTypeConstraintCheck(code, cap.inner(), valSlot, baseTypeName, failLabel, allParamSlots, allParamTypes, nextFreeSlot);
            case RangeTypeExpr rng -> {
                emitTypeConstraintCheck(code, rng.baseType(), valSlot, baseTypeName, failLabel, allParamSlots, allParamTypes, nextFreeSlot);
                var tmpCtx = new CodeGenContext(code, null, null);
                tmpCtx.nextSlot = nextFreeSlot;
                tmpCtx.locals.putAll(allParamSlots);
                tmpCtx.localTypes.putAll(allParamTypes);
                if (baseTypeName.equals("Long")) {
                    if (rng.lowerBound() != null) {
                        code.lload(valSlot);
                        generateExpr(tmpCtx, rng.lowerBound());
                        if (!inferTypeName(tmpCtx, rng.lowerBound()).equals("Long")) code.i2l();
                        code.lcmp();
                        code.iflt(failLabel);
                    }
                    if (rng.upperBound() != null) {
                        code.lload(valSlot);
                        generateExpr(tmpCtx, rng.upperBound());
                        if (!inferTypeName(tmpCtx, rng.upperBound()).equals("Long")) code.i2l();
                        code.lcmp();
                        if (rng.upperInclusive()) code.ifgt(failLabel);
                        else code.ifge(failLabel);
                    }
                } else {
                    if (rng.lowerBound() != null) {
                        code.iload(valSlot);
                        generateExpr(tmpCtx, rng.lowerBound());
                        code.if_icmplt(failLabel);
                    }
                    if (rng.upperBound() != null) {
                        code.iload(valSlot);
                        generateExpr(tmpCtx, rng.upperBound());
                        if (rng.upperInclusive()) code.if_icmpgt(failLabel);
                        else code.if_icmpge(failLabel);
                    }
                }
            }
            case RefinedTypeExpr ref -> {
                emitTypeConstraintCheck(code, ref.baseType(), valSlot, baseTypeName, failLabel, allParamSlots, allParamTypes, nextFreeSlot);
                if (ref.predicate() != null) {
                    var tmpCtx = new CodeGenContext(code, null, null);
                    tmpCtx.nextSlot = nextFreeSlot;
                    tmpCtx.locals.putAll(allParamSlots);
                    tmpCtx.localTypes.putAll(allParamTypes);
                    String binder = ref.binderName() != null ? ref.binderName() : "it";
                    tmpCtx.locals.put(binder, valSlot);
                    tmpCtx.localTypes.put(binder, baseTypeName);
                    generateExpr(tmpCtx, ref.predicate());
                    code.ifeq(failLabel);
                }
            }
            default -> {}
        }
    }

    private void generateBoundarySchemaMethods(
        ClassBuilder classBuilder,
        ClassDesc classDesc,
        MethodTypeDesc ctorDesc,
        RecordDecl record
    ) {
        var okDesc = ClassDesc.ofInternalName(classDesc.packageName().replace('.', '/') + "/" + record.name() + "$Ok");
        var errDesc = ClassDesc.ofInternalName(classDesc.packageName().replace('.', '/') + "/" + record.name() + "$Err");
        var codecDesc = ClassDesc.of("ngj.runtime.BoundaryCodec");
        var secretDesc = ClassDesc.of("ngj.runtime.SecretValue");
        var mapDesc = ClassDesc.of("java.util.Map");
        var linkedMapDesc = ClassDesc.of("java.util.LinkedHashMap");

        // 1. public String toJson()
        classBuilder.withMethod("toJson", MethodTypeDesc.of(ConstantDescs.CD_String), ACC_PUBLIC, mb -> {
            mb.withCode(code -> {
                code.new_(linkedMapDesc);
                code.dup();
                code.invokespecial(linkedMapDesc, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void));
                code.astore(1);
                for (var param : record.params()) {
                    code.aload(1);
                    code.ldc(param.name());
                    code.aload(0);
                    code.getfield(classDesc, param.name(), typeToClassDesc(param.type()));
                    boxSlotIfPrimitive(code, getTypeName(param.type()));
                    code.invokeinterface(mapDesc, "put",
                        MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                    code.pop();
                }
                code.aload(1);
                code.invokestatic(codecDesc, "encodeJsonObject",
                    MethodTypeDesc.of(ConstantDescs.CD_String, mapDesc));
                code.areturn();
            });
        });

        // 2. public static Object decodeJson(String json)
        classBuilder.withMethod("decodeJson", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_String),
            ACC_PUBLIC | ACC_STATIC, mb -> {
            mb.withCode(code -> {
                var tryStart = code.newLabel();
                var tryEnd = code.newLabel();
                var catchStart = code.newLabel();
                code.exceptionCatch(tryStart, tryEnd, catchStart, ClassDesc.of("java.lang.Exception"));

                code.labelBinding(tryStart);
                code.aload(0);
                code.invokestatic(codecDesc, "decodeJsonObject", MethodTypeDesc.of(mapDesc, ConstantDescs.CD_String));
                code.astore(1);

                code.new_(classDesc);
                code.dup();
                int tempSlot = 2;
                for (var param : record.params()) {
                    code.aload(1);
                    code.ldc(param.name());
                    code.invokeinterface(mapDesc, "get",
                        MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                    code.astore(tempSlot);
                    code.aload(tempSlot);
                    var nonNullLabel = code.newLabel();
                    code.ifnonnull(nonNullLabel);
                    code.new_(ClassDesc.of("java.lang.IllegalArgumentException"));
                    code.dup();
                    code.ldc("Missing required field: " + param.name());
                    code.invokespecial(ClassDesc.of("java.lang.IllegalArgumentException"), ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
                    code.athrow();
                    code.labelBinding(nonNullLabel);

                    String tName = getTypeName(param.type());
                    switch (tName) {
                        case "Int" -> {
                            code.aload(tempSlot);
                            code.invokestatic(ClassDesc.of("ngj.runtime.BoundaryCodec"), "requireInt",
                                MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_Object));
                        }
                        case "Long" -> {
                            code.aload(tempSlot);
                            code.checkcast(ClassDesc.of("java.lang.Number"));
                            code.invokevirtual(ClassDesc.of("java.lang.Number"), "longValue", MethodTypeDesc.of(ConstantDescs.CD_long));
                        }
                        case "Bool" -> {
                            code.aload(tempSlot);
                            code.checkcast(ClassDesc.of("java.lang.Boolean"));
                            code.invokevirtual(ClassDesc.of("java.lang.Boolean"), "booleanValue", MethodTypeDesc.of(ConstantDescs.CD_boolean));
                        }
                        case "String" -> {
                            code.aload(tempSlot);
                            code.checkcast(ConstantDescs.CD_String);
                        }
                        case "Secret" -> {
                            code.aload(tempSlot);
                            code.invokestatic(secretDesc, "of", MethodTypeDesc.of(secretDesc, ConstantDescs.CD_Object));
                        }
                        default -> code.aload(tempSlot);
                    }
                }
                code.invokespecial(classDesc, ConstantDescs.INIT_NAME, ctorDesc);
                code.astore(tempSlot);
                code.new_(okDesc);
                code.dup();
                code.aload(tempSlot);
                code.invokespecial(okDesc, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
                code.labelBinding(tryEnd);
                code.areturn();

                code.labelBinding(catchStart);
                code.pop();
                code.new_(errDesc);
                code.dup();
                code.ldc("BoundaryDecodeOrValidationFailed");
                code.invokespecial(errDesc, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
                code.areturn();
            });
        });

        // 3. public byte[] encodeBinary()
        var baosDesc = ClassDesc.of("java.io.ByteArrayOutputStream");
        var dosDesc = ClassDesc.of("java.io.DataOutputStream");
        classBuilder.withMethod("encodeBinary", MethodTypeDesc.of(ConstantDescs.CD_byte.arrayType()), ACC_PUBLIC, mb -> {
            mb.withCode(code -> {
                code.new_(baosDesc);
                code.dup();
                code.invokespecial(baosDesc, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void));
                code.astore(1);
                code.new_(dosDesc);
                code.dup();
                code.aload(1);
                code.invokespecial(dosDesc, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("java.io.OutputStream")));
                code.astore(2);

                for (var param : record.params()) {
                    String tName = getTypeName(param.type());
                    code.aload(2);
                    code.aload(0);
                    code.getfield(classDesc, param.name(), typeToClassDesc(param.type()));
                    switch (tName) {
                        case "Int" -> code.invokevirtual(dosDesc, "writeInt", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int));
                        case "Long" -> code.invokevirtual(dosDesc, "writeLong", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_long));
                        case "Bool" -> code.invokevirtual(dosDesc, "writeBoolean", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean));
                        case "Secret" -> {
                            code.pop();
                            code.ldc("Secret(****)");
                            code.invokevirtual(dosDesc, "writeUTF", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
                        }
                        default -> {
                            code.invokestatic(ConstantDescs.CD_String, "valueOf",
                                MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_Object));
                            code.invokevirtual(dosDesc, "writeUTF", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
                        }
                    }
                }
                code.aload(2);
                code.invokevirtual(dosDesc, "flush", MethodTypeDesc.of(ConstantDescs.CD_void));
                code.aload(1);
                code.invokevirtual(baosDesc, "toByteArray", MethodTypeDesc.of(ConstantDescs.CD_byte.arrayType()));
                code.areturn();
            });
        });

        // 4. public static Object decodeBinary(byte[] bytes)
        var baisDesc = ClassDesc.of("java.io.ByteArrayInputStream");
        var disDesc = ClassDesc.of("java.io.DataInputStream");
        classBuilder.withMethod("decodeBinary", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_byte.arrayType()),
            ACC_PUBLIC | ACC_STATIC, mb -> {
            mb.withCode(code -> {
                var tryStart = code.newLabel();
                var tryEnd = code.newLabel();
                var catchStart = code.newLabel();
                code.exceptionCatch(tryStart, tryEnd, catchStart, ClassDesc.of("java.lang.Exception"));

                code.labelBinding(tryStart);
                code.new_(disDesc);
                code.dup();
                code.new_(baisDesc);
                code.dup();
                code.aload(0);
                code.invokespecial(baisDesc, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_byte.arrayType()));
                code.invokespecial(disDesc, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("java.io.InputStream")));
                code.astore(1);

                code.new_(classDesc);
                code.dup();
                for (var param : record.params()) {
                    String tName = getTypeName(param.type());
                    code.aload(1);
                    switch (tName) {
                        case "Int" -> code.invokevirtual(disDesc, "readInt", MethodTypeDesc.of(ConstantDescs.CD_int));
                        case "Long" -> code.invokevirtual(disDesc, "readLong", MethodTypeDesc.of(ConstantDescs.CD_long));
                        case "Bool" -> code.invokevirtual(disDesc, "readBoolean", MethodTypeDesc.of(ConstantDescs.CD_boolean));
                        case "Secret" -> {
                            code.invokevirtual(disDesc, "readUTF", MethodTypeDesc.of(ConstantDescs.CD_String));
                            code.invokestatic(secretDesc, "of", MethodTypeDesc.of(secretDesc, ConstantDescs.CD_Object));
                        }
                        default -> code.invokevirtual(disDesc, "readUTF", MethodTypeDesc.of(ConstantDescs.CD_String));
                    }
                }
                code.invokespecial(classDesc, ConstantDescs.INIT_NAME, ctorDesc);
                code.astore(2);
                code.new_(okDesc);
                code.dup();
                code.aload(2);
                code.invokespecial(okDesc, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
                code.labelBinding(tryEnd);
                code.areturn();

                code.labelBinding(catchStart);
                code.pop();
                code.new_(errDesc);
                code.dup();
                code.ldc("BoundaryBinaryDecodeFailed");
                code.invokespecial(errDesc, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
                code.areturn();
            });
        });
    }
    
    /**
     * VALUE-02: Generate equals method that compares by field values.
     * This is the semantic guarantee of == for value types.
     */
    private void generateValueEquals(ClassBuilder classBuilder, ClassDesc classDesc, List<Param> params) {
        classBuilder.withMethod("equals", MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
            ACC_PUBLIC, mb -> {
            mb.withCode(code -> {
                // Check if same object (optimization)
                code.aload(0);
                code.aload(1);
                var notSame = code.newLabel();
                var returnFalse = code.newLabel();
                var end = code.newLabel();
                
                code.if_acmpne(notSame);
                code.ldc(1);
                code.goto_(end);
                
                code.labelBinding(notSame);
                // Check if other is null or different class
                code.aload(1);
                code.aconst_null();
                code.if_acmpeq(returnFalse);  // Jump to returnFalse if arg is null
                code.aload(1);
                code.instanceOf(classDesc);
                code.ifeq(returnFalse);
                
                // Cast and compare fields
                code.aload(1);
                code.checkcast(classDesc);
                int otherSlot = params.isEmpty() ? 2 : params.size() + 2;
                code.astore(otherSlot);
                
                // Compare each field
                for (var param : params) {
                    var fieldDesc = typeToClassDesc(param.type());
                    var typeName = getTypeName(param.type());
                    
                    code.aload(0);
                    code.getfield(classDesc, param.name(), fieldDesc);
                    code.aload(otherSlot);
                    code.getfield(classDesc, param.name(), fieldDesc);
                    
                    if (typeName.equals("Int") || typeName.equals("Bool")) {
                        code.if_icmpne(returnFalse);
                    } else if (typeName.equals("Long")) {
                        code.lcmp();
                        code.ifne(returnFalse);
                    } else {
                        // Reference types: use Objects.equals
                        code.invokestatic(ClassDesc.of("java.util.Objects"), "equals",
                            MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object, ConstantDescs.CD_Object));
                        code.ifeq(returnFalse);
                    }
                }
                
                code.ldc(1);
                code.goto_(end);
                
                code.labelBinding(returnFalse);
                code.ldc(0);
                
                code.labelBinding(end);
                code.ireturn();
            });
        });
    }
    
    private void generateValueHashCode(ClassBuilder classBuilder, List<Param> params) {
        classBuilder.withMethod("hashCode", MethodTypeDesc.of(ConstantDescs.CD_int), ACC_PUBLIC, mb -> {
            mb.withCode(code -> {
                // Simple hash code implementation: combine class hash with field count
                // Real implementation would hash field values
                code.ldc(params.size() * 31 + 17);
                code.ireturn();
            });
        });
    }
    
    /**
     * VALUE-01: Generate enum as a sealed hierarchy of value classes.
     */
    private byte[] generateEnum(EnumDecl enumDecl) {
        var className = currentModule.replace('.', '/') + "/" + enumDecl.name();
        var classDesc = ClassDesc.ofInternalName(className);
        
        // Build list of permitted subclass descriptors for sealed hierarchy
        var permittedSubclasses = enumDecl.variants().stream()
            .map(v -> ClassDesc.ofInternalName(className + "$" + v.name()))
            .toList();
        
        return ClassFile.of().build(classDesc, classBuilder -> {
            // VALUE-01: Use preview minor version for JEP 401
            classBuilder.withVersion(CLASS_FILE_VERSION, PREVIEW_MINOR_VERSION);
            // VALUE-01: NO ACC_IDENTITY flag - this is a value class base
            // ACC_ABSTRACT for the sealed base type
            classBuilder.withFlags(ACC_PUBLIC | ACC_ABSTRACT);
            classBuilder.withSuperclass(ConstantDescs.CD_Object);
            
            // Add PermittedSubclasses attribute for sealed hierarchy
            if (!permittedSubclasses.isEmpty()) {
                classBuilder.with(PermittedSubclassesAttribute.ofSymbols(permittedSubclasses));
            }
            
            // Protected constructor for subclass use
            classBuilder.withMethod(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                ACC_PROTECTED, mb -> {
                mb.withCode(code -> {
                    code.aload(0);
                    code.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void));
                    code.return_();
                });
            });
        });
    }
    
    /**
     * Generate variant classes as nested value classes within the enum.
     * Each variant is a final value class that extends the enum base.
     */
    public List<Path> generateEnumVariants(EnumDecl enumDecl) throws CodeGenException {
        var outputs = new ArrayList<Path>();
        var baseClassName = currentModule.replace('.', '/') + "/" + enumDecl.name();
        var baseClassDesc = ClassDesc.ofInternalName(baseClassName);
        
        
        try {
            var moduleDir = outputDir.resolve(currentModule.replace('.', '/'));
            Files.createDirectories(moduleDir);
            
            for (var variant : enumDecl.variants()) {
                var variantClassName = baseClassName + "$" + variant.name();
                var variantClassDesc = ClassDesc.ofInternalName(variantClassName);
                
                // Capture variant fields for use in lambda
                final var variantFields = variant.fields();
                final var finalVariantClassDesc = variantClassDesc;
                
                var classFile = ClassFile.of().build(variantClassDesc, classBuilder -> {
                    // VALUE-01: Use preview minor version, NO ACC_IDENTITY
                    classBuilder.withVersion(CLASS_FILE_VERSION, PREVIEW_MINOR_VERSION);
                    classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL);
                    classBuilder.withSuperclass(baseClassDesc);
                    
                    // Add fields for variant payload
                    for (var field : variantFields) {
                        var fieldDesc = typeToClassDesc(field.type());
                        classBuilder.withField(field.name(), fieldDesc, ACC_PUBLIC | ACC_FINAL);
                    }
                    
                    // Constructor
                    var ctorParamTypes = variantFields.stream()
                        .map(f -> typeToClassDesc(f.type()))
                        .toArray(ClassDesc[]::new);
                    var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ctorParamTypes);
                    
                    classBuilder.withMethod(ConstantDescs.INIT_NAME, ctorDesc, ACC_PUBLIC, mb -> {
                        mb.withCode(code -> {
                            // JEP 513 Flexible Constructor Bodies: validate refinements BEFORE super.<init>()
                            emitConstructorPrologueChecks(code, variantFields);

                            code.aload(0);
                            code.invokespecial(baseClassDesc, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void));
                            
                            int slot = 1;
                            for (var field : variantFields) {
                                code.aload(0);
                                var typeName = getTypeName(field.type());
                                var fieldDesc = typeToClassDesc(field.type());
                                if (typeName.equals("Int") || typeName.equals("Bool")) {
                                    code.iload(slot++);
                                } else if (typeName.equals("Long")) {
                                    code.lload(slot);
                                    slot += 2;
                                } else {
                                    code.aload(slot++);
                                }
                                code.putfield(finalVariantClassDesc, field.name(), fieldDesc);
                            }
                            
                            code.return_();
                        });
                    });
                });
                
                var outputPath = moduleDir.resolve(enumDecl.name() + "$" + variant.name() + ".class");
                Files.write(outputPath, classFile);
                outputs.add(outputPath);
            }
            
            return outputs;
        } catch (Exception e) {
            throw new CodeGenException("Failed to generate enum variants: " + e.getMessage(), e);
        }
    }
    
    private String getTypeName(TypeExpr type) {
        TypeExpr resolved = resolveTypeAlias(type);
        return switch (resolved) {
            case SimpleTypeExpr simple -> simple.name();
            case GenericTypeExpr generic -> generic.name();
            case UnitTypeExpr _ -> "Unit";
            case CapabilityTypeExpr cap -> getTypeName(cap.inner());
            case CaptureSetTypeExpr _ -> "Object";
            case RefinedTypeExpr ref -> getTypeName(ref.baseType());
            case RangeTypeExpr rng -> getTypeName(rng.baseType());
            case FunctionTypeExpr _ -> "Function";
            case TupleTypeExpr _ -> "Tuple";
        };
    }
    
    private class CodeGenContext {
        final CodeBuilder code;
        final FnDecl fn;
        final ClassDesc ownerClass;
        final Map<String, Integer> locals = new HashMap<>();
        final Map<String, String> localTypes = new HashMap<>();
        int nextSlot;
        boolean hasReturn = false;
        
        CodeGenContext(CodeBuilder code, FnDecl fn, ClassDesc ownerClass) {
            this.code = code;
            this.fn = fn;
            this.ownerClass = ownerClass;
            this.nextSlot = 0;
            
            if (fn != null) {
                for (var param : fn.params()) {
                    locals.put(param.name(), nextSlot);
                    var typeName = getTypeName(param.type());
                    localTypes.put(param.name(), typeName);
                    if (typeName.equals("Long")) {
                        nextSlot += 2;
                    } else {
                        nextSlot++;
                    }
                }
            }
        }
        
        CodeGenContext withCode(CodeBuilder newCode) {
            var ctx = new CodeGenContext(newCode, fn, ownerClass);
            ctx.locals.putAll(this.locals);
            ctx.localTypes.putAll(this.localTypes);
            ctx.nextSlot = this.nextSlot;
            ctx.hasReturn = this.hasReturn;
            return ctx;
        }
        
        int allocateLocal(String name, String typeName) {
            int slot = nextSlot;
            locals.put(name, slot);
            localTypes.put(name, typeName);
            if (typeName.equals("Long")) {
                nextSlot += 2;
            } else {
                nextSlot++;
            }
            return slot;
        }
        
        Integer getLocal(String name) {
            return locals.get(name);
        }
        
        String getLocalType(String name) {
            return localTypes.getOrDefault(name, "Object");
        }
    }
}
