package ngj.ast;

import ngj.diagnostic.SourceSpan;

public sealed interface Statement extends Node
    permits ValStmt, VarStmt, AssignStmt, ExprStmt, LetStmt, ReturnStmt {}
