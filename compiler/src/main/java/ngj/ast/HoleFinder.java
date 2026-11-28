package ngj.ast;

import java.util.List;

/**
 * Utility for finding holes in AST.
 */
public final class HoleFinder {
    
    public boolean hasHoles(CompilationUnit unit) {
        for (var decl : unit.declarations()) {
            if (hasHolesInDecl(decl)) {
                return true;
            }
        }
        return false;
    }
    
    public HoleExpr findHole(CompilationUnit unit, String holeId) {
        for (var decl : unit.declarations()) {
            var hole = findHoleInDecl(decl, holeId);
            if (hole != null) {
                return hole;
            }
        }
        return null;
    }
    
    private boolean hasHolesInDecl(TopLevelDecl decl) {
        return switch (decl) {
            case FnDecl fn -> hasHolesInContracts(fn.contracts()) || hasHolesInExpr(fn.body());
            case SpecFnDecl specFn -> hasHolesInExpr(specFn.body());
            case LemmaDecl lemma -> hasHolesInContracts(lemma.contracts()) || hasHolesInExpr(lemma.body());
            case DataDecl _, EnumDecl _, RecordDecl _, TypeAliasDecl _, ProtocolDecl _ -> false;
        };
    }

    private boolean hasHolesInContracts(List<ContractClause> contracts) {
        for (var c : contracts) {
            if (hasHolesInExpr(c.condition())) return true;
        }
        return false;
    }
    
    private HoleExpr findHoleInDecl(TopLevelDecl decl, String holeId) {
        return switch (decl) {
            case FnDecl fn -> {
                var inContracts = findHoleInContracts(fn.contracts(), holeId);
                yield inContracts != null ? inContracts : findHoleInExpr(fn.body(), holeId);
            }
            case SpecFnDecl specFn -> findHoleInExpr(specFn.body(), holeId);
            case LemmaDecl lemma -> {
                var inContracts = findHoleInContracts(lemma.contracts(), holeId);
                yield inContracts != null ? inContracts : findHoleInExpr(lemma.body(), holeId);
            }
            case DataDecl _, EnumDecl _, RecordDecl _, TypeAliasDecl _, ProtocolDecl _ -> null;
        };
    }

    private HoleExpr findHoleInContracts(List<ContractClause> contracts, String holeId) {
        for (var c : contracts) {
            var found = findHoleInExpr(c.condition(), holeId);
            if (found != null) return found;
        }
        return null;
    }
    
    private boolean hasHolesInExpr(Expr expr) {
        if (expr == null) return false;
        
        return switch (expr) {
            case HoleExpr _ -> true;
            case LiteralExpr _, IdentExpr _ -> false;
            case BinaryExpr binary -> hasHolesInExpr(binary.left()) || hasHolesInExpr(binary.right());
            case UnaryExpr unary -> hasHolesInExpr(unary.operand());
            case CallExpr call -> {
                if (hasHolesInExpr(call.callee())) yield true;
                for (var arg : call.args()) {
                    if (hasHolesInExpr(arg)) yield true;
                }
                yield false;
            }
            case FieldAccessExpr access -> hasHolesInExpr(access.object());
            case ConstructorExpr ctor -> {
                for (var field : ctor.fields()) {
                    if (hasHolesInExpr(field.value())) yield true;
                }
                yield false;
            }
            case IfExpr ifExpr -> {
                if (hasHolesInExpr(ifExpr.condition())) yield true;
                if (hasHolesInExpr(ifExpr.thenBranch())) yield true;
                if (ifExpr.elseBranch() != null && hasHolesInExpr(ifExpr.elseBranch())) yield true;
                yield false;
            }
            case MatchExpr matchExpr -> {
                if (hasHolesInExpr(matchExpr.scrutinee())) yield true;
                for (var arm : matchExpr.arms()) {
                    if (hasHolesInExpr(arm.body())) yield true;
                }
                yield false;
            }
            case BlockExpr block -> {
                for (var stmt : block.statements()) {
                    if (hasHolesInStmt(stmt)) yield true;
                }
                if (block.tailExpr() != null && hasHolesInExpr(block.tailExpr())) yield true;
                yield false;
            }
            case PropagateExpr prop -> hasHolesInExpr(prop.inner());
            case ParenExpr paren -> hasHolesInExpr(paren.inner());
            case ForExpr forExpr -> hasHolesInExpr(forExpr.iterable()) || hasHolesInExpr(forExpr.body());
            case WhileExpr whileExpr -> hasHolesInExpr(whileExpr.condition())
                || (whileExpr.invariant() != null && hasHolesInExpr(whileExpr.invariant()))
                || hasHolesInExpr(whileExpr.body());
            case LambdaExpr lambda -> hasHolesInExpr(lambda.body());
            case ScopeExpr scope -> hasHolesInExpr(scope.body());
            case ForkExpr fork -> hasHolesInExpr(fork.body());
            case ConsumeExpr consume -> hasHolesInExpr(consume.target());
            case TupleExpr tuple -> {
                for (var el : tuple.elements()) {
                    if (hasHolesInExpr(el)) yield true;
                }
                yield false;
            }
        };
    }
    
    private boolean hasHolesInStmt(Statement stmt) {
        return switch (stmt) {
            case LetStmt let -> hasHolesInExpr(let.initializer());
            case ValStmt val -> hasHolesInExpr(val.initializer());
            case VarStmt var -> hasHolesInExpr(var.initializer());
            case AssignStmt assign -> hasHolesInExpr(assign.value());
            case ExprStmt expr -> hasHolesInExpr(expr.expr());
            case ReturnStmt ret -> ret.value() != null && hasHolesInExpr(ret.value());
            case TupleDestructureStmt tuple -> hasHolesInExpr(tuple.initializer());
            case InvariantStmt inv -> hasHolesInExpr(inv.condition());
            case ProofStmt proof -> hasHolesInExpr(proof.body());
            case AssumeStmt assume -> hasHolesInExpr(assume.condition());
        };
    }
    
    private HoleExpr findHoleInExpr(Expr expr, String holeId) {
        if (expr == null) return null;
        
        return switch (expr) {
            case HoleExpr hole -> hole.name().equals(holeId) ? hole : null;
            case LiteralExpr _, IdentExpr _ -> null;
            case BinaryExpr binary -> {
                var left = findHoleInExpr(binary.left(), holeId);
                yield left != null ? left : findHoleInExpr(binary.right(), holeId);
            }
            case UnaryExpr unary -> findHoleInExpr(unary.operand(), holeId);
            case CallExpr call -> {
                var callee = findHoleInExpr(call.callee(), holeId);
                if (callee != null) yield callee;
                for (var arg : call.args()) {
                    var found = findHoleInExpr(arg, holeId);
                    if (found != null) yield found;
                }
                yield null;
            }
            case FieldAccessExpr access -> findHoleInExpr(access.object(), holeId);
            case ConstructorExpr ctor -> {
                for (var field : ctor.fields()) {
                    var found = findHoleInExpr(field.value(), holeId);
                    if (found != null) yield found;
                }
                yield null;
            }
            case IfExpr ifExpr -> {
                var cond = findHoleInExpr(ifExpr.condition(), holeId);
                if (cond != null) yield cond;
                var then = findHoleInExpr(ifExpr.thenBranch(), holeId);
                if (then != null) yield then;
                yield ifExpr.elseBranch() != null ? findHoleInExpr(ifExpr.elseBranch(), holeId) : null;
            }
            case MatchExpr matchExpr -> {
                var scrutinee = findHoleInExpr(matchExpr.scrutinee(), holeId);
                if (scrutinee != null) yield scrutinee;
                for (var arm : matchExpr.arms()) {
                    var found = findHoleInExpr(arm.body(), holeId);
                    if (found != null) yield found;
                }
                yield null;
            }
            case BlockExpr block -> {
                for (var stmt : block.statements()) {
                    var found = findHoleInStmt(stmt, holeId);
                    if (found != null) yield found;
                }
                yield block.tailExpr() != null ? findHoleInExpr(block.tailExpr(), holeId) : null;
            }
            case PropagateExpr prop -> findHoleInExpr(prop.inner(), holeId);
            case ParenExpr paren -> findHoleInExpr(paren.inner(), holeId);
            case ForExpr forExpr -> {
                var iter = findHoleInExpr(forExpr.iterable(), holeId);
                yield iter != null ? iter : findHoleInExpr(forExpr.body(), holeId);
            }
            case WhileExpr whileExpr -> {
                var cond = findHoleInExpr(whileExpr.condition(), holeId);
                if (cond != null) yield cond;
                if (whileExpr.invariant() != null) {
                    var inv = findHoleInExpr(whileExpr.invariant(), holeId);
                    if (inv != null) yield inv;
                }
                yield findHoleInExpr(whileExpr.body(), holeId);
            }
            case LambdaExpr lambda -> findHoleInExpr(lambda.body(), holeId);
            case ScopeExpr scope -> findHoleInExpr(scope.body(), holeId);
            case ForkExpr fork -> findHoleInExpr(fork.body(), holeId);
            case ConsumeExpr consume -> findHoleInExpr(consume.target(), holeId);
            case TupleExpr tuple -> {
                for (var el : tuple.elements()) {
                    var found = findHoleInExpr(el, holeId);
                    if (found != null) yield found;
                }
                yield null;
            }
        };
    }
    
    private HoleExpr findHoleInStmt(Statement stmt, String holeId) {
        return switch (stmt) {
            case LetStmt let -> findHoleInExpr(let.initializer(), holeId);
            case ValStmt val -> findHoleInExpr(val.initializer(), holeId);
            case VarStmt var -> findHoleInExpr(var.initializer(), holeId);
            case AssignStmt assign -> findHoleInExpr(assign.value(), holeId);
            case ExprStmt expr -> findHoleInExpr(expr.expr(), holeId);
            case ReturnStmt ret -> ret.value() != null ? findHoleInExpr(ret.value(), holeId) : null;
            case TupleDestructureStmt tuple -> findHoleInExpr(tuple.initializer(), holeId);
            case InvariantStmt inv -> findHoleInExpr(inv.condition(), holeId);
            case ProofStmt proof -> findHoleInExpr(proof.body(), holeId);
            case AssumeStmt assume -> findHoleInExpr(assume.condition(), holeId);
        };
    }
}
