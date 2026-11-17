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
    
    private String moduleInternalName(String simpleName) {
        if (currentModule == null || currentModule.isEmpty()) {
            return simpleName;
        }
        return currentModule.replace('.', '/') + "/" + simpleName;
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
            var enumBase = ClassDesc.ofInternalName(moduleInternalName(enumName));
            hierarchy.put(enumBase, ConstantDescs.CD_Object);
            
            for (var variant : enumDecl.variants()) {
                var variantClass = ClassDesc.ofInternalName(
                    moduleInternalName(enumName + "$" + variant.name()));
                hierarchy.put(variantClass, enumBase);
            }
        }
        
        // User-defined records, data types, and function wrapper classes
        for (var name : records.keySet()) {
            hierarchy.put(ClassDesc.ofInternalName(moduleInternalName(name)), ConstantDescs.CD_Object);
        }
        for (var name : datas.keySet()) {
            hierarchy.put(ClassDesc.ofInternalName(moduleInternalName(name)), ConstantDescs.CD_Object);
        }
        for (var name : functions.keySet()) {
            hierarchy.put(ClassDesc.ofInternalName(moduleInternalName(name + "$Fn")), ConstantDescs.CD_Object);
        }
        
        // Check generated hierarchy first, then fall back to JDK/system classes
        ClassHierarchyResolver customResolver = desc -> {
            var superclass = hierarchy.get(desc);
            if (superclass != null) {
                return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(superclass);
            }
            return null;
        };
        return customResolver
            .orElse(ClassHierarchyResolver.ofResourceParsing(ClassLoader.getSystemClassLoader()))
            .orElse(_ -> ClassHierarchyResolver.ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object));
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
            classBuilder.withFlags(isValueClassTarget()
                ? (ACC_PUBLIC | ACC_ABSTRACT)
                : (ACC_PUBLIC | ACC_ABSTRACT | ACC_IDENTITY));
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
            classBuilder.withFlags(isValueClassTarget()
                ? (ACC_PUBLIC | ACC_ABSTRACT)
                : (ACC_PUBLIC | ACC_ABSTRACT | ACC_IDENTITY));
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
        var className = moduleInternalName(fn.name() + "$Fn");
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
        if (type == null) return ConstantDescs.CD_Object;
        return switch (type) {
            case SimpleTypeExpr simple -> switch (simple.name()) {
                case "Int" -> ConstantDescs.CD_int;
                case "Long" -> ConstantDescs.CD_long;
                case "Bool" -> ConstantDescs.CD_boolean;
                case "String" -> ConstantDescs.CD_String;
                case "Unit" -> ConstantDescs.CD_void;
                case "OverflowError", "RangeError", "IndexError", "Object", "_" -> ConstantDescs.CD_Object;
                case "List" -> ClassDesc.of("java.util.List");
                case "Option" -> enums.containsKey("Option")
                    ? ClassDesc.ofInternalName(moduleInternalName("Option"))
                    : ClassDesc.ofInternalName("ngj/runtime/Option");
                case "Result" -> enums.containsKey("Result")
                    ? ClassDesc.ofInternalName(moduleInternalName("Result"))
                    : ClassDesc.ofInternalName("ngj/runtime/Result");
                default -> ClassDesc.ofInternalName(moduleInternalName(simple.name()));
            };
            case GenericTypeExpr generic -> switch (generic.name()) {
                case "Result" -> enums.containsKey("Result")
                    ? ClassDesc.ofInternalName(moduleInternalName("Result"))
                    : ClassDesc.ofInternalName("ngj/runtime/Result");
                case "Option" -> enums.containsKey("Option")
                    ? ClassDesc.ofInternalName(moduleInternalName("Option"))
                    : ClassDesc.ofInternalName("ngj/runtime/Option");
                case "List" -> ClassDesc.of("java.util.List");
                default -> ConstantDescs.CD_Object;
            };
            case UnitTypeExpr _ -> ConstantDescs.CD_void;
        };
    }

    private static final TypeExpr OBJECT_TYPE = new SimpleTypeExpr("Object", null);
    private static final TypeExpr WILDCARD_TYPE = new SimpleTypeExpr("_", null);

    private static TypeExpr simpleType(String name) {
        return new SimpleTypeExpr(name, null);
    }

    private static TypeExpr unitType() {
        return new UnitTypeExpr(null);
    }

    private static TypeExpr genericType(String name, TypeExpr... args) {
        return new GenericTypeExpr(name, List.of(args), null);
    }

    private boolean isUnknownType(TypeExpr type) {
        if (type == null) return true;
        var name = getTypeName(type);
        return name.equals("_") || name.equals("Object");
    }

    private TypeExpr mergeTypeExpr(TypeExpr a, TypeExpr b) {
        if (a == null) return b != null ? b : OBJECT_TYPE;
        if (b == null) return a;

        if (isUnknownType(a) && !isUnknownType(b)) return b;
        if (isUnknownType(b) && !isUnknownType(a)) return a;

        if (a instanceof GenericTypeExpr ga && b instanceof GenericTypeExpr gb && ga.name().equals(gb.name())) {
            int maxArgs = Math.max(ga.typeArgs().size(), gb.typeArgs().size());
            if (maxArgs == 0) return ga;
            var mergedArgs = new ArrayList<TypeExpr>(maxArgs);
            for (int i = 0; i < maxArgs; i++) {
                var argA = i < ga.typeArgs().size() ? ga.typeArgs().get(i) : WILDCARD_TYPE;
                var argB = i < gb.typeArgs().size() ? gb.typeArgs().get(i) : WILDCARD_TYPE;
                mergedArgs.add(mergeTypeExpr(argA, argB));
            }
            return new GenericTypeExpr(ga.name(), mergedArgs, null);
        }

        if (a instanceof GenericTypeExpr && !(b instanceof GenericTypeExpr) && getTypeName(a).equals(getTypeName(b))) {
            return a;
        }
        if (b instanceof GenericTypeExpr && !(a instanceof GenericTypeExpr) && getTypeName(a).equals(getTypeName(b))) {
            return b;
        }

        return a;
    }

    private boolean blockDiverges(BlockExpr block) {
        if (block.tailExpr() != null) {
            return exprDiverges(block.tailExpr());
        }
        for (var stmt : block.statements()) {
            if (stmt instanceof ReturnStmt) {
                return true;
            }
            if (stmt instanceof ExprStmt es && exprDiverges(es.expr())) {
                return true;
            }
        }
        return false;
    }

    private boolean exprDiverges(Expr expr) {
        return switch (expr) {
            case BlockExpr b -> blockDiverges(b);
            case IfExpr i -> i.elseBranch() != null && blockDiverges(i.thenBranch()) && exprDiverges(i.elseBranch());
            case MatchExpr m -> !m.arms().isEmpty() && m.arms().stream().allMatch(a -> exprDiverges(a.body()));
            case ParenExpr p -> exprDiverges(p.inner());
            default -> false;
        };
    }

    private void bindPatternTypeExprs(Map<String, TypeExpr> env, Pattern pattern, TypeExpr scrutineeType) {
        if (pattern == null) return;
        switch (pattern) {
            case WildcardPattern _, LiteralPattern _ -> {}
            case BindingPattern binding -> env.put(binding.name(), scrutineeType != null ? scrutineeType : OBJECT_TYPE);
            case ConstructorPattern ctor -> {
                var typePath = ctor.typePath();
                String shortVariantName = typePath.contains(".")
                    ? typePath.substring(typePath.lastIndexOf('.') + 1)
                    : typePath;
                boolean isRuntimeOption = !enums.containsKey("Option")
                    && (shortVariantName.equals("Some") || shortVariantName.equals("None"));
                boolean isRuntimeResult = !enums.containsKey("Result")
                    && (shortVariantName.equals("Ok") || shortVariantName.equals("Err"));
                boolean isRuntimeAdt = isRuntimeOption || isRuntimeResult;

                for (int i = 0; i < ctor.fields().size(); i++) {
                    var field = ctor.fields().get(i);
                    TypeExpr fieldType;
                    if (isRuntimeAdt) {
                        fieldType = getResultOptionFieldTypeExpr(scrutineeType, shortVariantName);
                    } else {
                        var actualFieldName = resolveVariantFieldName(typePath, field.name(), i);
                        fieldType = lookupEnumVariantFieldTypeExpr(typePath, actualFieldName);
                    }
                    if (field.pattern() != null) {
                        bindPatternTypeExprs(env, field.pattern(), fieldType);
                    } else {
                        env.put(field.name(), fieldType);
                    }
                }
            }
        }
    }

    private TypeExpr inferTypeExpr(CodeGenContext ctx, Expr expr) {
        return inferTypeExpr(ctx.localTypeExprs, expr);
    }

    private TypeExpr inferTypeExpr(Map<String, TypeExpr> env, Expr expr) {
        if (expr == null) return unitType();
        return switch (expr) {
            case LiteralExpr lit -> switch (lit.kind()) {
                case INT -> simpleType("Int");
                case LONG -> simpleType("Long");
                case BOOL -> simpleType("Bool");
                case STRING -> simpleType("String");
                case UNIT -> unitType();
            };
            case IdentExpr ident -> env.getOrDefault(ident.name(), OBJECT_TYPE);
            case BinaryExpr bin -> switch (bin.op()) {
                case ADD, SUB, MUL, DIV, MOD -> inferTypeExpr(env, bin.left());
                case EQ, NE, LT, LE, GT, GE, AND, OR -> simpleType("Bool");
            };
            case UnaryExpr un -> switch (un.op()) {
                case NEG -> inferTypeExpr(env, un.operand());
                case NOT -> simpleType("Bool");
            };
            case ParenExpr paren -> inferTypeExpr(env, paren.inner());
            case BlockExpr block -> {
                if (block.tailExpr() == null) {
                    yield unitType();
                }
                Map<String, TypeExpr> blockEnv = env;
                if (!block.statements().isEmpty()) {
                    var mutableEnv = new HashMap<>(env);
                    for (var stmt : block.statements()) {
                        switch (stmt) {
                            case ValStmt val -> {
                                var initType = inferTypeExpr(mutableEnv, val.initializer());
                                var varType = val.explicitType() != null
                                    ? mergeTypeExpr(val.explicitType(), initType)
                                    : initType;
                                mutableEnv.put(val.name(), varType);
                            }
                            case VarStmt varStmt -> {
                                var initType = inferTypeExpr(mutableEnv, varStmt.initializer());
                                var varType = varStmt.explicitType() != null
                                    ? mergeTypeExpr(varStmt.explicitType(), initType)
                                    : initType;
                                mutableEnv.put(varStmt.name(), varType);
                            }
                            case LetStmt letStmt -> {
                                var initType = inferTypeExpr(mutableEnv, letStmt.initializer());
                                var varType = letStmt.explicitType() != null
                                    ? mergeTypeExpr(letStmt.explicitType(), initType)
                                    : initType;
                                mutableEnv.put(letStmt.name(), varType);
                            }
                            default -> {}
                        }
                    }
                    blockEnv = mutableEnv;
                }
                yield inferTypeExpr(blockEnv, block.tailExpr());
            }
            case IfExpr ifExpr -> {
                if (ifExpr.elseBranch() == null) {
                    yield unitType();
                }
                TypeExpr thenType = blockDiverges(ifExpr.thenBranch()) ? null : inferTypeExpr(env, ifExpr.thenBranch());
                TypeExpr elseType = exprDiverges(ifExpr.elseBranch()) ? null : inferTypeExpr(env, ifExpr.elseBranch());
                if (thenType != null && elseType != null) {
                    yield mergeTypeExpr(thenType, elseType);
                }
                if (thenType != null) {
                    yield thenType;
                }
                if (elseType != null) {
                    yield elseType;
                }
                yield unitType();
            }
            case MatchExpr matchExpr -> {
                var scrutType = inferTypeExpr(env, matchExpr.scrutinee());
                TypeExpr resultType = null;
                for (var arm : matchExpr.arms()) {
                    if (exprDiverges(arm.body())) {
                        continue;
                    }
                    Map<String, TypeExpr> armEnv = env;
                    if (!(arm.pattern() instanceof WildcardPattern) && !(arm.pattern() instanceof LiteralPattern)) {
                        var mutableArmEnv = new HashMap<>(env);
                        bindPatternTypeExprs(mutableArmEnv, arm.pattern(), scrutType);
                        armEnv = mutableArmEnv;
                    }
                    var armType = inferTypeExpr(armEnv, arm.body());
                    resultType = (resultType == null) ? armType : mergeTypeExpr(resultType, armType);
                }
                yield resultType != null ? resultType : unitType();
            }
            case ConstructorExpr ctor -> simpleType(ctor.typePath());
            case CallExpr call -> {
                if (call.callee() instanceof IdentExpr ident) {
                    var name = ident.name();
                    yield switch (name) {
                        case "length" -> simpleType("Int");
                        case "isEmpty", "isSome", "isNone", "isOk", "isErr" -> simpleType("Bool");
                        case "Ok" -> call.args().isEmpty()
                            ? simpleType("Result")
                            : genericType("Result", inferTypeExpr(env, call.args().get(0)), WILDCARD_TYPE);
                        case "Err" -> call.args().isEmpty()
                            ? simpleType("Result")
                            : genericType("Result", WILDCARD_TYPE, inferTypeExpr(env, call.args().get(0)));
                        case "checkedAdd", "checkedSub", "checkedMul" ->
                            genericType("Result", simpleType("Int"), simpleType("OverflowError"));
                        case "toResult" -> {
                            TypeExpr okType = WILDCARD_TYPE;
                            if (!call.args().isEmpty()
                                && inferTypeExpr(env, call.args().get(0)) instanceof GenericTypeExpr gen
                                && gen.name().equals("Option") && !gen.typeArgs().isEmpty()) {
                                okType = gen.typeArgs().get(0);
                            }
                            TypeExpr errType = call.args().size() >= 2
                                ? inferTypeExpr(env, call.args().get(1))
                                : WILDCARD_TYPE;
                            yield genericType("Result", okType, errType);
                        }
                        case "Some" -> call.args().isEmpty()
                            ? simpleType("Option")
                            : genericType("Option", inferTypeExpr(env, call.args().get(0)));
                        case "None" -> genericType("Option", WILDCARD_TYPE);
                        case "head", "get" -> {
                            if (!call.args().isEmpty()
                                && inferTypeExpr(env, call.args().get(0)) instanceof GenericTypeExpr gen
                                && gen.name().equals("List") && !gen.typeArgs().isEmpty()) {
                                yield genericType("Option", gen.typeArgs().get(0));
                            }
                            yield genericType("Option", WILDCARD_TYPE);
                        }
                        case "toOption" -> {
                            if (!call.args().isEmpty()
                                && inferTypeExpr(env, call.args().get(0)) instanceof GenericTypeExpr gen
                                && gen.name().equals("Result") && !gen.typeArgs().isEmpty()) {
                                yield genericType("Option", gen.typeArgs().get(0));
                            }
                            yield genericType("Option", WILDCARD_TYPE);
                        }
                        case "getOrElse" -> {
                            TypeExpr optInner = null;
                            if (!call.args().isEmpty()
                                && inferTypeExpr(env, call.args().get(0)) instanceof GenericTypeExpr gen
                                && gen.name().equals("Option") && !gen.typeArgs().isEmpty()) {
                                optInner = gen.typeArgs().get(0);
                            }
                            TypeExpr defType = call.args().size() >= 2 ? inferTypeExpr(env, call.args().get(1)) : null;
                            yield mergeTypeExpr(optInner, defType);
                        }
                        case "Nil" -> genericType("List", WILDCARD_TYPE);
                        case "listOf" -> {
                            if (call.args().isEmpty()) {
                                yield genericType("List", WILDCARD_TYPE);
                            }
                            TypeExpr elemType = null;
                            for (var arg : call.args()) {
                                var argType = inferTypeExpr(env, arg);
                                elemType = (elemType == null) ? argType : mergeTypeExpr(elemType, argType);
                            }
                            yield genericType("List", elemType != null ? elemType : WILDCARD_TYPE);
                        }
                        case "Cons" -> {
                            TypeExpr elemType = call.args().isEmpty()
                                ? WILDCARD_TYPE
                                : inferTypeExpr(env, call.args().get(0));
                            if (isUnknownType(elemType) && call.args().size() >= 2
                                && inferTypeExpr(env, call.args().get(1)) instanceof GenericTypeExpr tailGen
                                && tailGen.name().equals("List") && !tailGen.typeArgs().isEmpty()) {
                                elemType = tailGen.typeArgs().get(0);
                            }
                            yield genericType("List", elemType);
                        }
                        default -> {
                            if (functions.containsKey(name)) {
                                yield functions.get(name).returnType();
                            }
                            if (records.containsKey(name) || datas.containsKey(name)) {
                                yield simpleType(name);
                            }
                            for (var entry : enums.entrySet()) {
                                for (var v : entry.getValue().variants()) {
                                    if (v.name().equals(name)) {
                                        yield simpleType(entry.getKey());
                                    }
                                }
                            }
                            yield OBJECT_TYPE;
                        }
                    };
                } else if (call.callee() instanceof FieldAccessExpr access && access.object() instanceof IdentExpr enumIdent) {
                    if (enums.containsKey(enumIdent.name())) {
                        yield simpleType(enumIdent.name());
                    }
                }
                yield OBJECT_TYPE;
            }
            case FieldAccessExpr access -> {
                var objType = getTypeName(inferTypeExpr(env, access.object()));
                if (records.containsKey(objType)) {
                    for (var p : records.get(objType).params()) {
                        if (p.name().equals(access.fieldName())) {
                            yield p.type();
                        }
                    }
                } else if (datas.containsKey(objType)) {
                    for (var f : datas.get(objType).fields()) {
                        if (f.name().equals(access.fieldName())) {
                            yield f.type();
                        }
                    }
                }
                yield OBJECT_TYPE;
            }
            case PropagateExpr prop -> {
                var innerType = inferTypeExpr(env, prop.inner());
                if (innerType instanceof GenericTypeExpr gen && gen.name().equals("Result") && !gen.typeArgs().isEmpty()) {
                    yield gen.typeArgs().get(0);
                }
                yield OBJECT_TYPE;
            }
            case ForExpr forExpr -> {
                if (!forExpr.hasYield()) {
                    yield unitType();
                }
                TypeExpr elemType = OBJECT_TYPE;
                if (inferTypeExpr(env, forExpr.iterable()) instanceof GenericTypeExpr gen
                    && gen.name().equals("List") && !gen.typeArgs().isEmpty()) {
                    elemType = gen.typeArgs().get(0);
                }
                var loopEnv = new HashMap<>(env);
                loopEnv.put(forExpr.varName(), elemType);
                var yieldedType = inferTypeExpr(loopEnv, forExpr.body());
                yield genericType("List", yieldedType);
            }
            case WhileExpr _ -> unitType();
            default -> OBJECT_TYPE;
        };
    }

    private String inferTypeName(CodeGenContext ctx, Expr expr) {
        if (expr == null) return "Unit";
        return getTypeName(inferTypeExpr(ctx, expr));
    }

    /**
     * Infer the element TypeExpr from a collection expression.
     * For List<Int>, returns Int. For unknown types, returns Object.
     */
    private TypeExpr inferElementTypeExpr(CodeGenContext ctx, Expr iterableExpr) {
        var iterableType = inferTypeExpr(ctx, iterableExpr);
        if (iterableType instanceof GenericTypeExpr gen && gen.name().equals("List") && !gen.typeArgs().isEmpty()) {
            return gen.typeArgs().get(0);
        }
        return OBJECT_TYPE;
    }

    private void generateBlock(CodeGenContext ctx, BlockExpr block) {
        var savedLocals = new HashMap<>(ctx.locals);
        var savedTypes = new HashMap<>(ctx.localTypes);
        var savedTypeExprs = new HashMap<>(ctx.localTypeExprs);
        try {
            for (var stmt : block.statements()) {
                generateStmt(ctx, stmt);
                if (ctx.hasReturn) {
                    return;
                }
            }

            if (block.tailExpr() != null) {
                generateExpr(ctx, block.tailExpr());
            }
        } finally {
            ctx.locals.clear();
            ctx.locals.putAll(savedLocals);
            ctx.localTypes.clear();
            ctx.localTypes.putAll(savedTypes);
            ctx.localTypeExprs.clear();
            ctx.localTypeExprs.putAll(savedTypeExprs);
        }
    }

    private void generateStmt(CodeGenContext ctx, Statement stmt) {
        switch (stmt) {
            case ValStmt val -> {
                var initType = inferTypeExpr(ctx, val.initializer());
                var varType = val.explicitType() != null
                    ? mergeTypeExpr(val.explicitType(), initType)
                    : initType;
                var typeName = getTypeName(varType);
                generateExpr(ctx, val.initializer());
                int slot = ctx.allocateLocal(val.name(), varType);
                switch (typeName) {
                    case "Long" -> ctx.code.lstore(slot);
                    case "Int", "Bool" -> ctx.code.istore(slot);
                    default -> ctx.code.astore(slot);
                }
            }
            case VarStmt varStmt -> {
                var initType = inferTypeExpr(ctx, varStmt.initializer());
                var varType = varStmt.explicitType() != null
                    ? mergeTypeExpr(varStmt.explicitType(), initType)
                    : initType;
                var typeName = getTypeName(varType);
                generateExpr(ctx, varStmt.initializer());
                int slot = ctx.allocateLocal(varStmt.name(), varType);
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
            case LetStmt letStmt -> {
                var initType = inferTypeExpr(ctx, letStmt.initializer());
                var varType = letStmt.explicitType() != null
                    ? mergeTypeExpr(letStmt.explicitType(), initType)
                    : initType;
                var typeName = getTypeName(varType);
                generateExpr(ctx, letStmt.initializer());
                int slot = ctx.allocateLocal(letStmt.name(), varType);
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
        var elementTypeExpr = inferElementTypeExpr(ctx, forExpr.iterable());
        var elementType = getTypeName(elementTypeExpr);

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

        var savedLocals = new HashMap<>(ctx.locals);
        var savedTypes = new HashMap<>(ctx.localTypes);
        var savedTypeExprs = new HashMap<>(ctx.localTypeExprs);
        try {
            // Unbox primitive or cast reference element types
            int elemSlot;
            switch (elementType) {
                case "Int" -> {
                    ctx.code.checkcast(ClassDesc.of("java.lang.Integer"));
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.lang.Integer"),
                        "intValue",
                        MethodTypeDesc.of(ConstantDescs.CD_int)
                    );
                    elemSlot = ctx.allocateLocal(forExpr.varName(), elementTypeExpr);
                    ctx.code.istore(elemSlot);
                }
                case "Long" -> {
                    ctx.code.checkcast(ClassDesc.of("java.lang.Long"));
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.lang.Long"),
                        "longValue",
                        MethodTypeDesc.of(ConstantDescs.CD_long)
                    );
                    elemSlot = ctx.allocateLocal(forExpr.varName(), elementTypeExpr);
                    ctx.code.lstore(elemSlot);
                }
                case "Bool" -> {
                    ctx.code.checkcast(ClassDesc.of("java.lang.Boolean"));
                    ctx.code.invokevirtual(
                        ClassDesc.of("java.lang.Boolean"),
                        "booleanValue",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean)
                    );
                    elemSlot = ctx.allocateLocal(forExpr.varName(), elementTypeExpr);
                    ctx.code.istore(elemSlot);
                }
                default -> {
                    castReferenceIfNeeded(ctx, elementType);
                    elemSlot = ctx.allocateLocal(forExpr.varName(), elementTypeExpr);
                    ctx.code.astore(elemSlot);
                }
            }

            // Infer yield expression type before block scopes pop locals
            String yieldTypeName = forExpr.hasYield() ? getTypeName(inferTypeExpr(ctx, forExpr.body())) : "Unit";

            // Generate body
            generateBlock(ctx, forExpr.body());

            if (forExpr.hasYield()) {
                // Box primitive values for adding to list
                boxPrimitiveType(ctx, yieldTypeName);

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
        } finally {
            ctx.locals.clear();
            ctx.locals.putAll(savedLocals);
            ctx.localTypes.clear();
            ctx.localTypes.putAll(savedTypes);
            ctx.localTypeExprs.clear();
            ctx.localTypeExprs.putAll(savedTypeExprs);
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
                    ctx.code.invokestatic(
                        ClassDesc.of("java.util.Collections"),
                        "unmodifiableList",
                        MethodTypeDesc.of(ClassDesc.of("java.util.List"), ClassDesc.of("java.util.List"))
                    );
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
                    var optType = inferTypeExpr(ctx, call.args().get(0));
                    TypeExpr optInner = (optType instanceof GenericTypeExpr gen
                        && gen.name().equals("Option")
                        && !gen.typeArgs().isEmpty())
                        ? gen.typeArgs().get(0)
                        : null;
                    var valueType = getTypeName(mergeTypeExpr(optInner, inferTypeExpr(ctx, call.args().get(1))));
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
                    unboxIfNeeded(ctx, valueType);
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
                var classDesc = ClassDesc.ofInternalName(moduleInternalName(methodName));
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
                var classDesc = ClassDesc.ofInternalName(moduleInternalName(methodName));
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
                            moduleInternalName(entry.getKey() + "$" + variant.name()));
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
            var classDesc = ClassDesc.ofInternalName(moduleInternalName(methodName + "$Fn"));
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
                            moduleInternalName(enumName + "$" + variantName));
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
        boxPrimitiveType(ctx, inferTypeName(ctx, expr));
    }

    private void boxPrimitiveType(CodeGenContext ctx, String typeName) {
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

    private void castReferenceIfNeeded(CodeGenContext ctx, String targetTypeName) {
        if (targetTypeName == null) {
            return;
        }
        switch (targetTypeName) {
            case "Int", "Long", "Bool", "Unit", "Object", "_", "OverflowError", "RangeError", "IndexError" -> {}
            case "String" -> ctx.code.checkcast(ConstantDescs.CD_String);
            case "List" -> ctx.code.checkcast(ClassDesc.of("java.util.List"));
            case "Option" -> ctx.code.checkcast(
                enums.containsKey("Option")
                    ? ClassDesc.ofInternalName(moduleInternalName("Option"))
                    : ClassDesc.ofInternalName("ngj/runtime/Option"));
            case "Result" -> ctx.code.checkcast(
                enums.containsKey("Result")
                    ? ClassDesc.ofInternalName(moduleInternalName("Result"))
                    : ClassDesc.ofInternalName("ngj/runtime/Result"));
            default -> {
                if (records.containsKey(targetTypeName)
                    || datas.containsKey(targetTypeName)
                    || enums.containsKey(targetTypeName)) {
                    ctx.code.checkcast(ClassDesc.ofInternalName(moduleInternalName(targetTypeName)));
                }
            }
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
            default -> castReferenceIfNeeded(ctx, targetTypeName);
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
        var classDesc = ClassDesc.ofInternalName(moduleInternalName(ctor.typePath()));
        
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
        // Infer full scrutinee TypeExpr before codegen
        TypeExpr scrutineeTypeExpr = inferTypeExpr(ctx, matchExpr.scrutinee());
        String scrutineeType = getTypeName(scrutineeTypeExpr);

        // Evaluate scrutinee, box if primitive, and store in local variable
        generateExpr(ctx, matchExpr.scrutinee());
        boxPrimitiveType(ctx, scrutineeType);
        var scrutineeSlot = ctx.allocateLocal("$match$scrutinee$" + ctx.nextSlot, "Object");
        ctx.code.astore(scrutineeSlot);

        var endLabel = ctx.code.newLabel();
        var arms = matchExpr.arms();

        // Track next arm labels and return coverage across arms
        Label nextArmLabel = null;
        boolean prevReturn = ctx.hasReturn;
        boolean allArmsReturned = true;

        for (int i = 0; i < arms.size(); i++) {
            var arm = arms.get(i);
            var pattern = arm.pattern();
            var isLastArm = (i == arms.size() - 1);

            // Bind previous arm's fallthrough label if any
            if (nextArmLabel != null) {
                ctx.code.labelBinding(nextArmLabel);
                nextArmLabel = null;
            }

            ctx.hasReturn = false;

            switch (pattern) {
                case WildcardPattern _ -> generateExpr(ctx, arm.body());

                case BindingPattern binding -> {
                    var savedLocals = new HashMap<>(ctx.locals);
                    var savedTypes = new HashMap<>(ctx.localTypes);
                    var savedTypeExprs = new HashMap<>(ctx.localTypeExprs);
                    try {
                        ctx.code.aload(scrutineeSlot);
                        unboxIfNeeded(ctx, scrutineeType);
                        var bindSlot = ctx.allocateLocal(binding.name(), scrutineeTypeExpr);
                        switch (scrutineeType) {
                            case "Long" -> ctx.code.lstore(bindSlot);
                            case "Int", "Bool" -> ctx.code.istore(bindSlot);
                            default -> ctx.code.astore(bindSlot);
                        }
                        generateExpr(ctx, arm.body());
                    } finally {
                        ctx.locals.clear();
                        ctx.locals.putAll(savedLocals);
                        ctx.localTypes.clear();
                        ctx.localTypes.putAll(savedTypes);
                        ctx.localTypeExprs.clear();
                        ctx.localTypeExprs.putAll(savedTypeExprs);
                    }
                }

                case LiteralPattern literal -> {
                    if (!isLastArm) {
                        nextArmLabel = ctx.code.newLabel();
                    }
                    ctx.code.aload(scrutineeSlot);
                    generateExpr(ctx, literal.literal());
                    boxIfNeeded(ctx, literal.literal());
                    ctx.code.invokestatic(
                        ClassDesc.of("java.util.Objects"),
                        "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object, ConstantDescs.CD_Object)
                    );
                    if (!isLastArm) {
                        ctx.code.ifeq(nextArmLabel);
                    } else {
                        ctx.code.pop();
                    }
                    generateExpr(ctx, arm.body());
                }

                case ConstructorPattern ctor -> {
                    if (!isLastArm) {
                        nextArmLabel = ctx.code.newLabel();
                    }
                    var savedLocals = new HashMap<>(ctx.locals);
                    var savedTypes = new HashMap<>(ctx.localTypes);
                    var savedTypeExprs = new HashMap<>(ctx.localTypeExprs);
                    try {
                        matchConstructorPattern(ctx, ctor, scrutineeTypeExpr, scrutineeSlot, !isLastArm, nextArmLabel);
                        generateExpr(ctx, arm.body());
                    } finally {
                        ctx.locals.clear();
                        ctx.locals.putAll(savedLocals);
                        ctx.localTypes.clear();
                        ctx.localTypes.putAll(savedTypes);
                        ctx.localTypeExprs.clear();
                        ctx.localTypeExprs.putAll(savedTypeExprs);
                    }
                }
            }

            boolean armReturned = ctx.hasReturn;
            allArmsReturned &= armReturned;
            if (!isLastArm && !armReturned) {
                ctx.code.goto_(endLabel);
            }
        }

        if (!allArmsReturned || arms.isEmpty()) {
            ctx.code.labelBinding(endLabel);
        }
        ctx.hasReturn = prevReturn || (!arms.isEmpty() && allArmsReturned);
    }

    private void matchConstructorPattern(
        CodeGenContext ctx,
        ConstructorPattern ctor,
        TypeExpr scrutTypeExpr,
        int valueSlot,
        boolean canFail,
        Label failLabel
    ) {
        var typePath = ctor.typePath();
        String shortVariantName = typePath.contains(".")
            ? typePath.substring(typePath.lastIndexOf('.') + 1)
            : typePath;
        boolean isRuntimeOption = !enums.containsKey("Option")
            && (shortVariantName.equals("Some") || shortVariantName.equals("None"));
        boolean isRuntimeResult = !enums.containsKey("Result")
            && (shortVariantName.equals("Ok") || shortVariantName.equals("Err"));
        boolean isRuntimeAdt = isRuntimeOption || isRuntimeResult;

        ClassDesc variantClass = resolveVariantClass(ctx, typePath);

        if (canFail) {
            ctx.code.aload(valueSlot);
            ctx.code.instanceOf(variantClass);
            ctx.code.ifeq(failLabel);
        }

        boolean needsCast = ctor.fields().stream().anyMatch(f ->
            !(f.pattern() instanceof WildcardPattern)
                && (canFail || !(f.pattern() instanceof LiteralPattern)));
        if (!needsCast) {
            return;
        }

        ctx.code.aload(valueSlot);
        ctx.code.checkcast(variantClass);
        var castedSlot = ctx.allocateLocal("$match$casted$" + ctx.nextSlot, "Object");
        ctx.code.astore(castedSlot);

        for (int fieldIdx = 0; fieldIdx < ctor.fields().size(); fieldIdx++) {
            var field = ctor.fields().get(fieldIdx);
            var pat = field.pattern();
            if (pat instanceof WildcardPattern) {
                continue;
            }
            if (!canFail && pat instanceof LiteralPattern) {
                continue;
            }

            String actualFieldName = resolveVariantFieldName(typePath, field.name(), fieldIdx);
            TypeExpr fieldTypeExpr;
            ClassDesc fieldDesc;
            if (isRuntimeAdt) {
                fieldTypeExpr = getResultOptionFieldTypeExpr(scrutTypeExpr, shortVariantName);
                fieldDesc = ConstantDescs.CD_Object;
            } else {
                fieldTypeExpr = lookupEnumVariantFieldTypeExpr(typePath, actualFieldName);
                fieldDesc = typeToClassDesc(fieldTypeExpr);
            }
            String fieldType = getTypeName(fieldTypeExpr);

            ctx.code.aload(castedSlot);
            ctx.code.getfield(variantClass, actualFieldName, fieldDesc);

            if (pat == null || pat instanceof BindingPattern) {
                String bindName = (pat instanceof BindingPattern bp) ? bp.name() : field.name();
                if (isRuntimeAdt) {
                    unboxIfNeeded(ctx, fieldType);
                } else {
                    castReferenceIfNeeded(ctx, fieldType);
                }
                var fieldSlot = ctx.allocateLocal(bindName, fieldTypeExpr);
                switch (fieldType) {
                    case "Long" -> ctx.code.lstore(fieldSlot);
                    case "Int", "Bool" -> ctx.code.istore(fieldSlot);
                    default -> ctx.code.astore(fieldSlot);
                }
            } else if (pat instanceof LiteralPattern lit) {
                if (!isRuntimeAdt) {
                    boxPrimitiveType(ctx, fieldType);
                }
                generateExpr(ctx, lit.literal());
                boxIfNeeded(ctx, lit.literal());
                ctx.code.invokestatic(
                    ClassDesc.of("java.util.Objects"),
                    "equals",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object, ConstantDescs.CD_Object)
                );
                ctx.code.ifeq(failLabel);
            } else if (pat instanceof ConstructorPattern nestedCtor) {
                castReferenceIfNeeded(ctx, fieldType);
                var nestedSlot = ctx.allocateLocal("$match$nested$" + ctx.nextSlot, "Object");
                ctx.code.astore(nestedSlot);
                matchConstructorPattern(ctx, nestedCtor, fieldTypeExpr, nestedSlot, canFail, failLabel);
            }
        }
    }

    /**
     * Get the correct field TypeExpr for Option/Result pattern bindings.
     * - Some(v): v has type T from Option<T>
     * - Ok(v): v has type T from Result<T, E>
     * - Err(e): e has type E from Result<T, E>
     */
    private TypeExpr getResultOptionFieldTypeExpr(TypeExpr scrutineeType, String variantName) {
        if (scrutineeType instanceof GenericTypeExpr gen) {
            if (variantName.equals("Some") && gen.name().equals("Option") && !gen.typeArgs().isEmpty()) {
                return gen.typeArgs().get(0);
            }
            if (variantName.equals("Ok") && gen.name().equals("Result") && !gen.typeArgs().isEmpty()) {
                return gen.typeArgs().get(0);
            }
            if (variantName.equals("Err") && gen.name().equals("Result") && gen.typeArgs().size() >= 2) {
                return gen.typeArgs().get(1);
            }
        }
        return OBJECT_TYPE;
    }

    private ClassDesc resolveVariantClass(CodeGenContext ctx, String typePath) {
        String shortVariantName = typePath.contains(".")
            ? typePath.substring(typePath.lastIndexOf('.') + 1)
            : typePath;

        if (!enums.containsKey("Option")
            && (shortVariantName.equals("Some") || shortVariantName.equals("None"))) {
            return ClassDesc.ofInternalName("ngj/runtime/Option$" + shortVariantName);
        }
        if (!enums.containsKey("Result")
            && (shortVariantName.equals("Ok") || shortVariantName.equals("Err"))) {
            return ClassDesc.ofInternalName("ngj/runtime/Result$" + shortVariantName);
        }

        if (typePath.contains(".")) {
            var parts = typePath.split("\\.");
            var enumName = parts[0];
            var variantName = parts[1];
            return ClassDesc.ofInternalName(moduleInternalName(enumName + "$" + variantName));
        }

        for (var entry : enums.entrySet()) {
            for (var variant : entry.getValue().variants()) {
                if (variant.name().equals(typePath)) {
                    return ClassDesc.ofInternalName(moduleInternalName(entry.getKey() + "$" + typePath));
                }
            }
        }

        return ClassDesc.ofInternalName(moduleInternalName(typePath));
    }

    /**
     * Resolve the actual field name in a variant class (or record/data).
     */
    private String resolveVariantFieldName(String typePath, String patternFieldName, int fieldIndex) {
        String enumName = typePath.contains(".")
            ? typePath.substring(0, typePath.lastIndexOf('.'))
            : null;
        String shortVariantName = typePath.contains(".")
            ? typePath.substring(typePath.lastIndexOf('.') + 1)
            : typePath;

        if (!enums.containsKey("Option") && shortVariantName.equals("Some")) {
            return "value";
        }
        if (!enums.containsKey("Result") && shortVariantName.equals("Ok")) {
            return "value";
        }
        if (!enums.containsKey("Result") && shortVariantName.equals("Err")) {
            return "error";
        }

        if (enumName != null && enums.containsKey(enumName)) {
            for (var variant : enums.get(enumName).variants()) {
                if (variant.name().equals(shortVariantName)) {
                    var fields = variant.fields();
                    if (fieldIndex < fields.size()) {
                        return fields.get(fieldIndex).name();
                    }
                }
            }
        } else {
            for (var entry : enums.entrySet()) {
                for (var variant : entry.getValue().variants()) {
                    if (variant.name().equals(shortVariantName)) {
                        var fields = variant.fields();
                        if (fieldIndex < fields.size()) {
                            return fields.get(fieldIndex).name();
                        }
                    }
                }
            }
        }

        if (records.containsKey(typePath)) {
            var params = records.get(typePath).params();
            if (fieldIndex < params.size()) {
                return params.get(fieldIndex).name();
            }
        }
        if (datas.containsKey(typePath)) {
            var fields = datas.get(typePath).fields();
            if (fieldIndex < fields.size()) {
                return fields.get(fieldIndex).name();
            }
        }

        return patternFieldName;
    }

    /**
     * Look up the TypeExpr of a field in an enum variant (or record/data).
     */
    private TypeExpr lookupEnumVariantFieldTypeExpr(String typePath, String fieldName) {
        String enumName = typePath.contains(".")
            ? typePath.substring(0, typePath.lastIndexOf('.'))
            : null;
        String shortVariantName = typePath.contains(".")
            ? typePath.substring(typePath.lastIndexOf('.') + 1)
            : typePath;

        if (enumName != null && enums.containsKey(enumName)) {
            for (var variant : enums.get(enumName).variants()) {
                if (variant.name().equals(shortVariantName)) {
                    for (var field : variant.fields()) {
                        if (field.name().equals(fieldName)) {
                            return field.type();
                        }
                    }
                }
            }
        } else {
            for (var entry : enums.entrySet()) {
                for (var variant : entry.getValue().variants()) {
                    if (variant.name().equals(shortVariantName)) {
                        for (var field : variant.fields()) {
                            if (field.name().equals(fieldName)) {
                                return field.type();
                            }
                        }
                    }
                }
            }
        }

        if (records.containsKey(typePath)) {
            for (var param : records.get(typePath).params()) {
                if (param.name().equals(fieldName)) {
                    return param.type();
                }
            }
        }
        if (datas.containsKey(typePath)) {
            for (var field : datas.get(typePath).fields()) {
                if (field.name().equals(fieldName)) {
                    return field.type();
                }
            }
        }

        return OBJECT_TYPE;
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
        if (!enums.containsKey("Result")) {
            ctx.code.checkcast(ClassDesc.ofInternalName("ngj/runtime/Result$Err"));
        }
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
            var ownerDesc = ClassDesc.ofInternalName(moduleInternalName(objTypeName));
            for (var param : rec.params()) {
                if (param.name().equals(access.fieldName())) {
                    ctx.code.getfield(ownerDesc, access.fieldName(), typeToClassDesc(param.type()));
                    return;
                }
            }
        } else if (datas.containsKey(objTypeName)) {
            var data = datas.get(objTypeName);
            var ownerDesc = ClassDesc.ofInternalName(moduleInternalName(objTypeName));
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
        var className = moduleInternalName(data.name());
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
        var className = moduleInternalName(record.name());
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
        var className = moduleInternalName(enumDecl.name());
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
        var baseClassName = moduleInternalName(enumDecl.name());
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
        if (type == null) return "Object";
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
        final Map<String, TypeExpr> localTypeExprs = new HashMap<>();
        int nextSlot;
        boolean hasReturn = false;
        
        CodeGenContext(CodeBuilder code, FnDecl fn) {
            this.code = code;
            this.fn = fn;
            this.nextSlot = 0;
            
            for (var param : fn.params()) {
                locals.put(param.name(), nextSlot);
                var paramType = param.type() != null ? param.type() : OBJECT_TYPE;
                var typeName = getTypeName(paramType);
                localTypes.put(param.name(), typeName);
                localTypeExprs.put(param.name(), paramType);
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
            ctx.localTypeExprs.putAll(this.localTypeExprs);
            ctx.nextSlot = this.nextSlot;
            ctx.hasReturn = this.hasReturn;
            return ctx;
        }
        
        int allocateLocal(String name, String typeName) {
            int slot = nextSlot;
            locals.put(name, slot);
            localTypes.put(name, typeName);
            localTypeExprs.put(name, simpleType(typeName));
            if (typeName.equals("Long")) {
                nextSlot += 2;
            } else {
                nextSlot++;
            }
            return slot;
        }
        
        int allocateLocal(String name, TypeExpr typeExpr) {
            var normalized = typeExpr != null ? typeExpr : OBJECT_TYPE;
            int slot = allocateLocal(name, getTypeName(normalized));
            localTypeExprs.put(name, normalized);
            return slot;
        }
        
        Integer getLocal(String name) {
            return locals.get(name);
        }
        
        String getLocalType(String name) {
            return localTypes.getOrDefault(name, "Object");
        }
        
        TypeExpr getLocalTypeExpr(String name) {
            return localTypeExprs.getOrDefault(name, OBJECT_TYPE);
        }
    }
}
