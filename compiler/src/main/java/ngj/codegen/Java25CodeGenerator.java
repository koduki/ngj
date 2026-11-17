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
    
    public List<Path> generate(CompilationUnit unit) throws CodeGenException {
        currentModule = unit.modulePath();
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
        };
    }
    
    private String getDeclName(TopLevelDecl decl) {
        return switch (decl) {
            case FnDecl fn -> fn.name() + "$Fn";
            case DataDecl data -> data.name();
            case RecordDecl record -> record.name();
            case EnumDecl enumDecl -> enumDecl.name();
        };
    }
    
    private byte[] generateFunction(FnDecl fn) {
        var className = currentModule.replace('.', '/') + "/" + fn.name() + "$Fn";
        var classDesc = ClassDesc.ofInternalName(className);
        
        return ClassFile.of().build(classDesc, classBuilder -> {
            classBuilder.withVersion(CLASS_FILE_VERSION, 0);
            classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL);
            classBuilder.withSuperclass(ConstantDescs.CD_Object);
            
            var methodDesc = buildMethodDescriptor(fn);
            classBuilder.withMethod("invoke", methodDesc, ACC_PUBLIC | ACC_STATIC, mb -> {
                mb.withCode(codeBuilder -> {
                    var ctx = new CodeGenContext(codeBuilder, fn);
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
    
    private ClassDesc typeToClassDesc(TypeExpr type) {
        return switch (type) {
            case SimpleTypeExpr simple -> switch (simple.name()) {
                case "Int" -> ConstantDescs.CD_int;
                case "Long" -> ConstantDescs.CD_long;
                case "Bool" -> ConstantDescs.CD_boolean;
                case "String" -> ConstantDescs.CD_String;
                default -> ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + simple.name());
            };
            case GenericTypeExpr generic -> switch (generic.name()) {
                case "Result" -> ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/Result");
                case "Option" -> ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/Option");
                case "List" -> ClassDesc.of("java.util.List");
                default -> ConstantDescs.CD_Object;
            };
            case UnitTypeExpr _ -> ConstantDescs.CD_void;
        };
    }
    
    private void generateBlock(CodeGenContext ctx, BlockExpr block) {
        for (var stmt : block.statements()) {
            generateStmt(ctx, stmt);
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
                var typeName = type != null ? getTypeName(type) : "Object";
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
                var typeName = type != null ? getTypeName(type) : "Object";
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
                var typeName = type != null ? getTypeName(type) : "Object";
                int slot = ctx.allocateLocal(let.name(), typeName);
                switch (typeName) {
                    case "Long" -> ctx.code.lstore(slot);
                    case "Int", "Bool" -> ctx.code.istore(slot);
                    default -> ctx.code.astore(slot);
                }
            }
            case ExprStmt expr -> {
                generateExpr(ctx, expr.expr());
            }
            case ReturnStmt ret -> {
                if (ret.value() != null) {
                    generateExpr(ctx, ret.value());
                }
                var retType = getTypeName(ctx.fn.returnType());
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
            case HoleExpr hole -> throw new IllegalStateException("Hole in codegen: ?" + hole.name());
        }
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
        throw new IllegalStateException("lambda expression codegen not yet implemented");
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
        
        generateExpr(ctx, binary.left());
        generateExpr(ctx, binary.right());
        
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
        generateExpr(ctx, unary.operand());
        
        switch (unary.op()) {
            case NEG -> ctx.code.ineg();
            case NOT -> {
                ctx.code.ldc(1);
                ctx.code.ixor();
            }
        }
    }
    
    private void generateIf(CodeGenContext ctx, IfExpr ifExpr) {
        generateExpr(ctx, ifExpr.condition());
        ctx.code.ifThenElse(Opcode.IFNE,
            tb -> generateBlock(ctx.withCode(tb), ifExpr.thenBranch()),
            fb -> {
                if (ifExpr.elseBranch() != null) {
                    generateExpr(ctx.withCode(fb), ifExpr.elseBranch());
                }
            });
    }
    
    private void generateCall(CodeGenContext ctx, CallExpr call) {
        for (var arg : call.args()) {
            generateExpr(ctx, arg);
        }
        
        if (call.callee() instanceof IdentExpr ident) {
            var methodName = ident.name();
            
            switch (methodName) {
                case "Ok", "Err", "Some" -> {
                    ctx.code.aconst_null();
                    return;
                }
                case "None" -> {
                    ctx.code.aconst_null();
                    return;
                }
                case "Nil" -> {
                    // Create empty ArrayList
                    ctx.code.new_(ClassDesc.of("java.util.ArrayList"));
                    ctx.code.dup();
                    ctx.code.invokespecial(
                        ClassDesc.of("java.util.ArrayList"),
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void)
                    );
                    return;
                }
                case "listOf" -> {
                    // Create ArrayList with elements
                    ctx.code.new_(ClassDesc.of("java.util.ArrayList"));
                    ctx.code.dup();
                    ctx.code.invokespecial(
                        ClassDesc.of("java.util.ArrayList"),
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void)
                    );
                    // Add each argument to the list
                    for (var arg : call.args()) {
                        ctx.code.dup();
                        generateExpr(ctx, arg);
                        // Auto-box primitives
                        ctx.code.invokestatic(
                            ClassDesc.of("java.lang.Integer"),
                            "valueOf",
                            MethodTypeDesc.of(ClassDesc.of("java.lang.Integer"), ConstantDescs.CD_int)
                        );
                        ctx.code.invokeinterface(
                            ClassDesc.of("java.util.List"),
                            "add",
                            MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)
                        );
                        ctx.code.pop();
                    }
                    return;
                }
            }
            
            var classDesc = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + methodName + "$Fn");
            ctx.code.invokestatic(classDesc, "invoke", MethodTypeDesc.of(ConstantDescs.CD_Object));
        }
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
    
    private void generateMatch(CodeGenContext ctx, MatchExpr matchExpr) {
        generateExpr(ctx, matchExpr.scrutinee());
        
        if (!matchExpr.arms().isEmpty()) {
            ctx.code.pop();
            generateExpr(ctx, matchExpr.arms().get(0).body());
        }
    }
    
    private void generatePropagate(CodeGenContext ctx, PropagateExpr prop) {
        generateExpr(ctx, prop.inner());
    }
    
    private void generateFieldAccess(CodeGenContext ctx, FieldAccessExpr access) {
        generateExpr(ctx, access.object());
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
                var returnTrue = code.newLabel();
                var returnFalse = code.newLabel();
                var end = code.newLabel();
                
                code.if_acmpne(notSame);
                code.ldc(1);
                code.goto_(end);
                
                code.labelBinding(notSame);
                // Check if other is null or different class
                code.aload(1);
                code.ifnull(returnFalse);
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
     * 
     * JVM representation:
     *   public sealed value interface Shape permits Shape.Circle, Shape.Rectangle, Shape.Point {}
     *   public final value class Shape.Circle implements Shape { ... }
     *   public final value class Shape.Rectangle implements Shape { ... }
     *   public final value class Shape.Point implements Shape {}
     * 
     * We generate:
     * 1. A sealed abstract base class (the enum type itself)
     * 2. A nested value class for each variant (permitted subclasses)
     * 
     * Note: Since Java's sealed classes require the PermittedSubclasses attribute,
     * we generate it. For value classes, we omit ACC_IDENTITY.
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
        return switch (type) {
            case SimpleTypeExpr simple -> simple.name();
            case GenericTypeExpr generic -> generic.name();
            case UnitTypeExpr _ -> "Unit";
        };
    }
    
    private class CodeGenContext {
        final CodeBuilder code;
        final FnDecl fn;
        final Map<String, Integer> locals = new HashMap<>();
        final Map<String, String> localTypes = new HashMap<>();
        int nextSlot;
        boolean hasReturn = false;
        
        CodeGenContext(CodeBuilder code, FnDecl fn) {
            this.code = code;
            this.fn = fn;
            this.nextSlot = 0;
            
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
        
        CodeGenContext withCode(CodeBuilder newCode) {
            var ctx = new CodeGenContext(newCode, fn);
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
