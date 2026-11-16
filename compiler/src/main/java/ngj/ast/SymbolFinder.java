package ngj.ast;

import ngj.SymbolInfo;

import java.util.List;

/**
 * Utility for finding symbols in AST.
 */
public final class SymbolFinder {
    
    public SymbolInfo find(CompilationUnit unit, String symbolId) {
        var modulePath = unit.modulePath();
        
        for (var decl : unit.declarations()) {
            var info = findInDecl(decl, symbolId, modulePath);
            if (info != null) {
                return info;
            }
        }
        
        return null;
    }
    
    private SymbolInfo findInDecl(TopLevelDecl decl, String symbolId, String modulePath) {
        return switch (decl) {
            case FnDecl fn -> {
                var id = modulePath + "." + fn.name();
                if (id.equals(symbolId) || fn.name().equals(symbolId)) {
                    yield new SymbolInfo(
                        id,
                        "function",
                        fn.name(),
                        modulePath,
                        fn.returnType(),
                        fn.effects(),
                        fn.isPublic() ? "pub" : "private",
                        fn.signature()
                    );
                }
                yield null;
            }
            case DataDecl data -> {
                var id = modulePath + "." + data.name();
                if (id.equals(symbolId) || data.name().equals(symbolId)) {
                    yield new SymbolInfo(
                        id,
                        "data",
                        data.name(),
                        modulePath,
                        null,
                        List.of(),
                        data.isPublic() ? "pub" : "private",
                        "data " + data.name()
                    );
                }
                yield null;
            }
            case RecordDecl record -> {
                var id = modulePath + "." + record.name();
                if (id.equals(symbolId) || record.name().equals(symbolId)) {
                    yield new SymbolInfo(
                        id,
                        "record",
                        record.name(),
                        modulePath,
                        null,
                        List.of(),
                        record.isPublic() ? "public" : "private",
                        "record " + record.name()
                    );
                }
                yield null;
            }
            case EnumDecl enumDecl -> {
                var id = modulePath + "." + enumDecl.name();
                if (id.equals(symbolId) || enumDecl.name().equals(symbolId)) {
                    yield new SymbolInfo(
                        id,
                        "enum",
                        enumDecl.name(),
                        modulePath,
                        null,
                        List.of(),
                        enumDecl.isPublic() ? "public" : "private",
                        "enum " + enumDecl.name()
                    );
                }
                for (var variant : enumDecl.variants()) {
                    var variantId = id + "." + variant.name();
                    if (variantId.equals(symbolId) || variant.name().equals(symbolId)) {
                        yield new SymbolInfo(
                            variantId,
                            "variant",
                            variant.name(),
                            modulePath,
                            null,
                            List.of(),
                            enumDecl.isPublic() ? "public" : "private",
                            enumDecl.name() + "." + variant.name()
                        );
                    }
                }
                yield null;
            }
        };
    }
}
