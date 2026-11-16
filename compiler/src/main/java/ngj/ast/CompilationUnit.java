package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record CompilationUnit(
    ModuleDecl moduleDecl,
    List<ImportDecl> imports,
    List<TopLevelDecl> declarations,
    SourceSpan span
) implements Node {
    public String modulePath() {
        return moduleDecl.path();
    }
}
