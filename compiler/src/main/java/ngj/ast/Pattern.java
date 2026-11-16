package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public sealed interface Pattern extends Node permits
    WildcardPattern, LiteralPattern, BindingPattern, ConstructorPattern {}
