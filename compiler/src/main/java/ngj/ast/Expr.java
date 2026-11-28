package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public sealed interface Expr extends Node permits
    LiteralExpr, IdentExpr, HoleExpr, BinaryExpr, UnaryExpr, 
    CallExpr, FieldAccessExpr, ConstructorExpr, IfExpr, 
    MatchExpr, BlockExpr, PropagateExpr, ParenExpr,
    ForExpr, WhileExpr, LambdaExpr,
    ScopeExpr, ForkExpr, ConsumeExpr, TupleExpr {}
