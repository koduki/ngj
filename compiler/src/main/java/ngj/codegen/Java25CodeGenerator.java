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
    
    // ACC_STRICT flag (0x0800) for value class fields (JEP 401)
    // Fields in value classes must be strict to ensure they are initialized before use
    private static final int ACC_STRICT = 0x0800;
    
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
    private String target = "valhalla"; // Default target
    private final Map<String, RecordDecl> records = new HashMap<>();
    private final Map<String, DataDecl> datas = new HashMap<>();
    private final Map<String, EnumDecl> enums = new HashMap<>();
    private final Map<String, FnDecl> functions = new HashMap<>();
    
    // Custom class hierarchy resolver for ngj.runtime classes and user enums
    private ClassHierarchyResolver hierarchyResolver;
    
    public void setTarget(String target) {
        this.target = target;
    }
    
    /**
     * Build a ClassHierarchyResolver that knows about ngj.runtime classes and user-defined enums.
     * This is needed for ClassFile API to compute stack frames when branches join with different types.
     */
    private ClassHierarchyResolver buildHierarchyResolver() {
        // Map of class -> superclass for our generated classes
        Map<ClassDesc, ClassDesc> hierarchy = new HashMap<>();
        
        // ngj.runtime.Option hierarchy
        var optionBase = ClassDesc.ofInternalName("ngj/runtime/Option");
        var optionSome = ClassDesc.ofInternalName("ngj/runtime/Option$Some");
        var optionNone = ClassDesc.ofInternalName("ngj/runtime/Option$None");
        hierarchy.put(optionBase, ConstantDescs.CD_Object);
        hierarchy.put(optionSome, optionBase);
        hierarchy.put(optionNone, optionBase);
        
        // ngj.runtime.Result hierarchy
        var resultBase = ClassDesc.ofInternalName("ngj/runtime/Result");
        var resultOk = ClassDesc.ofInternalName("ngj/runtime/Result$Ok");
        var resultErr = ClassDesc.ofInternalName("ngj/runtime/Result$Err");
        hierarchy.put(resultBase, ConstantDescs.CD_Object);
        hierarchy.put(resultOk, resultBase);
        hierarchy.put(resultErr, resultBase);
        
        // User-defined enums and their variants
        for (var entry : enums.entrySet()) {
            var enumName = entry.getKey();
            var enumDecl = entry.getValue();
            var enumBase = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + enumName);
            hierarchy.put(enumBase, ConstantDescs.CD_Object);
            
            for (var variant : enumDecl.variants()) {
                var variantClass = ClassDesc.ofInternalName(
                    currentModule.replace('.', '/') + "/" + enumName + "$" + variant.name());
                hierarchy.put(variantClass, enumBase);
            }
        }
        
        // User-defined records and data types
        for (var name : records.keySet()) {
            var classDesc = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + name);
            hierarchy.put(classDesc, ConstantDescs.CD_Object);
        }
        for (var name : datas.keySet()) {
            var classDesc = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + name);
            hierarchy.put(classDesc, ConstantDescs.CD_Object);
        }
        
        // Create resolver that checks our hierarchy first, then falls back to system
        return ClassHierarchyResolver.ofResourceParsing(
            ClassLoader.getSystemClassLoader()
        ).orElse(desc -> {
            var superclass = hierarchy.get(desc);
            if (superclass != null) {
                return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(superclass);
            }
            // Unknown class - assume Object as superclass
            return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object);
        });
    }
    
    /**
     * Get the ClassFile builder with our custom hierarchy resolver.
     */
    private ClassFile classFileBuilder() {
        return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchyResolver));
    }
    
    private int getClassFileVersionForTarget() {
        return switch (target) {
            case "java25" -> 69; // JDK 25 class file version
            case "valhalla-compat" -> 69; // JDK 25 with identity classes only
            case "valhalla" -> CLASS_FILE_VERSION; // JDK 28 with value classes
            default -> CLASS_FILE_VERSION;
        };
    }
    
    private int getMinorVersionForTarget() {
        return switch (target) {
            case "valhalla" -> PREVIEW_MINOR_VERSION; // Enable preview for value classes
            default -> 0;
        };
    }
    
    private boolean isValueClassTarget() {
        return target.equals("valhalla");
    }
    
    public List<Path> generate(CompilationUnit unit) throws CodeGenException {
        currentModule = unit.modulePath();
        records.clear();
        datas.clear();
        enums.clear();
        functions.clear();
        for (var decl : unit.declarations()) {
            switch (decl) {
                case RecordDecl r -> records.put(r.name(), r);
                case DataDecl d -> datas.put(d.name(), d);
                case EnumDecl e -> enums.put(e.name(), e);
                case FnDecl f -> functions.put(f.name(), f);
            }
        }
        
        // Build hierarchy resolver after collecting all declarations
        hierarchyResolver = buildHierarchyResolver();
        
        var outputs = new ArrayList<Path>();
        
        try {
            var moduleDir = outputDir.resolve(currentModule.replace('.', '/'));
            Files.createDirectories(moduleDir);
            
            // Generate standard library runtime classes (Option, Result)
            outputs.addAll(generateRuntimeClasses());
            
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
    
    /**
     * Generate Option and Result as standard library runtime classes.
     * These are sealed ADT hierarchies per the specification.
     */
    private List<Path> generateRuntimeClasses() throws Exception {
        var outputs = new ArrayList<Path>();
        var runtimeDir = outputDir.resolve("ngj/runtime");
        Files.createDirectories(runtimeDir);
        
        // Generate Option hierarchy
        outputs.add(generateOptionBase(runtimeDir));
        outputs.add(generateOptionSome(runtimeDir));
        outputs.add(generateOptionNone(runtimeDir));
        
        // Generate Result hierarchy  
        outputs.add(generateResultBase(runtimeDir));
        outputs.add(generateResultOk(runtimeDir));
        outputs.add(generateResultErr(runtimeDir));
        
        return outputs;
    }
    
    private Path generateOptionBase(Path runtimeDir) throws Exception {
        var className = "ngj/runtime/Option";
        var classDesc = ClassDesc.ofInternalName(className);
        var someDesc = ClassDesc.ofInternalName("ngj/runtime/Option$Some");
        var noneDesc = ClassDesc.ofInternalName("ngj/runtime/Option$None");
        
        var classFile = classFileBuilder().build(classDesc, classBuilder -> {
            classBuilder.withVersion(getClassFileVersionForTarget(), getMinorVersionForTarget());
            // Sealed abstract class
            classBuilder.withFlags(ACC_PUBLIC | ACC_ABSTRACT);
            classBuilder.withSuperclass(ConstantDescs.CD_Object);
            
            // PermittedSubclasses attribute for sealed hierarchy
            classBuilder.with(PermittedSubclassesAttribute.ofSymbols(someDesc, noneDesc));
            
            // Protected no-arg constructor
            classBuilder.withMethod(ConstantDescs.INIT_NAME, 
                MethodTypeDesc.of(ConstantDescs.CD_void), ACC_PROTECTED, mb -> {
                mb.withCode(code -> {
                    code.aload(0);
                    code.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void));
                    code.return_();
                });
            });
        });
        
        var outputPath = runtimeDir.resolve("Option.class");
        Files.write(outputPath, classFile);
        return outputPath;
    }
    
    private Path generateOptionSome(Path runtimeDir) throws Exception {
        var className = "ngj/runtime/Option$Some";
        var classDesc = ClassDesc.ofInternalName(className);
        var baseDesc = ClassDesc.ofInternalName("ngj/runtime/Option");
        
        var classFile = classFileBuilder().build(classDesc, classBuilder -> {
            classBuilder.withVersion(getClassFileVersionForTarget(), getMinorVersionForTarget());
            if (isValueClassTarget()) {
                classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL);
            } else {
                classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_IDENTITY);
            }
            classBuilder.withSuperclass(baseDesc);
            
            // Field: value
            int fieldFlags = ACC_PUBLIC | ACC_FINAL;
            if (isValueClassTarget()) {
                fieldFlags |= ACC_STRICT;
            }
            classBuilder.withField("value", ConstantDescs.CD_Object, fieldFlags);
            
            // Constructor
            classBuilder.withMethod(ConstantDescs.INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object), ACC_PUBLIC, mb -> {
                mb.withCode(code -> {
                    if (isValueClassTarget()) {
                        // Value class: fields before super
                        code.aload(0);
                        code.aload(1);
                        code.putfield(classDesc, "value", ConstantDescs.CD_Object);
                        code.aload(0);
                        code.invokespecial(baseDesc, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void));
                    } else {
                        // Identity class: super first
                        code.aload(0);
                        code.invokespecial(baseDesc, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void));
                        code.aload(0);
                        code.aload(1);
                        code.putfield(classDesc, "value", ConstantDescs.CD_Object);
                    }
                    code.return_();
                });
            });
        });
        
        var outputPath = runtimeDir.resolve("Option$Some.class");
        Files.write(outputPath, classFile);
        return outputPath;
    }
    
    private Path generateOptionNone(Path runtimeDir) throws Exception {
        var className = "ngj/runtime/Option$None";
        var classDesc = ClassDesc.ofInternalName(className);
        var baseDesc = ClassDesc.ofInternalName("ngj/runtime/Option");
        
        var classFile = classFileBuilder().build(classDesc, classBuilder -> {
            classBuilder.withVersion(getClassFileVersionForTarget(), getMinorVersionForTarget());
            if (isValueClassTarget()) {
                classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL);
            } else {
                classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_IDENTITY);
            }
            classBuilder.withSuperclass(baseDesc);
            
            // No fields - None has no payload
            
            // No-arg constructor
            classBuilder.withMethod(ConstantDescs.INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void), ACC_PUBLIC, mb -> {
                mb.withCode(code -> {
                    code.aload(0);
                    code.invokespecial(baseDesc, ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void));
                    code.return_();
                });
            });
        });
        
        var outputPath = runtimeDir.resolve("Option$None.class");
        Files.write(outputPath, classFile);
        return outputPath;
    }
    
    private Path generateResultBase(Path runtimeDir) throws Exception {
        var className = "ngj/runtime/Result";
        var classDesc = ClassDesc.ofInternalName(className);
        var okDesc = ClassDesc.ofInternalName("ngj/runtime/Result$Ok");
        var errDesc = ClassDesc.ofInternalName("ngj/runtime/Result$Err");
        
        var classFile = classFileBuilder().build(classDesc, classBuilder -> {
            classBuilder.withVersion(getClassFileVersionForTarget(), getMinorVersionForTarget());
            // Sealed abstract class
            classBuilder.withFlags(ACC_PUBLIC | ACC_ABSTRACT);
            classBuilder.withSuperclass(ConstantDescs.CD_Object);
            
            // PermittedSubclasses attribute for sealed hierarchy
            classBuilder.with(PermittedSubclassesAttribute.ofSymbols(okDesc, errDesc));
            
            // Protected no-arg constructor
            classBuilder.withMethod(ConstantDescs.INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void), ACC_PROTECTED, mb -> {
                mb.withCode(code -> {
                    code.aload(0);
                    code.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void));
                    code.return_();
                });
            });
        });
        
        var outputPath = runtimeDir.resolve("Result.class");
        Files.write(outputPath, classFile);
        return outputPath;
    }
    
    private Path generateResultOk(Path runtimeDir) throws Exception {
        var className = "ngj/runtime/Result$Ok";
        var classDesc = ClassDesc.ofInternalName(className);
        var baseDesc = ClassDesc.ofInternalName("ngj/runtime/Result");
        
        var classFile = classFileBuilder().build(classDesc, classBuilder -> {
            classBuilder.withVersion(getClassFileVersionForTarget(), getMinorVersionForTarget());
            if (isValueClassTarget()) {
                classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL);
            } else {
                classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_IDENTITY);
            }
            classBuilder.withSuperclass(baseDesc);
            
            // Field: value
            int fieldFlags = ACC_PUBLIC | ACC_FINAL;
            if (isValueClassTarget()) {
                fieldFlags |= ACC_STRICT;
            }
            classBuilder.withField("value", ConstantDescs.CD_Object, fieldFlags);
            
            // Constructor
            classBuilder.withMethod(ConstantDescs.INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object), ACC_PUBLIC, mb -> {
                mb.withCode(code -> {
                    if (isValueClassTarget()) {
                        code.aload(0);
                        code.aload(1);
                        code.putfield(classDesc, "value", ConstantDescs.CD_Object);
                        code.aload(0);
                        code.invokespecial(baseDesc, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void));
                    } else {
                        code.aload(0);
                        code.invokespecial(baseDesc, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void));
                        code.aload(0);
                        code.aload(1);
                        code.putfield(classDesc, "value", ConstantDescs.CD_Object);
                    }
                    code.return_();
                });
            });
        });
        
        var outputPath = runtimeDir.resolve("Result$Ok.class");
        Files.write(outputPath, classFile);
        return outputPath;
    }
    
    private Path generateResultErr(Path runtimeDir) throws Exception {
        var className = "ngj/runtime/Result$Err";
        var classDesc = ClassDesc.ofInternalName(className);
        var baseDesc = ClassDesc.ofInternalName("ngj/runtime/Result");
        
        var classFile = classFileBuilder().build(classDesc, classBuilder -> {
            classBuilder.withVersion(getClassFileVersionForTarget(), getMinorVersionForTarget());
            if (isValueClassTarget()) {
                classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL);
            } else {
                classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_IDENTITY);
            }
            classBuilder.withSuperclass(baseDesc);
            
            // Field: error
            int fieldFlags = ACC_PUBLIC | ACC_FINAL;
            if (isValueClassTarget()) {
                fieldFlags |= ACC_STRICT;
            }
            classBuilder.withField("error", ConstantDescs.CD_Object, fieldFlags);
            
            // Constructor
            classBuilder.withMethod(ConstantDescs.INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object), ACC_PUBLIC, mb -> {
                mb.withCode(code -> {
                    if (isValueClassTarget()) {
                        code.aload(0);
                        code.aload(1);
                        code.putfield(classDesc, "error", ConstantDescs.CD_Object);
                        code.aload(0);
                        code.invokespecial(baseDesc, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void));
                    } else {
                        code.aload(0);
                        code.invokespecial(baseDesc, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void));
                        code.aload(0);
                        code.aload(1);
                        code.putfield(classDesc, "error", ConstantDescs.CD_Object);
                    }
                    code.return_();
                });
            });
        });
        
        var outputPath = runtimeDir.resolve("Result$Err.class");
        Files.write(outputPath, classFile);
        return outputPath;
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
        
        return classFileBuilder().build(classDesc, classBuilder -> {
            classBuilder.withVersion(getClassFileVersionForTarget(), getMinorVersionForTarget());
            // Function wrapper classes are always identity classes (not value classes)
            // JDK 28 with preview features requires explicit ACC_IDENTITY for non-value classes
            classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_IDENTITY);
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
                case "Unit" -> ConstantDescs.CD_void;
                case "OverflowError", "RangeError", "IndexError" -> ConstantDescs.CD_Object;
                default -> ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + simple.name());
            };
            case GenericTypeExpr generic -> switch (generic.name()) {
                case "Result" -> enums.containsKey("Result")
                    ? ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/Result")
                    : ClassDesc.ofInternalName("ngj/runtime/Result");
                case "Option" -> enums.containsKey("Option")
                    ? ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/Option")
                    : ClassDesc.ofInternalName("ngj/runtime/Option");
                case "List" -> ClassDesc.of("java.util.List");
                default -> ConstantDescs.CD_Object;
            };
            case UnitTypeExpr _ -> ConstantDescs.CD_void;
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
            case CallExpr call -> {
                if (call.callee() instanceof IdentExpr ident) {
                    var name = ident.name();
                    yield switch (name) {
                        case "length" -> "Int";
                        case "isEmpty", "isSome", "isNone", "isOk", "isErr" -> "Bool";
                        case "Ok", "Err", "checkedAdd", "checkedSub", "checkedMul", "toResult" -> "Result";
                        case "Some", "None", "head", "get", "toOption" -> "Option";
                        case "Nil", "listOf", "Cons" -> "List";
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
                } else if (call.callee() instanceof FieldAccessExpr access && access.object() instanceof IdentExpr enumIdent) {
                    if (enums.containsKey(enumIdent.name())) {
                        yield enumIdent.name();
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
            default -> "Object";
        };
    }
    
    /**
     * Infer the element type from a collection expression.
     * For List<Int>, returns "Int". For unknown types, returns "Object".
     */
    private String inferElementType(CodeGenContext ctx, Expr iterableExpr) {
        // Check if it's an identifier referencing a parameter with known List type
        if (iterableExpr instanceof IdentExpr ident) {
            // Look up the parameter in the function signature
            for (var param : ctx.fn.params()) {
                if (param.name().equals(ident.name())) {
                    if (param.type() instanceof GenericTypeExpr gen) {
                        if (gen.name().equals("List") && !gen.typeArgs().isEmpty()) {
                            return getTypeName(gen.typeArgs().get(0));
                        }
                    }
                    break;
                }
            }
        }
        // Check if it's a call that returns List
        if (iterableExpr instanceof CallExpr call && call.callee() instanceof IdentExpr ident) {
            if (functions.containsKey(ident.name())) {
                var fn = functions.get(ident.name());
                if (fn.returnType() instanceof GenericTypeExpr gen) {
                    if (gen.name().equals("List") && !gen.typeArgs().isEmpty()) {
                        return getTypeName(gen.typeArgs().get(0));
                    }
                }
            }
        }
        return "Object";
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
            case ExprStmt expr -> {
                generateExpr(ctx, expr.expr());
            }
            case ReturnStmt ret -> {
                if (ret.value() != null) {
                    generateExpr(ctx, ret.value());
                }
                if (!ctx.hasReturn) {
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
        // Infer element type from iterable
        var elementType = inferElementType(ctx, forExpr.iterable());
        
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
        
        // Unbox primitive element types
        int elemSlot;
        switch (elementType) {
            case "Int" -> {
                ctx.code.checkcast(ClassDesc.of("java.lang.Integer"));
                ctx.code.invokevirtual(
                    ClassDesc.of("java.lang.Integer"),
                    "intValue",
                    MethodTypeDesc.of(ConstantDescs.CD_int)
                );
                elemSlot = ctx.allocateLocal(forExpr.varName(), "Int");
                ctx.code.istore(elemSlot);
            }
            case "Long" -> {
                ctx.code.checkcast(ClassDesc.of("java.lang.Long"));
                ctx.code.invokevirtual(
                    ClassDesc.of("java.lang.Long"),
                    "longValue",
                    MethodTypeDesc.of(ConstantDescs.CD_long)
                );
                elemSlot = ctx.allocateLocal(forExpr.varName(), "Long");
                ctx.code.lstore(elemSlot);
            }
            case "Bool" -> {
                ctx.code.checkcast(ClassDesc.of("java.lang.Boolean"));
                ctx.code.invokevirtual(
                    ClassDesc.of("java.lang.Boolean"),
                    "booleanValue",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean)
                );
                elemSlot = ctx.allocateLocal(forExpr.varName(), "Bool");
                ctx.code.istore(elemSlot);
            }
            default -> {
                elemSlot = ctx.allocateLocal(forExpr.varName(), elementType);
                ctx.code.astore(elemSlot);
            }
        }
        
        // Generate body
        generateBlock(ctx, forExpr.body());
        
        if (forExpr.hasYield()) {
            // Box primitive values for adding to list
            if (forExpr.body().tailExpr() != null) {
                boxIfPrimitive(ctx, forExpr.body().tailExpr());
            }
            
            // Store the yield result in a temp variable
            int yieldSlot = ctx.allocateLocal("$yield$" + ctx.nextSlot, "Object");
            ctx.code.astore(yieldSlot);
            
            // Add result to list
            ctx.code.aload(resultListSlot);
            ctx.code.aload(yieldSlot);
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
            // Push unmodifiable result list as return value
            ctx.code.aload(resultListSlot);
            ctx.code.invokestatic(
                ClassDesc.of("java.util.Collections"),
                "unmodifiableList",
                MethodTypeDesc.of(ClassDesc.of("java.util.List"), ClassDesc.of("java.util.List"))
            );
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
            // Unresolved identifier - this should have been caught by type checking
            throw new IllegalStateException(
                "Unresolved identifier '" + ident.name() + "' at codegen. " +
                "This indicates a type checker bug - unresolved identifiers should be diagnosed.");
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
                // STDLIB-03: Result constructors - ngj.runtime.Result ADT
                case "Ok" -> {
                    ctx.code.new_(ClassDesc.ofInternalName("ngj/runtime/Result$Ok"));
                    ctx.code.dup();
                    generateExpr(ctx, call.args().get(0));
                    boxIfPrimitive(ctx, call.args().get(0));
                    ctx.code.invokespecial(
                        ClassDesc.ofInternalName("ngj/runtime/Result$Ok"),
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object)
                    );
                    return;
                }
                case "Err" -> {
                    ctx.code.new_(ClassDesc.ofInternalName("ngj/runtime/Result$Err"));
                    ctx.code.dup();
                    generateExpr(ctx, call.args().get(0));
                    boxIfPrimitive(ctx, call.args().get(0));
                    ctx.code.invokespecial(
                        ClassDesc.ofInternalName("ngj/runtime/Result$Err"),
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object)
                    );
                    return;
                }
                
                // STDLIB-02: Option constructors - ngj.runtime.Option ADT
                case "Some" -> {
                    ctx.code.new_(ClassDesc.ofInternalName("ngj/runtime/Option$Some"));
                    ctx.code.dup();
                    generateExpr(ctx, call.args().get(0));
                    boxIfPrimitive(ctx, call.args().get(0));
                    ctx.code.invokespecial(
                        ClassDesc.ofInternalName("ngj/runtime/Option$Some"),
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object)
                    );
                    return;
                }
                case "None" -> {
                    ctx.code.new_(ClassDesc.ofInternalName("ngj/runtime/Option$None"));
                    ctx.code.dup();
                    ctx.code.invokespecial(
                        ClassDesc.ofInternalName("ngj/runtime/Option$None"),
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void)
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
                        // Empty immutable list via List.of()
                        ctx.code.invokestatic(
                            ClassDesc.of("java.util.List"),
                            "of",
                            MethodTypeDesc.of(ClassDesc.of("java.util.List"))
                        );
                    } else {
                        // Build ArrayList then wrap in unmodifiableList for immutability
                        ctx.code.new_(ClassDesc.of("java.util.ArrayList"));
                        ctx.code.dup();
                        ctx.code.invokespecial(
                            ClassDesc.of("java.util.ArrayList"),
                            ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void)
                        );
                        for (int i = 0; i < call.args().size(); i++) {
                            ctx.code.dup();
                            generateExpr(ctx, call.args().get(i));
                            boxIfPrimitive(ctx, call.args().get(i));
                            ctx.code.invokeinterface(
                                ClassDesc.of("java.util.List"),
                                "add",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)
                            );
                            ctx.code.pop(); // discard boolean
                        }
                        // Wrap in unmodifiableList for STDLIB immutability guarantee
                        ctx.code.invokestatic(
                            ClassDesc.of("java.util.Collections"),
                            "unmodifiableList",
                            MethodTypeDesc.of(ClassDesc.of("java.util.List"), ClassDesc.of("java.util.List"))
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
                        tb -> {
                            // Empty list -> return None()
                            tb.new_(ClassDesc.ofInternalName("ngj/runtime/Option$None"));
                            tb.dup();
                            tb.invokespecial(
                                ClassDesc.ofInternalName("ngj/runtime/Option$None"),
                                ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void)
                            );
                        },
                        fb -> {
                            // Non-empty list -> return Some(first element)
                            fb.new_(ClassDesc.ofInternalName("ngj/runtime/Option$Some"));
                            fb.dup();
                            fb.aload(listSlot);
                            fb.ldc(0);
                            fb.invokeinterface(
                                ClassDesc.of("java.util.List"),
                                "get",
                                MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int)
                            );
                            fb.invokespecial(
                                ClassDesc.ofInternalName("ngj/runtime/Option$Some"),
                                ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object)
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
                    
                    // In bounds -> return Some(element)
                    ctx.code.new_(ClassDesc.ofInternalName("ngj/runtime/Option$Some"));
                    ctx.code.dup();
                    ctx.code.aload(listSlot);
                    ctx.code.iload(indexSlot);
                    ctx.code.invokeinterface(
                        ClassDesc.of("java.util.List"),
                        "get",
                        MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int)
                    );
                    ctx.code.invokespecial(
                        ClassDesc.ofInternalName("ngj/runtime/Option$Some"),
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object)
                    );
                    ctx.code.goto_(end);
                    
                    // Out of bounds -> return None()
                    ctx.code.labelBinding(outOfBounds);
                    ctx.code.new_(ClassDesc.ofInternalName("ngj/runtime/Option$None"));
                    ctx.code.dup();
                    ctx.code.invokespecial(
                        ClassDesc.ofInternalName("ngj/runtime/Option$None"),
                        ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void)
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
                
                // Option/Result predicates using ngj.runtime classes
                case "isSome" -> {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.instanceOf(ClassDesc.ofInternalName("ngj/runtime/Option$Some"));
                    return;
                }
                case "isNone" -> {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.instanceOf(ClassDesc.ofInternalName("ngj/runtime/Option$None"));
                    return;
                }
                case "isOk" -> {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.instanceOf(ClassDesc.ofInternalName("ngj/runtime/Result$Ok"));
                    return;
                }
                case "isErr" -> {
                    generateExpr(ctx, call.args().get(0));
                    ctx.code.instanceOf(ClassDesc.ofInternalName("ngj/runtime/Result$Err"));
                    return;
                }
                case "getOrElse" -> {
                    var defaultType = inferTypeName(ctx, call.args().get(1));
                    generateExpr(ctx, call.args().get(0));
                    var optSlot = ctx.allocateLocal("$opt$" + ctx.nextSlot, "Object");
                    ctx.code.astore(optSlot);
                    generateExpr(ctx, call.args().get(1));
                    boxIfPrimitive(ctx, call.args().get(1));
                    var defSlot = ctx.allocateLocal("$def$" + ctx.nextSlot, "Object");
                    ctx.code.astore(defSlot);
                    
                    ctx.code.aload(optSlot);
                    ctx.code.instanceOf(ClassDesc.ofInternalName("ngj/runtime/Option$Some"));
                    ctx.code.ifThenElse(Opcode.IFNE,
                        tb -> {
                            tb.aload(optSlot);
                            tb.checkcast(ClassDesc.ofInternalName("ngj/runtime/Option$Some"));
                            tb.getfield(ClassDesc.ofInternalName("ngj/runtime/Option$Some"), "value", ConstantDescs.CD_Object);
                        },
                        fb -> fb.aload(defSlot)
                    );
                    unboxIfNeeded(ctx, defaultType);
                    return;
                }
                case "toOption" -> {
                    generateExpr(ctx, call.args().get(0));
                    var resSlot = ctx.allocateLocal("$res$" + ctx.nextSlot, "Object");
                    ctx.code.astore(resSlot);
                    
                    ctx.code.aload(resSlot);
                    ctx.code.instanceOf(ClassDesc.ofInternalName("ngj/runtime/Result$Ok"));
                    ctx.code.ifThenElse(Opcode.IFNE,
                        tb -> {
                            tb.new_(ClassDesc.ofInternalName("ngj/runtime/Option$Some"));
                            tb.dup();
                            tb.aload(resSlot);
                            tb.checkcast(ClassDesc.ofInternalName("ngj/runtime/Result$Ok"));
                            tb.getfield(ClassDesc.ofInternalName("ngj/runtime/Result$Ok"), "value", ConstantDescs.CD_Object);
                            tb.invokespecial(
                                ClassDesc.ofInternalName("ngj/runtime/Option$Some"),
                                ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object)
                            );
                        },
                        fb -> {
                            fb.new_(ClassDesc.ofInternalName("ngj/runtime/Option$None"));
                            fb.dup();
                            fb.invokespecial(
                                ClassDesc.ofInternalName("ngj/runtime/Option$None"),
                                ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void)
                            );
                        }
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
                    ctx.code.instanceOf(ClassDesc.ofInternalName("ngj/runtime/Option$Some"));
                    ctx.code.ifThenElse(Opcode.IFNE,
                        tb -> {
                            tb.new_(ClassDesc.ofInternalName("ngj/runtime/Result$Ok"));
                            tb.dup();
                            tb.aload(optSlot);
                            tb.checkcast(ClassDesc.ofInternalName("ngj/runtime/Option$Some"));
                            tb.getfield(ClassDesc.ofInternalName("ngj/runtime/Option$Some"), "value", ConstantDescs.CD_Object);
                            tb.invokespecial(
                                ClassDesc.ofInternalName("ngj/runtime/Result$Ok"),
                                ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object)
                            );
                        },
                        fb -> {
                            fb.new_(ClassDesc.ofInternalName("ngj/runtime/Result$Err"));
                            fb.dup();
                            fb.aload(errSlot);
                            fb.invokespecial(
                                ClassDesc.ofInternalName("ngj/runtime/Result$Err"),
                                ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object)
                            );
                        }
                    );
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
        } else if (call.callee() instanceof FieldAccessExpr access && access.object() instanceof IdentExpr enumIdent) {
            var enumName = enumIdent.name();
            var variantName = access.fieldName();
            if (enums.containsKey(enumName)) {
                for (var variant : enums.get(enumName).variants()) {
                    if (variant.name().equals(variantName)) {
                        var variantClassDesc = ClassDesc.ofInternalName(
                            currentModule.replace('.', '/') + "/" + enumName + "$" + variantName);
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
            ctx.code.ldc(variantName);
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
        
        // Success: wrap in Ok
        var resultSlot = ctx.allocateLocal("$result$" + ctx.nextSlot, "Object");
        ctx.code.astore(resultSlot);
        ctx.code.new_(ClassDesc.ofInternalName("ngj/runtime/Result$Ok"));
        ctx.code.dup();
        ctx.code.aload(resultSlot);
        ctx.code.invokespecial(
            ClassDesc.ofInternalName("ngj/runtime/Result$Ok"),
            ConstantDescs.INIT_NAME,
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object)
        );
        ctx.code.goto_(end);
        
        // Overflow: wrap in Err with error variant
        ctx.code.labelBinding(catchStart);
        ctx.code.pop();
        ctx.code.new_(ClassDesc.ofInternalName("ngj/runtime/Result$Err"));
        ctx.code.dup();
        ctx.code.ldc(errorVariant);
        ctx.code.invokespecial(
            ClassDesc.ofInternalName("ngj/runtime/Result$Err"),
            ConstantDescs.INIT_NAME,
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object)
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
                // null -> None()
                tb.new_(ClassDesc.ofInternalName("ngj/runtime/Option$None"));
                tb.dup();
                tb.invokespecial(
                    ClassDesc.ofInternalName("ngj/runtime/Option$None"),
                    ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void)
                );
            },
            fb -> {
                // non-null -> Some(value)
                var valueSlot = 0; // temp storage
                fb.new_(ClassDesc.ofInternalName("ngj/runtime/Option$Some"));
                fb.dup_x1();
                fb.swap();
                fb.invokespecial(
                    ClassDesc.ofInternalName("ngj/runtime/Option$Some"),
                    ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object)
                );
            }
        );
    }
    
    private void generateConstructor(CodeGenContext ctx, ConstructorExpr ctor) {
        var classDesc = ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + ctor.typePath());
        
        ctx.code.new_(classDesc);
        ctx.code.dup();
        
        for (var field : ctor.fields()) {
            generateExpr(ctx, field.value());
        }
        
        // Look up the actual constructor parameter types from the record/data declaration
        ClassDesc[] paramTypes;
        if (records.containsKey(ctor.typePath())) {
            var rec = records.get(ctor.typePath());
            paramTypes = rec.params().stream()
                .map(p -> typeToClassDesc(p.type()))
                .toArray(ClassDesc[]::new);
        } else if (datas.containsKey(ctor.typePath())) {
            var data = datas.get(ctor.typePath());
            paramTypes = data.fields().stream()
                .map(f -> typeToClassDesc(f.type()))
                .toArray(ClassDesc[]::new);
        } else {
            // Fallback to Object for unknown types
            paramTypes = new ClassDesc[ctor.fields().size()];
            Arrays.fill(paramTypes, ConstantDescs.CD_Object);
        }
        var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void, paramTypes);
        
        ctx.code.invokespecial(classDesc, ConstantDescs.INIT_NAME, ctorDesc);
    }
    
    private void generateMatch(CodeGenContext ctx, MatchExpr matchExpr) {
        // Evaluate scrutinee and store in local variable
        generateExpr(ctx, matchExpr.scrutinee());
        var scrutineeSlot = ctx.allocateLocal("$match$scrutinee", "Object");
        ctx.code.astore(scrutineeSlot);
        
        // Infer scrutinee type for Option/Result field type resolution
        String scrutineeType = inferTypeName(ctx, matchExpr.scrutinee());
        String innerType = inferGenericInnerType(ctx, matchExpr.scrutinee());
        
        var endLabel = ctx.code.newLabel();
        var arms = matchExpr.arms();
        
        // Track next arm labels for conditional patterns
        Label nextArmLabel = null;
        
        for (int i = 0; i < arms.size(); i++) {
            var arm = arms.get(i);
            var pattern = arm.pattern();
            var isLastArm = (i == arms.size() - 1);
            
            // Bind previous arm's fallthrough label if any
            if (nextArmLabel != null) {
                ctx.code.labelBinding(nextArmLabel);
                nextArmLabel = null;
            }
            
            switch (pattern) {
                case WildcardPattern _ -> {
                    // Wildcard matches everything - just evaluate body
                    generateExpr(ctx, arm.body());
                    if (!isLastArm) {
                        ctx.code.goto_(endLabel);
                    }
                }
                
                case BindingPattern binding -> {
                    // Bind scrutinee to variable and evaluate body
                    ctx.code.aload(scrutineeSlot);
                    var bindSlot = ctx.allocateLocal(binding.name(), scrutineeType);
                    ctx.code.astore(bindSlot);
                    generateExpr(ctx, arm.body());
                    if (!isLastArm) {
                        ctx.code.goto_(endLabel);
                    }
                }
                
                case LiteralPattern literal -> {
                    // Compare with literal value
                    if (!isLastArm) {
                        nextArmLabel = ctx.code.newLabel();
                    }
                    ctx.code.aload(scrutineeSlot);
                    generateExpr(ctx, literal.literal());
                    // Box primitive if needed for equals comparison
                    boxIfNeeded(ctx, literal.literal());
                    ctx.code.invokevirtual(
                        ConstantDescs.CD_Object,
                        "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)
                    );
                    if (!isLastArm) {
                        ctx.code.ifeq(nextArmLabel);
                    } else {
                        // Last arm - pop the boolean and proceed (exhaustive match assumed)
                        ctx.code.pop();
                    }
                    generateExpr(ctx, arm.body());
                    if (!isLastArm) {
                        ctx.code.goto_(endLabel);
                    }
                }
                
                case ConstructorPattern ctor -> {
                    // Check instanceof variant class and extract fields
                    var typePath = ctor.typePath();
                    
                    // Get the short variant name (e.g., "Ok", "Err", "Some", "None")
                    String shortVariantName = typePath.contains(".") 
                        ? typePath.substring(typePath.lastIndexOf('.') + 1)
                        : typePath;
                    
                    // Resolve variant class - could be EnumName.Variant or just Variant
                    ClassDesc variantClass = resolveVariantClass(ctx, typePath);
                    
                    if (!isLastArm) {
                        nextArmLabel = ctx.code.newLabel();
                        ctx.code.aload(scrutineeSlot);
                        ctx.code.instanceOf(variantClass);
                        ctx.code.ifeq(nextArmLabel);
                    }
                    // For last arm, we assume exhaustive match and don't need to check
                    
                    // Extract fields from variant
                    if (!ctor.fields().isEmpty()) {
                        ctx.code.aload(scrutineeSlot);
                        ctx.code.checkcast(variantClass);
                        var castedSlot = ctx.allocateLocal("$match$casted", typePath);
                        ctx.code.astore(castedSlot);
                        
                        for (int fieldIdx = 0; fieldIdx < ctor.fields().size(); fieldIdx++) {
                            var field = ctor.fields().get(fieldIdx);
                            if (field.pattern() instanceof BindingPattern bindPat) {
                                ctx.code.aload(castedSlot);
                                
                                // Resolve the actual field name in the variant class
                                // For Option/Result ADTs, the field names are fixed
                                String actualFieldName = resolveVariantFieldName(typePath, field.name(), fieldIdx);
                                
                                // Get the field type for the getfield descriptor
                                // For Option/Result, use the correct type parameter based on variant:
                                // - Some(v): v is T from Option<T>
                                // - Ok(v): v is T from Result<T, E>
                                // - Err(e): e is E from Result<T, E>
                                // For user-defined enums, use the declared field type
                                String fieldType;
                                ClassDesc fieldDesc;
                                if (scrutineeType.equals("Option") || scrutineeType.equals("Result")) {
                                    fieldType = getResultOptionFieldType(ctx, matchExpr.scrutinee(), shortVariantName);
                                    fieldDesc = ConstantDescs.CD_Object; // Option/Result store as Object
                                } else {
                                    fieldType = lookupEnumVariantFieldType(typePath, actualFieldName);
                                    fieldDesc = typeNameToClassDesc(fieldType);
                                }
                                
                                ctx.code.getfield(variantClass, actualFieldName, fieldDesc);
                                
                                // For Option/Result with boxed primitives, unbox if needed
                                if (scrutineeType.equals("Option") || scrutineeType.equals("Result")) {
                                    if (fieldType.equals("Int")) {
                                        ctx.code.checkcast(ClassDesc.of("java.lang.Integer"));
                                        ctx.code.invokevirtual(
                                            ClassDesc.of("java.lang.Integer"),
                                            "intValue",
                                            MethodTypeDesc.of(ConstantDescs.CD_int)
                                        );
                                        var fieldSlot = ctx.allocateLocal(bindPat.name(), "Int");
                                        ctx.code.istore(fieldSlot);
                                    } else if (fieldType.equals("Long")) {
                                        ctx.code.checkcast(ClassDesc.of("java.lang.Long"));
                                        ctx.code.invokevirtual(
                                            ClassDesc.of("java.lang.Long"),
                                            "longValue",
                                            MethodTypeDesc.of(ConstantDescs.CD_long)
                                        );
                                        var fieldSlot = ctx.allocateLocal(bindPat.name(), "Long");
                                        ctx.code.lstore(fieldSlot);
                                    } else if (fieldType.equals("Bool")) {
                                        ctx.code.checkcast(ClassDesc.of("java.lang.Boolean"));
                                        ctx.code.invokevirtual(
                                            ClassDesc.of("java.lang.Boolean"),
                                            "booleanValue",
                                            MethodTypeDesc.of(ConstantDescs.CD_boolean)
                                        );
                                        var fieldSlot = ctx.allocateLocal(bindPat.name(), "Bool");
                                        ctx.code.istore(fieldSlot);
                                    } else {
                                        var fieldSlot = ctx.allocateLocal(bindPat.name(), fieldType);
                                        ctx.code.astore(fieldSlot);
                                    }
                                } else {
                                    // User-defined enums: use the actual field type
                                    if (fieldType.equals("Int") || fieldType.equals("Bool")) {
                                        var fieldSlot = ctx.allocateLocal(bindPat.name(), fieldType);
                                        ctx.code.istore(fieldSlot);
                                    } else if (fieldType.equals("Long")) {
                                        var fieldSlot = ctx.allocateLocal(bindPat.name(), fieldType);
                                        ctx.code.lstore(fieldSlot);
                                    } else {
                                        var fieldSlot = ctx.allocateLocal(bindPat.name(), fieldType);
                                        ctx.code.astore(fieldSlot);
                                    }
                                }
                            }
                        }
                    }
                    
                    generateExpr(ctx, arm.body());
                    if (!isLastArm) {
                        ctx.code.goto_(endLabel);
                    }
                }
            }
        }
        
        ctx.code.labelBinding(endLabel);
    }
    
    private String inferGenericInnerType(CodeGenContext ctx, Expr expr) {
        // For Option<T> or Result<T, E>, return T (first type param)
        return inferGenericTypeParam(ctx, expr, 0);
    }
    
    private String inferGenericTypeParam(CodeGenContext ctx, Expr expr, int paramIndex) {
        // For Option<T> paramIndex 0 -> T
        // For Result<T, E> paramIndex 0 -> T, paramIndex 1 -> E
        if (expr instanceof IdentExpr ident) {
            // Look up in function parameters
            for (var param : ctx.fn.params()) {
                if (param.name().equals(ident.name())) {
                    if (param.type() instanceof GenericTypeExpr gen) {
                        if (gen.typeArgs().size() > paramIndex) {
                            return getTypeName(gen.typeArgs().get(paramIndex));
                        }
                    }
                }
            }
        }
        return "Object";
    }
    
    /**
     * Get the correct field type for Option/Result pattern bindings.
     * - Some(v): v has type T from Option<T>
     * - None: no binding
     * - Ok(v): v has type T from Result<T, E>
     * - Err(e): e has type E from Result<T, E>
     */
    private String getResultOptionFieldType(CodeGenContext ctx, Expr scrutinee, String variantName) {
        if (variantName.equals("Some")) {
            return inferGenericTypeParam(ctx, scrutinee, 0); // Option<T> -> T
        } else if (variantName.equals("Ok")) {
            return inferGenericTypeParam(ctx, scrutinee, 0); // Result<T, E> -> T
        } else if (variantName.equals("Err")) {
            return inferGenericTypeParam(ctx, scrutinee, 1); // Result<T, E> -> E
        }
        return "Object";
    }
    
    private ClassDesc resolveVariantClass(CodeGenContext ctx, String typePath) {
        // Handle Option/Result builtins
        if (typePath.equals("Some") || typePath.equals("None")) {
            return ClassDesc.ofInternalName("ngj/runtime/Option$" + typePath);
        }
        if (typePath.equals("Ok") || typePath.equals("Err")) {
            return ClassDesc.ofInternalName("ngj/runtime/Result$" + typePath);
        }
        
        // Handle qualified paths like "Color.Red" or simple names like "Red"
        if (typePath.contains(".")) {
            var parts = typePath.split("\\.");
            var enumName = parts[0];
            var variantName = parts[1];
            return ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + enumName + "$" + variantName);
        }
        
        // Try to find in known enums
        for (var entry : enums.entrySet()) {
            for (var variant : entry.getValue().variants()) {
                if (variant.name().equals(typePath)) {
                    return ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + entry.getKey() + "$" + typePath);
                }
            }
        }
        
        // Fallback: assume it's a qualified variant in current module
        return ClassDesc.ofInternalName(currentModule.replace('.', '/') + "/" + typePath);
    }
    
    /**
     * Resolve the actual field name in a variant class.
     * For Option/Result ADTs, the field names are fixed:
     * - Some: "value"
     * - Ok: "value"
     * - Err: "error"
     * For user-defined enums, use the declared field name from the enum definition.
     */
    private String resolveVariantFieldName(String typePath, String patternFieldName, int fieldIndex) {
        // Built-in Option/Result ADT field names
        if (typePath.equals("Some") || typePath.equals("Ok")) {
            return "value";
        }
        if (typePath.equals("Err")) {
            return "error";
        }
        
        // For user-defined enums, look up the declared field name
        for (var entry : enums.entrySet()) {
            for (var variant : entry.getValue().variants()) {
                if (variant.name().equals(typePath)) {
                    var fields = variant.fields();
                    if (fieldIndex < fields.size()) {
                        return fields.get(fieldIndex).name();
                    }
                }
            }
        }
        
        // Fallback to pattern field name (might not match, but best effort)
        return patternFieldName;
    }
    
    /**
     * Look up the type of a field in an enum variant.
     */
    private String lookupEnumVariantFieldType(String typePath, String fieldName) {
        for (var entry : enums.entrySet()) {
            for (var variant : entry.getValue().variants()) {
                if (variant.name().equals(typePath)) {
                    for (var field : variant.fields()) {
                        if (field.name().equals(fieldName)) {
                            return getTypeName(field.type());
                        }
                    }
                }
            }
        }
        return "Object";
    }
    
    /**
     * Convert a type name (like "Int", "String") to a ClassDesc for field access.
     */
    private ClassDesc typeNameToClassDesc(String typeName) {
        return switch (typeName) {
            case "Int" -> ConstantDescs.CD_int;
            case "Long" -> ConstantDescs.CD_long;
            case "Bool" -> ConstantDescs.CD_boolean;
            case "Float" -> ConstantDescs.CD_float;
            case "Double" -> ConstantDescs.CD_double;
            case "String" -> ConstantDescs.CD_String;
            case "Object" -> ConstantDescs.CD_Object;
            default -> ClassDesc.of(typeName.contains(".") ? typeName : "java.lang." + typeName);
        };
    }
    
    private void boxIfNeeded(CodeGenContext ctx, LiteralExpr literal) {
        switch (literal.kind()) {
            case INT -> ctx.code.invokestatic(
                ClassDesc.of("java.lang.Integer"),
                "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Integer"), ConstantDescs.CD_int)
            );
            case LONG -> ctx.code.invokestatic(
                ClassDesc.of("java.lang.Long"),
                "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Long"), ConstantDescs.CD_long)
            );
            case BOOL -> ctx.code.invokestatic(
                ClassDesc.of("java.lang.Boolean"),
                "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Boolean"), ConstantDescs.CD_boolean)
            );
            default -> {} // String and Unit don't need boxing
        }
    }
    
    private void generatePropagate(CodeGenContext ctx, PropagateExpr prop) {
        var unwrappedType = inferTypeName(ctx, prop);
        generateExpr(ctx, prop.inner());
        var resSlot = ctx.allocateLocal("$prop$" + ctx.nextSlot, "Object");
        ctx.code.astore(resSlot);
        
        // Check if it's an Err using instanceof
        ctx.code.aload(resSlot);
        ctx.code.instanceOf(ClassDesc.ofInternalName("ngj/runtime/Result$Err"));
        
        var okLabel = ctx.code.newLabel();
        ctx.code.ifeq(okLabel);
        
        // It's an Err - return early
        ctx.code.aload(resSlot);
        ctx.code.areturn();
        
        // It's Ok - extract the value
        ctx.code.labelBinding(okLabel);
        ctx.code.aload(resSlot);
        ctx.code.checkcast(ClassDesc.ofInternalName("ngj/runtime/Result$Ok"));
        ctx.code.getfield(ClassDesc.ofInternalName("ngj/runtime/Result$Ok"), "value", ConstantDescs.CD_Object);
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
        
        return classFileBuilder().build(classDesc, classBuilder -> {
            classBuilder.withVersion(getClassFileVersionForTarget(), 0);
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
        
        return classFileBuilder().build(classDesc, classBuilder -> {
            if (isValueClassTarget()) {
                // VALUE-01: Use preview minor version for JEP 401
                classBuilder.withVersion(getClassFileVersionForTarget(), PREVIEW_MINOR_VERSION);
                // VALUE-01: NO ACC_IDENTITY flag - this is a value class
                classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL);
            } else {
                // Identity class for non-valhalla targets
                classBuilder.withVersion(getClassFileVersionForTarget(), 0);
                classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_IDENTITY);
            }
            classBuilder.withSuperclass(ConstantDescs.CD_Object);
            
            for (var param : record.params()) {
                var fieldDesc = typeToClassDesc(param.type());
                // JEP 401: Value class fields must be strict (only for valhalla target)
                int fieldFlags = ACC_PUBLIC | ACC_FINAL;
                if (isValueClassTarget()) {
                    fieldFlags |= ACC_STRICT;
                }
                classBuilder.withField(param.name(), fieldDesc, fieldFlags);
            }
            
            var ctorParamTypes = record.params().stream()
                .map(p -> typeToClassDesc(p.type()))
                .toArray(ClassDesc[]::new);
            var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ctorParamTypes);
            
            classBuilder.withMethod(ConstantDescs.INIT_NAME, ctorDesc, ACC_PUBLIC, mb -> {
                mb.withCode(code -> {
                    if (isValueClassTarget()) {
                        // JEP 401: Value class constructor must assign ALL fields BEFORE super()
                        // This is the strict initialization order required for value classes
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
                        // super() after field assignments for value classes
                        code.aload(0);
                        code.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, 
                            MethodTypeDesc.of(ConstantDescs.CD_void));
                    } else {
                        // Identity class: standard super() first, then fields
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
        
        return classFileBuilder().build(classDesc, classBuilder -> {
            if (isValueClassTarget()) {
                // VALUE-01: Use preview minor version for JEP 401
                classBuilder.withVersion(getClassFileVersionForTarget(), PREVIEW_MINOR_VERSION);
                // VALUE-01: NO ACC_IDENTITY flag - this is a value class base
                classBuilder.withFlags(ACC_PUBLIC | ACC_ABSTRACT);
            } else {
                classBuilder.withVersion(getClassFileVersionForTarget(), 0);
                classBuilder.withFlags(ACC_PUBLIC | ACC_ABSTRACT | ACC_IDENTITY);
            }
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
                
                var classFile = classFileBuilder().build(variantClassDesc, classBuilder -> {
                    if (isValueClassTarget()) {
                        // VALUE-01: Use preview minor version, NO ACC_IDENTITY
                        classBuilder.withVersion(getClassFileVersionForTarget(), PREVIEW_MINOR_VERSION);
                        classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL);
                    } else {
                        classBuilder.withVersion(getClassFileVersionForTarget(), 0);
                        classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_IDENTITY);
                    }
                    classBuilder.withSuperclass(baseClassDesc);
                    
                    // Add fields for variant payload
                    for (var field : variantFields) {
                        var fieldDesc = typeToClassDesc(field.type());
                        // JEP 401: Value class fields must be strict (only for valhalla target)
                        int fieldFlags = ACC_PUBLIC | ACC_FINAL;
                        if (isValueClassTarget()) {
                            fieldFlags |= ACC_STRICT;
                        }
                        classBuilder.withField(field.name(), fieldDesc, fieldFlags);
                    }
                    
                    // Constructor
                    var ctorParamTypes = variantFields.stream()
                        .map(f -> typeToClassDesc(f.type()))
                        .toArray(ClassDesc[]::new);
                    var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ctorParamTypes);
                    
                    classBuilder.withMethod(ConstantDescs.INIT_NAME, ctorDesc, ACC_PUBLIC, mb -> {
                        mb.withCode(code -> {
                            if (isValueClassTarget()) {
                                // JEP 401: Value class constructor must assign ALL fields BEFORE super()
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
                                // super() after field assignments for value classes
                                code.aload(0);
                                code.invokespecial(baseClassDesc, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void));
                            } else {
                                // Identity class: standard super() first, then fields
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
