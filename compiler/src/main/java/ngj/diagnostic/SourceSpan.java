package ngj.diagnostic;

/**
 * A source location span with UTF-8 byte offsets.
 */
public record SourceSpan(
    String file,
    int startByte,
    int endByte
) {
    public SourceSpan(String file, int offset) {
        this(file, offset, offset);
    }
    
    public SourceSpan merge(SourceSpan other) {
        if (other == null) return this;
        return new SourceSpan(
            file,
            Math.min(startByte, other.startByte),
            Math.max(endByte, other.endByte)
        );
    }
}
