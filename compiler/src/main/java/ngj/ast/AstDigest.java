package ngj.ast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Canonical normalized AST SHA-256 hash utility for spec-drift and source_revision.
 * Ignores SourceSpan offsets so formatting/comment-only edits produce identical AST digests.
 */
public final class AstDigest {

    private AstDigest() {}

    public static String sha256(CompilationUnit unit) {
        var sb = new StringBuilder();
        appendCompilationUnit(sb, unit);
        return sha256Hex(sb.toString());
    }

    public static String sha256(TopLevelDecl decl) {
        var sb = new StringBuilder();
        appendDecl(sb, decl);
        return sha256Hex(sb.toString());
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void appendCompilationUnit(StringBuilder sb, CompilationUnit unit) {
        sb.append("CU(").append(unit.moduleDecl().path()).append(";");
        for (var imp : unit.imports()) {
            sb.append("IMP(").append(imp.modulePath()).append(":").append(imp.names()).append(":").append(imp.isWildcard()).append(")");
        }
        for (var decl : unit.declarations()) {
            appendDecl(sb, decl);
        }
        sb.append(")");
    }

    private static void appendDecl(StringBuilder sb, TopLevelDecl decl) {
        switch (decl) {
            case FnDecl fn -> {
                sb.append("FN(").append(fn.isPublic()).append(",").append(fn.name()).append(",");
                appendAnnotations(sb, fn.annotations());
                appendParams(sb, fn.params());
                appendType(sb, fn.returnType());
                sb.append("EFF(").append(String.join(",", fn.effects())).append(")");
                sb.append("CAPS(").append(String.join(",", fn.captureBounds())).append(")");
                appendContracts(sb, fn.contracts());
                appendExpr(sb, fn.body());
                sb.append(")");
            }
            case RecordDecl rec -> {
                sb.append("REC(").append(rec.isPublic()).append(",").append(rec.name()).append(",");
                appendAnnotations(sb, rec.annotations());
                appendParams(sb, rec.params());
                sb.append(")");
            }
            case DataDecl data -> {
                sb.append("DATA(").append(data.isPublic()).append(",").append(data.name()).append(",");
                for (var f : data.fields()) {
                    sb.append(f.name()).append(":");
                    appendType(sb, f.type());
                    sb.append(";");
                }
                sb.append(")");
            }
            case EnumDecl en -> {
                sb.append("ENUM(").append(en.isPublic()).append(",").append(en.name()).append(",");
                appendAnnotations(sb, en.annotations());
                sb.append("TP(").append(String.join(",", en.typeParams())).append(")");
                for (var v : en.variants()) {
                    sb.append("V(").append(v.name()).append(":");
                    for (var f : v.fields()) {
                        sb.append(f.name()).append(":");
                        appendType(sb, f.type());
                        sb.append(";");
                    }
                    sb.append(")");
                }
                sb.append(")");
            }
            case TypeAliasDecl alias -> {
                sb.append("TYPE(").append(alias.isPublic()).append(",").append(alias.name()).append(",");
                appendAnnotations(sb, alias.annotations());
                sb.append("TP(").append(String.join(",", alias.typeParams())).append(")");
                appendType(sb, alias.targetType());
                sb.append(")");
            }
            case ProtocolDecl proto -> {
                sb.append("PROTO(").append(proto.isPublic()).append(",").append(proto.name()).append(",");
                sb.append("REF(").append(proto.parentProtocol()).append(")");
                appendAnnotations(sb, proto.annotations());
                sb.append("INIT(").append(proto.initialState()).append(")");
                sb.append("STATES(").append(String.join(",", proto.states())).append(")");
                sb.append("FINAL(").append(String.join(",", proto.finalStates())).append(")");
                for (var t : proto.transitions()) {
                    sb.append("TR(").append(t.name()).append(",").append(t.fromState()).append("->").append(t.toState())
                      .append(",EFF(").append(String.join(",", t.effects())).append("))");
                }
                sb.append(")");
            }
            case SpecFnDecl specFn -> {
                sb.append("SPEC(").append(specFn.isPublic()).append(",").append(specFn.isPredicate()).append(",").append(specFn.name()).append(",");
                appendAnnotations(sb, specFn.annotations());
                appendParams(sb, specFn.params());
                appendType(sb, specFn.returnType());
                appendExpr(sb, specFn.body());
                sb.append(")");
            }
            case LemmaDecl lemma -> {
                sb.append("LEMMA(").append(lemma.isPublic()).append(",").append(lemma.name()).append(",");
                appendAnnotations(sb, lemma.annotations());
                appendParams(sb, lemma.params());
                appendContracts(sb, lemma.contracts());
                appendExpr(sb, lemma.body());
                sb.append(")");
            }
        }
    }

    private static void appendAnnotations(StringBuilder sb, List<Annotation> annotations) {
        for (var ann : annotations) {
            sb.append("@").append(ann.name()).append("(").append(String.join(",", ann.args())).append(")");
        }
    }

    private static void appendParams(StringBuilder sb, List<Param> params) {
        sb.append("P(");
        for (var p : params) {
            if (p.isConsume()) sb.append("consume ");
            sb.append(p.name()).append(":");
            appendType(sb, p.type());
            sb.append(",");
        }
        sb.append(")");
    }

    private static void appendContracts(StringBuilder sb, List<ContractClause> contracts) {
        for (var c : contracts) {
            sb.append(c.kind()).append("(");
            appendExpr(sb, c.condition());
            sb.append(")");
        }
    }

    private static void appendType(StringBuilder sb, TypeExpr type) {
        if (type == null) {
            sb.append("null");
            return;
        }
        switch (type) {
            case SimpleTypeExpr s -> sb.append(s.name());
            case UnitTypeExpr _ -> sb.append("Unit");
            case GenericTypeExpr g -> {
                sb.append(g.name()).append("<");
                for (var a : g.typeArgs()) {
                    appendType(sb, a);
                    sb.append(",");
                }
                sb.append(">");
            }
            case CapabilityTypeExpr c -> {
                sb.append(c.mode()).append("(");
                appendType(sb, c.inner());
                sb.append(")");
            }
            case CaptureSetTypeExpr c -> sb.append("caps(").append(String.join(",", c.targets())).append(")");
            case RefinedTypeExpr r -> {
                sb.append("REFINE(").append(r.binderName()).append(":");
                appendType(sb, r.baseType());
                sb.append("|");
                appendExpr(sb, r.predicate());
                sb.append(")");
            }
            case RangeTypeExpr r -> {
                sb.append("RANGE(");
                appendType(sb, r.baseType());
                sb.append("[");
                appendExpr(sb, r.lowerBound());
                sb.append(r.upperInclusive() ? ".." : "..<");
                appendExpr(sb, r.upperBound());
                sb.append("])");
            }
            case FunctionTypeExpr f -> {
                sb.append("FNTYPE(");
                for (var pt : f.paramTypes()) {
                    appendType(sb, pt);
                    sb.append(",");
                }
                sb.append("->");
                appendType(sb, f.returnType());
                sb.append(",EFF(").append(String.join(",", f.effects())).append(")");
                sb.append(",CAPS(").append(String.join(",", f.captureBounds())).append("))");
            }
            case TupleTypeExpr t -> {
                sb.append("TUPLE(");
                for (var el : t.elements()) {
                    appendType(sb, el);
                    sb.append(",");
                }
                sb.append(")");
            }
        }
    }

    private static void appendStmt(StringBuilder sb, Statement stmt) {
        switch (stmt) {
            case ValStmt v -> {
                sb.append("VAL(").append(v.name()).append(":");
                appendType(sb, v.explicitType());
                sb.append("=");
                appendExpr(sb, v.initializer());
                sb.append(")");
            }
            case VarStmt v -> {
                sb.append("VAR(").append(v.name()).append(":");
                appendType(sb, v.explicitType());
                sb.append("=");
                appendExpr(sb, v.initializer());
                sb.append(")");
            }
            case LetStmt l -> {
                sb.append("LET(").append(l.name()).append(":");
                appendType(sb, l.explicitType());
                sb.append("=");
                appendExpr(sb, l.initializer());
                sb.append(")");
            }
            case AssignStmt a -> {
                sb.append("ASSIGN(").append(a.name()).append("=");
                appendExpr(sb, a.value());
                sb.append(")");
            }
            case ExprStmt e -> {
                sb.append("EXPR(");
                appendExpr(sb, e.expr());
                sb.append(")");
            }
            case ReturnStmt r -> {
                sb.append("RET(");
                appendExpr(sb, r.value());
                sb.append(")");
            }
            case TupleDestructureStmt t -> {
                sb.append("TDEST(").append(String.join(",", t.names())).append(":");
                for (var ty : t.explicitTypes()) {
                    appendType(sb, ty);
                    sb.append(",");
                }
                sb.append("=");
                appendExpr(sb, t.initializer());
                sb.append(")");
            }
            case InvariantStmt i -> {
                sb.append("INV(");
                appendExpr(sb, i.condition());
                sb.append(")");
            }
            case ProofStmt p -> {
                sb.append("PROOF(");
                appendExpr(sb, p.body());
                sb.append(")");
            }
            case AssumeStmt a -> {
                sb.append("ASSUME(");
                appendExpr(sb, a.condition());
                sb.append(")");
            }
        }
    }

    private static void appendExpr(StringBuilder sb, Expr expr) {
        if (expr == null) {
            sb.append("null");
            return;
        }
        switch (expr) {
            case LiteralExpr l -> sb.append("LIT(").append(l.kind()).append(":").append(l.value()).append(")");
            case IdentExpr i -> sb.append("ID(").append(i.name()).append(")");
            case HoleExpr h -> sb.append("HOLE(").append(h.kind()).append(":").append(h.name()).append(")");
            case BinaryExpr b -> {
                sb.append("BIN(").append(b.op()).append(",");
                appendExpr(sb, b.left());
                sb.append(",");
                appendExpr(sb, b.right());
                sb.append(")");
            }
            case UnaryExpr u -> {
                sb.append("UN(").append(u.op()).append(",");
                appendExpr(sb, u.operand());
                sb.append(")");
            }
            case CallExpr c -> {
                sb.append("CALL(");
                appendExpr(sb, c.callee());
                sb.append(",TA(");
                for (var ta : c.typeArgs()) {
                    appendType(sb, ta);
                    sb.append(",");
                }
                sb.append("),ARGS(");
                for (var arg : c.args()) {
                    appendExpr(sb, arg);
                    sb.append(",");
                }
                sb.append("))");
            }
            case FieldAccessExpr f -> {
                sb.append("FA(");
                appendExpr(sb, f.object());
                sb.append(".").append(f.fieldName()).append(")");
            }
            case ConstructorExpr c -> {
                sb.append("CTOR(").append(c.typePath()).append(",");
                for (var f : c.fields()) {
                    sb.append(f.name()).append(":");
                    appendExpr(sb, f.value());
                    sb.append(",");
                }
                sb.append(")");
            }
            case IfExpr i -> {
                sb.append("IF(");
                appendExpr(sb, i.condition());
                sb.append(",");
                appendExpr(sb, i.thenBranch());
                sb.append(",");
                appendExpr(sb, i.elseBranch());
                sb.append(")");
            }
            case MatchExpr m -> {
                sb.append("MATCH(");
                appendExpr(sb, m.scrutinee());
                for (var arm : m.arms()) {
                    sb.append(",ARM(");
                    appendPattern(sb, arm.pattern());
                    sb.append("=>");
                    appendExpr(sb, arm.body());
                    sb.append(")");
                }
                sb.append(")");
            }
            case BlockExpr b -> {
                sb.append("BLK(");
                for (var s : b.statements()) {
                    appendStmt(sb, s);
                    sb.append(";");
                }
                appendExpr(sb, b.tailExpr());
                sb.append(")");
            }
            case PropagateExpr p -> {
                sb.append("PROP(");
                appendExpr(sb, p.inner());
                sb.append(")");
            }
            case ParenExpr p -> {
                sb.append("PAREN(");
                appendExpr(sb, p.inner());
                sb.append(")");
            }
            case ForExpr f -> {
                sb.append("FOR(").append(f.varName()).append(",").append(f.hasYield()).append(",");
                appendExpr(sb, f.iterable());
                sb.append(",");
                appendExpr(sb, f.body());
                sb.append(")");
            }
            case WhileExpr w -> {
                sb.append("WHILE(");
                appendExpr(sb, w.condition());
                sb.append(",INV(");
                appendExpr(sb, w.invariant());
                sb.append("),");
                appendExpr(sb, w.body());
                sb.append(")");
            }
            case LambdaExpr l -> {
                sb.append("LAMBDA(");
                appendParams(sb, l.params());
                appendExpr(sb, l.body());
                sb.append(")");
            }
            case ScopeExpr s -> {
                sb.append("SCOPE(");
                appendExpr(sb, s.body());
                sb.append(")");
            }
            case ForkExpr f -> {
                sb.append("FORK(");
                appendExpr(sb, f.body());
                sb.append(")");
            }
            case ConsumeExpr c -> {
                sb.append("CONSUME(");
                appendExpr(sb, c.target());
                sb.append(")");
            }
            case TupleExpr t -> {
                sb.append("TUPLE(");
                for (var el : t.elements()) {
                    appendExpr(sb, el);
                    sb.append(",");
                }
                sb.append(")");
            }
        }
    }

    private static void appendPattern(StringBuilder sb, Pattern p) {
        switch (p) {
            case WildcardPattern _ -> sb.append("_");
            case BindingPattern b -> sb.append("BP(").append(b.name()).append(")");
            case LiteralPattern l -> {
                sb.append("LP(");
                appendExpr(sb, l.literal());
                sb.append(")");
            }
            case ConstructorPattern c -> {
                sb.append("CP(").append(c.typePath()).append(",");
                for (var f : c.fields()) {
                    sb.append(f.name()).append(":");
                    appendPattern(sb, f.pattern());
                    sb.append(",");
                }
                sb.append(")");
            }
        }
    }
}
