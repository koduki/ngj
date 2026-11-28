package ngj;

import ngj.cli.ExitCode;
import ngj.cli.Main;
import ngj.typechecker.protocol.ProtocolAutomaton;

import java.io.PrintStream;
import java.util.List;
import java.util.stream.Collectors;

public record InspectResult(
    SymbolInfo symbol,
    String protocolId,
    ProtocolAutomaton protocolAutomaton,
    String error
) {
    public InspectResult(SymbolInfo symbol) {
        this(symbol, null, null, null);
    }

    public InspectResult(SymbolInfo symbol, String error) {
        this(symbol, null, null, error);
    }

    public static InspectResult ofProtocol(String protocolId, ProtocolAutomaton automaton) {
        return new InspectResult(null, protocolId, automaton, null);
    }

    public int exitCode() {
        return error == null ? ExitCode.SUCCESS : ExitCode.BAD_INPUT;
    }

    public String toJson() {
        return toJson(
            "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            Main.computePolicyDigest("standard", "default", "tier1", "jvm", List.of()),
            Main.TARGET_PROFILE
        );
    }

    public String toJson(String sourceRevision, String policyDigest, String targetProfile) {
        if (error != null) {
            return """
                {
                  "schema_version": "%s",
                  "compiler_version": "%s",
                  "source_revision": "%s",
                  "policy_digest": "%s",
                  "target_profile": "%s",
                  "error": "%s"
                }""".formatted(
                    Main.INSPECT_SCHEMA_VERSION,
                    Main.COMPILER_VERSION,
                    escapeJson(sourceRevision),
                    escapeJson(policyDigest),
                    escapeJson(targetProfile),
                    escapeJson(error)
                );
        }

        if (protocolAutomaton != null) {
            String statesJson = protocolAutomaton.states().stream()
                .map(s -> "\"" + escapeJson(s) + "\"")
                .collect(Collectors.joining(", "));
            String finalJson = protocolAutomaton.finalStates().stream()
                .map(s -> "\"" + escapeJson(s) + "\"")
                .collect(Collectors.joining(", "));
            String transJson = protocolAutomaton.transitions().stream()
                .map(t -> """
                    {"from": "%s", "action": "%s", "to": "%s", "effects": [%s]}""".formatted(
                        escapeJson(t.from()),
                        escapeJson(t.action()),
                        escapeJson(t.to()),
                        t.effects().stream().map(e -> "\"" + escapeJson(e) + "\"").collect(Collectors.joining(", "))
                    ))
                .collect(Collectors.joining(", "));

            return """
                {
                  "schema_version": "%s",
                  "compiler_version": "%s",
                  "source_revision": "%s",
                  "policy_digest": "%s",
                  "target_profile": "%s",
                  "protocol_id": "%s",
                  "initial_state": "%s",
                  "states": [%s],
                  "final_states": [%s],
                  "transitions": [%s],
                  "mermaid": "%s"
                }""".formatted(
                    Main.INSPECT_SCHEMA_VERSION,
                    Main.COMPILER_VERSION,
                    escapeJson(sourceRevision),
                    escapeJson(policyDigest),
                    escapeJson(targetProfile),
                    escapeJson(protocolId),
                    escapeJson(protocolAutomaton.initialState()),
                    statesJson,
                    finalJson,
                    transJson,
                    escapeJson(protocolAutomaton.toMermaid())
                );
        }

        return symbol.toJson(sourceRevision, policyDigest, targetProfile);
    }

    public String toMermaid() {
        if (protocolAutomaton == null) {
            return "";
        }
        return protocolAutomaton.toMermaid();
    }

    public void printHuman(PrintStream out) {
        if (error != null) {
            out.println("Error: " + error);
        } else if (protocolAutomaton != null) {
            out.print(protocolAutomaton.toMermaid());
        } else {
            symbol.printHuman(out);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
