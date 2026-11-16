package ngj.codegen;

import ngj.ast.*;

import org.objectweb.asm.*;
import static org.objectweb.asm.Opcodes.*;

import java.nio.file.*;
import java.util.*;

/**
 * Code generator targeting Java 25 class files using ASM.
 * Note: Targets Java 21 bytecode for MVP, can be upgraded to Java 25 when available.
 */
public final class Java25CodeGenerator {
    
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
            case EnumDecl enumDecl -> generateEnum(enumDecl);
        };
    }
    
    private String getDeclName(TopLevelDecl decl) {
        return switch (decl) {
            case FnDecl fn -> fn.name() + "$Fn";
            case DataDecl data -> data.name();
            case EnumDecl enumDecl -> enumDecl.name();
        };
    }
    
    private byte[] generateFunction(FnDecl fn) {
        var className = currentModule.replace('.', '/') + "/" + fn.name() + "$Fn";
        
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL, className, null, "java/lang/Object", null);
        
        var methodDesc = buildMethodDescriptor(fn);
        var mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "invoke", methodDesc, null, null);
        mv.visitCode();
        
        var ctx = new CodeGenContext(mv, fn);
        generateBlock(ctx, fn.body());
        
        if (!ctx.hasReturn) {
            var retType = getTypeName(fn.returnType());
            switch (retType) {
                case "Unit" -> mv.visitInsn(RETURN);
                case "Int", "Bool" -> mv.visitInsn(IRETURN);
                case "Long" -> mv.visitInsn(LRETURN);
                default -> mv.visitInsn(ARETURN);
            }
        }
        
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        
        if (fn.params().isEmpty() && getTypeName(fn.returnType()).equals("Int")) {
            var mainMv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
            mainMv.visitCode();
            mainMv.visitMethodInsn(INVOKESTATIC, className, "invoke", methodDesc, false);
            mainMv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "exit", "(I)V", false);
            mainMv.visitInsn(RETURN);
            mainMv.visitMaxs(0, 0);
            mainMv.visitEnd();
        }
        
        cw.visitEnd();
        return cw.toByteArray();
    }
    
    private String buildMethodDescriptor(FnDecl fn) {
        var sb = new StringBuilder("(");
        for (var param : fn.params()) {
            sb.append(typeToDescriptor(param.type()));
        }
        sb.append(")");
        sb.append(typeToDescriptor(fn.returnType()));
        return sb.toString();
    }
    
    private String typeToDescriptor(TypeExpr type) {
        return switch (type) {
            case SimpleTypeExpr simple -> switch (simple.name()) {
                case "Int" -> "I";
                case "Long" -> "J";
                case "Bool" -> "Z";
                case "String" -> "Ljava/lang/String;";
                default -> "L" + currentModule.replace('.', '/') + "/" + simple.name() + ";";
            };
            case GenericTypeExpr generic -> switch (generic.name()) {
                case "Result" -> "L" + currentModule.replace('.', '/') + "/Result;";
                case "Option" -> "L" + currentModule.replace('.', '/') + "/Option;";
                default -> "Ljava/lang/Object;";
            };
            case UnitTypeExpr _ -> "V";
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
            case LetStmt let -> {
                generateExpr(ctx, let.initializer());
                var type = let.explicitType();
                var typeName = type != null ? getTypeName(type) : "Object";
                int slot = ctx.allocateLocal(let.name(), typeName);
                switch (typeName) {
                    case "Long" -> ctx.mv.visitVarInsn(LSTORE, slot);
                    case "Int", "Bool" -> ctx.mv.visitVarInsn(ISTORE, slot);
                    default -> ctx.mv.visitVarInsn(ASTORE, slot);
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
                    case "Unit" -> ctx.mv.visitInsn(RETURN);
                    case "Int", "Bool" -> ctx.mv.visitInsn(IRETURN);
                    case "Long" -> ctx.mv.visitInsn(LRETURN);
                    default -> ctx.mv.visitInsn(ARETURN);
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
            case HoleExpr hole -> throw new IllegalStateException("Hole in codegen: ?" + hole.name());
        }
    }
    
    private void generateLiteral(CodeGenContext ctx, LiteralExpr lit) {
        switch (lit.kind()) {
            case INT -> ctx.mv.visitLdcInsn((Integer) lit.value());
            case LONG -> ctx.mv.visitLdcInsn((Long) lit.value());
            case BOOL -> ctx.mv.visitLdcInsn((Boolean) lit.value() ? 1 : 0);
            case STRING -> ctx.mv.visitLdcInsn((String) lit.value());
            case UNIT -> {}
        }
    }
    
    private void generateIdent(CodeGenContext ctx, IdentExpr ident) {
        Integer slot = ctx.getLocal(ident.name());
        if (slot != null) {
            var typeName = ctx.getLocalType(ident.name());
            switch (typeName) {
                case "Int", "Bool" -> ctx.mv.visitVarInsn(ILOAD, slot);
                case "Long" -> ctx.mv.visitVarInsn(LLOAD, slot);
                default -> ctx.mv.visitVarInsn(ALOAD, slot);
            }
        } else {
            ctx.mv.visitInsn(ACONST_NULL);
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
            case ADD -> ctx.mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "addExact", "(II)I", false);
            case SUB -> ctx.mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "subtractExact", "(II)I", false);
            case MUL -> ctx.mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "multiplyExact", "(II)I", false);
            case DIV -> ctx.mv.visitInsn(IDIV);
            case MOD -> ctx.mv.visitInsn(IREM);
            case EQ -> generateComparison(ctx, IF_ICMPEQ);
            case NE -> generateComparison(ctx, IF_ICMPNE);
            case LT -> generateComparison(ctx, IF_ICMPLT);
            case LE -> generateComparison(ctx, IF_ICMPLE);
            case GT -> generateComparison(ctx, IF_ICMPGT);
            case GE -> generateComparison(ctx, IF_ICMPGE);
            default -> {}
        }
    }
    
    private void generateComparison(CodeGenContext ctx, int opcode) {
        var trueLabel = new Label();
        var endLabel = new Label();
        
        ctx.mv.visitJumpInsn(opcode, trueLabel);
        ctx.mv.visitLdcInsn(0);
        ctx.mv.visitJumpInsn(GOTO, endLabel);
        ctx.mv.visitLabel(trueLabel);
        ctx.mv.visitLdcInsn(1);
        ctx.mv.visitLabel(endLabel);
    }
    
    private void generateShortCircuitAnd(CodeGenContext ctx, BinaryExpr binary) {
        var falseLabel = new Label();
        var endLabel = new Label();
        
        generateExpr(ctx, binary.left());
        ctx.mv.visitJumpInsn(IFEQ, falseLabel);
        
        generateExpr(ctx, binary.right());
        ctx.mv.visitJumpInsn(GOTO, endLabel);
        
        ctx.mv.visitLabel(falseLabel);
        ctx.mv.visitLdcInsn(0);
        
        ctx.mv.visitLabel(endLabel);
    }
    
    private void generateShortCircuitOr(CodeGenContext ctx, BinaryExpr binary) {
        var trueLabel = new Label();
        var endLabel = new Label();
        
        generateExpr(ctx, binary.left());
        ctx.mv.visitJumpInsn(IFNE, trueLabel);
        
        generateExpr(ctx, binary.right());
        ctx.mv.visitJumpInsn(GOTO, endLabel);
        
        ctx.mv.visitLabel(trueLabel);
        ctx.mv.visitLdcInsn(1);
        
        ctx.mv.visitLabel(endLabel);
    }
    
    private void generateUnary(CodeGenContext ctx, UnaryExpr unary) {
        generateExpr(ctx, unary.operand());
        
        switch (unary.op()) {
            case NEG -> ctx.mv.visitInsn(INEG);
            case NOT -> {
                ctx.mv.visitLdcInsn(1);
                ctx.mv.visitInsn(IXOR);
            }
        }
    }
    
    private void generateIf(CodeGenContext ctx, IfExpr ifExpr) {
        var elseLabel = new Label();
        var endLabel = new Label();
        
        generateExpr(ctx, ifExpr.condition());
        ctx.mv.visitJumpInsn(IFEQ, elseLabel);
        
        generateBlock(ctx, ifExpr.thenBranch());
        ctx.mv.visitJumpInsn(GOTO, endLabel);
        
        ctx.mv.visitLabel(elseLabel);
        if (ifExpr.elseBranch() != null) {
            generateExpr(ctx, ifExpr.elseBranch());
        }
        
        ctx.mv.visitLabel(endLabel);
    }
    
    private void generateCall(CodeGenContext ctx, CallExpr call) {
        for (var arg : call.args()) {
            generateExpr(ctx, arg);
        }
        
        if (call.callee() instanceof IdentExpr ident) {
            var methodName = ident.name();
            
            switch (methodName) {
                case "Ok", "Err", "Some" -> {
                    ctx.mv.visitInsn(ACONST_NULL);
                    return;
                }
                case "None" -> {
                    ctx.mv.visitInsn(ACONST_NULL);
                    return;
                }
            }
            
            var className = currentModule.replace('.', '/') + "/" + methodName + "$Fn";
            ctx.mv.visitMethodInsn(INVOKESTATIC, className, "invoke", "()Ljava/lang/Object;", false);
        }
    }
    
    private void generateConstructor(CodeGenContext ctx, ConstructorExpr ctor) {
        var className = currentModule.replace('.', '/') + "/" + ctor.typePath();
        
        ctx.mv.visitTypeInsn(NEW, className);
        ctx.mv.visitInsn(DUP);
        
        for (var field : ctor.fields()) {
            generateExpr(ctx, field.value());
        }
        
        var sb = new StringBuilder("(");
        for (int i = 0; i < ctor.fields().size(); i++) {
            sb.append("Ljava/lang/Object;");
        }
        sb.append(")V");
        
        ctx.mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", sb.toString(), false);
    }
    
    private void generateMatch(CodeGenContext ctx, MatchExpr matchExpr) {
        generateExpr(ctx, matchExpr.scrutinee());
        
        if (!matchExpr.arms().isEmpty()) {
            ctx.mv.visitInsn(POP);
            generateExpr(ctx, matchExpr.arms().get(0).body());
        }
    }
    
    private void generatePropagate(CodeGenContext ctx, PropagateExpr prop) {
        generateExpr(ctx, prop.inner());
    }
    
    private void generateFieldAccess(CodeGenContext ctx, FieldAccessExpr access) {
        generateExpr(ctx, access.object());
        ctx.mv.visitFieldInsn(GETFIELD, "java/lang/Object", access.fieldName(), "Ljava/lang/Object;");
    }
    
    private byte[] generateData(DataDecl data) {
        var className = currentModule.replace('.', '/') + "/" + data.name();
        
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL, className, null, "java/lang/Object", null);
        
        for (var field : data.fields()) {
            cw.visitField(ACC_PUBLIC | ACC_FINAL, field.name(), 
                typeToDescriptor(field.type()), null, null).visitEnd();
        }
        
        var ctorDesc = buildConstructorDescriptor(data.fields());
        var mv = cw.visitMethod(ACC_PUBLIC, "<init>", ctorDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        
        int slot = 1;
        for (var field : data.fields()) {
            mv.visitVarInsn(ALOAD, 0);
            var typeName = getTypeName(field.type());
            if (typeName.equals("Int") || typeName.equals("Bool")) {
                mv.visitVarInsn(ILOAD, slot++);
            } else if (typeName.equals("Long")) {
                mv.visitVarInsn(LLOAD, slot);
                slot += 2;
            } else {
                mv.visitVarInsn(ALOAD, slot++);
            }
            mv.visitFieldInsn(PUTFIELD, className, field.name(), typeToDescriptor(field.type()));
        }
        
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        
        var eqMv = cw.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        eqMv.visitCode();
        eqMv.visitVarInsn(ALOAD, 0);
        eqMv.visitVarInsn(ALOAD, 1);
        var eqLabel = new Label();
        var endLabel = new Label();
        eqMv.visitJumpInsn(IF_ACMPEQ, eqLabel);
        eqMv.visitLdcInsn(0);
        eqMv.visitJumpInsn(GOTO, endLabel);
        eqMv.visitLabel(eqLabel);
        eqMv.visitLdcInsn(1);
        eqMv.visitLabel(endLabel);
        eqMv.visitInsn(IRETURN);
        eqMv.visitMaxs(0, 0);
        eqMv.visitEnd();
        
        var hcMv = cw.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);
        hcMv.visitCode();
        hcMv.visitVarInsn(ALOAD, 0);
        hcMv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "hashCode", "()I", false);
        hcMv.visitInsn(IRETURN);
        hcMv.visitMaxs(0, 0);
        hcMv.visitEnd();
        
        cw.visitEnd();
        return cw.toByteArray();
    }
    
    private byte[] generateEnum(EnumDecl enumDecl) {
        var className = currentModule.replace('.', '/') + "/" + enumDecl.name();
        
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_ABSTRACT, className, null, "java/lang/Object", null);
        
        var mv = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        
        cw.visitEnd();
        return cw.toByteArray();
    }
    
    private String buildConstructorDescriptor(List<FieldDecl> fields) {
        var sb = new StringBuilder("(");
        for (var field : fields) {
            sb.append(typeToDescriptor(field.type()));
        }
        sb.append(")V");
        return sb.toString();
    }
    
    private String getTypeName(TypeExpr type) {
        return switch (type) {
            case SimpleTypeExpr simple -> simple.name();
            case GenericTypeExpr generic -> generic.name();
            case UnitTypeExpr _ -> "Unit";
        };
    }
    
    private class CodeGenContext {
        final MethodVisitor mv;
        final FnDecl fn;
        final Map<String, Integer> locals = new HashMap<>();
        final Map<String, String> localTypes = new HashMap<>();
        int nextSlot;
        boolean hasReturn = false;
        
        CodeGenContext(MethodVisitor mv, FnDecl fn) {
            this.mv = mv;
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
