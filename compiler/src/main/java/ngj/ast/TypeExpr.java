package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;
import java.util.stream.Collectors;

public sealed interface TypeExpr extends Node permits
    SimpleTypeExpr, GenericTypeExpr, UnitTypeExpr,
    CapabilityTypeExpr, CaptureSetTypeExpr, RefinedTypeExpr,
    RangeTypeExpr, FunctionTypeExpr, TupleTypeExpr {}
