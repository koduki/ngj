package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record ProtocolDecl(
    String name,
    boolean isPublic,
    String parentProtocol,
    String initialState,
    List<String> states,
    List<String> finalStates,
    List<Transition> transitions,
    List<Annotation> annotations,
    SourceSpan span
) implements TopLevelDecl {
    public record Transition(
        String name,
        String fromState,
        String toState,
        List<String> effects,
        SourceSpan span
    ) implements Node {}
}
