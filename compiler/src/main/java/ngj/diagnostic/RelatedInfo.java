package ngj.diagnostic;

public record RelatedInfo(
    String kind,
    String symbolId,
    SourceSpan span
) {
    public RelatedInfo(String kind, String symbolId) {
        this(kind, symbolId, null);
    }
}
