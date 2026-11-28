package ngj.ast;

import ngj.diagnostic.SourceSpan;

/**
 * Base interface for all AST nodes.
 */
public sealed interface Node permits 
    CompilationUnit, ModuleDecl, ImportDecl, TopLevelDecl,
    Statement, Expr, TypeExpr, Pattern,
    FieldDecl, Variant, Param,
    Annotation, ContractClause, ProtocolDecl.Transition {
    
    SourceSpan span();
}
