package ngj.gate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure in-memory unified-diff ({@code git diff} / {@code diff -u}) parser and
 * applier over virtual workspace {@code Map<String, String>} files.
 *
 * <p>Never reads or mutates files on disk. Rejects malformed headers, invalid
 * hunk counts, path traversals, and context/deletion mismatches cleanly.
 */
public final class PatchApplier {

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    public record PatchResult(
            boolean applied,
            Map<String, String> updatedFiles,
            List<String> changedFiles,
            String errorDetail) {

        public PatchResult {
            updatedFiles = updatedFiles == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new TreeMap<>(updatedFiles));
            changedFiles = changedFiles == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(changedFiles));
            errorDetail = errorDetail == null ? "" : errorDetail;
        }

        public static PatchResult success(
                Map<String, String> updatedFiles, List<String> changedFiles) {
            return new PatchResult(true, updatedFiles, changedFiles, "");
        }

        public static PatchResult failure(String errorDetail) {
            return new PatchResult(false, Map.of(), List.of(), errorDetail);
        }
    }

    private enum HunkOp {
        CONTEXT,
        ADD,
        DELETE
    }

    private record HunkLine(HunkOp op, String text) {}

    private record Hunk(
            int oldStart,
            int oldCount,
            int newStart,
            int newCount,
            List<HunkLine> lines,
            boolean noNewlineAtEnd) {}

    private record FilePatch(
            String oldPath,
            String newPath,
            boolean isNewFile,
            boolean isDeletedFile,
            List<Hunk> hunks) {}

    private PatchApplier() {}

    /**
     * Parses and applies a unified diff to {@code virtualFiles} purely in memory.
     */
    public static PatchResult apply(Map<String, String> virtualFiles, String unifiedDiff) {
        Objects.requireNonNull(virtualFiles, "virtualFiles");
        Objects.requireNonNull(unifiedDiff, "unifiedDiff");

        if (unifiedDiff.isBlank()) {
            return PatchResult.failure("Empty patch");
        }

        List<FilePatch> patches;
        try {
            patches = parsePatches(unifiedDiff);
        } catch (IllegalArgumentException e) {
            return PatchResult.failure(e.getMessage());
        }

        if (patches.isEmpty()) {
            return PatchResult.failure("No valid file diffs found in patch");
        }

        TreeMap<String, String> workspace = new TreeMap<>(virtualFiles);
        List<String> changedFiles = new ArrayList<>();

        for (FilePatch fp : patches) {
            String targetPath = fp.isDeletedFile() ? fp.oldPath() : fp.newPath();
            if (!isSafeRelativePath(targetPath)) {
                return PatchResult.failure("Unsafe or invalid patch path: " + targetPath);
            }

            if (fp.isNewFile()) {
                if (workspace.containsKey(targetPath)) {
                    return PatchResult.failure("Cannot create file that already exists: " + targetPath);
                }
                String created;
                try {
                    created = applyHunks(List.of(), false, fp.hunks(), targetPath);
                } catch (IllegalArgumentException e) {
                    return PatchResult.failure(e.getMessage());
                }
                workspace.put(targetPath, created);
                changedFiles.add(targetPath);
            } else if (fp.isDeletedFile()) {
                String existing = workspace.get(targetPath);
                if (existing == null) {
                    return PatchResult.failure("Cannot delete missing file: " + targetPath);
                }
                try {
                    String afterDelete = applyHunks(
                            splitLines(existing), existing.endsWith("\n"), fp.hunks(), targetPath);
                    if (!afterDelete.isEmpty()) {
                        return PatchResult.failure(
                                "Deleted file still has remaining content after hunks: " + targetPath);
                    }
                } catch (IllegalArgumentException e) {
                    return PatchResult.failure(e.getMessage());
                }
                workspace.remove(targetPath);
                changedFiles.add(targetPath);
            } else {
                String existing = workspace.get(targetPath);
                if (existing == null) {
                    return PatchResult.failure("Target file not found in workspace: " + targetPath);
                }
                String updated;
                try {
                    updated = applyHunks(
                            splitLines(existing), existing.endsWith("\n"), fp.hunks(), targetPath);
                } catch (IllegalArgumentException e) {
                    return PatchResult.failure(e.getMessage());
                }
                workspace.put(targetPath, updated);
                changedFiles.add(targetPath);
            }
        }

        return PatchResult.success(workspace, changedFiles);
    }

    private static List<FilePatch> parsePatches(String unifiedDiff) {
        String normalized = unifiedDiff.replace("\r\n", "\n");
        String[] rawLines = normalized.split("\n", -1);
        int n = rawLines.length;
        if (n > 0 && rawLines[n - 1].isEmpty()) {
            n--;
        }

        List<FilePatch> patches = new ArrayList<>();
        int i = 0;
        while (i < n) {
            String line = rawLines[i];
            if (line.startsWith("diff --git ")) {
                i++;
                while (i < n && !rawLines[i].startsWith("--- ") && !rawLines[i].startsWith("diff --git ")) {
                    i++;
                }
                if (i >= n || rawLines[i].startsWith("diff --git ")) {
                    throw new IllegalArgumentException("Malformed git diff header without ---/+++ lines");
                }
            } else if (!line.startsWith("--- ")) {
                if (line.isBlank()) {
                    i++;
                    continue;
                }
                throw new IllegalArgumentException("Unexpected line outside file diff: " + line);
            }

            // Parse "--- <oldPath>" and "+++ <newPath>"
            String oldHeader = rawLines[i++];
            if (i >= n || !rawLines[i].startsWith("+++ ")) {
                throw new IllegalArgumentException("Expected +++ header after: " + oldHeader);
            }
            String newHeader = rawLines[i++];

            String oldRaw = extractHeaderPath(oldHeader.substring(4));
            String newRaw = extractHeaderPath(newHeader.substring(4));
            boolean isNew = "/dev/null".equals(oldRaw);
            boolean isDeleted = "/dev/null".equals(newRaw);
            if (isNew && isDeleted) {
                throw new IllegalArgumentException("Patch cannot have both --- /dev/null and +++ /dev/null");
            }
            String oldPath = isNew ? oldRaw : stripDiffPrefix(oldRaw, "a/");
            String newPath = isDeleted ? newRaw : stripDiffPrefix(newRaw, "b/");

            List<Hunk> hunks = new ArrayList<>();
            while (i < n && !rawLines[i].startsWith("diff --git ") && !rawLines[i].startsWith("--- ")) {
                String hLine = rawLines[i];
                if (hLine.isBlank()) {
                    i++;
                    continue;
                }
                Matcher m = HUNK_HEADER.matcher(hLine);
                if (!m.matches()) {
                    throw new IllegalArgumentException("Malformed hunk header: " + hLine);
                }
                i++;

                int oldStart = Integer.parseInt(m.group(1));
                int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                int newStart = Integer.parseInt(m.group(3));
                int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));

                List<HunkLine> hunkLines = new ArrayList<>();
                int seenOld = 0;
                int seenNew = 0;
                boolean noNewlineAtEnd = false;

                while (i < n && (seenOld < oldCount || seenNew < newCount)) {
                    String bodyLine = rawLines[i];
                    if (bodyLine.startsWith("@@ ") || bodyLine.startsWith("diff --git ")
                            || bodyLine.startsWith("--- ")) {
                        break;
                    }
                    if (bodyLine.startsWith("\\ No newline at end of file")) {
                        noNewlineAtEnd = true;
                        i++;
                        continue;
                    }
                    if (bodyLine.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Malformed empty line without diff prefix inside hunk");
                    }
                    char prefix = bodyLine.charAt(0);
                    String content = bodyLine.substring(1);
                    switch (prefix) {
                        case ' ' -> {
                            hunkLines.add(new HunkLine(HunkOp.CONTEXT, content));
                            seenOld++;
                            seenNew++;
                        }
                        case '-' -> {
                            hunkLines.add(new HunkLine(HunkOp.DELETE, content));
                            seenOld++;
                        }
                        case '+' -> {
                            hunkLines.add(new HunkLine(HunkOp.ADD, content));
                            seenNew++;
                        }
                        default -> throw new IllegalArgumentException(
                                "Invalid hunk line prefix '" + prefix + "' in: " + bodyLine);
                    }
                    i++;
                }

                // Consume optional trailing "\ No newline at end of file"
                if (i < n && rawLines[i].startsWith("\\ No newline at end of file")) {
                    noNewlineAtEnd = true;
                    i++;
                }

                if (seenOld != oldCount || seenNew != newCount) {
                    throw new IllegalArgumentException(
                            "Hunk line count mismatch: expected -" + oldCount + " +" + newCount
                                    + ", got -" + seenOld + " +" + seenNew);
                }
                hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, hunkLines, noNewlineAtEnd));
            }

            if (hunks.isEmpty()) {
                throw new IllegalArgumentException("File patch has no hunks: "
                        + (isDeleted ? oldPath : newPath));
            }

            patches.add(new FilePatch(oldPath, newPath, isNew, isDeleted, hunks));
        }
        return patches;
    }

    private static String applyHunks(
            List<String> origLines,
            boolean origTrailingNewline,
            List<Hunk> hunks,
            String path) {

        List<String> result = new ArrayList<>();
        int cursor = 0;
        boolean trailingNewline = origTrailingNewline || origLines.isEmpty();

        for (Hunk hunk : hunks) {
            int startIdx = hunk.oldCount() == 0 ? hunk.oldStart() : hunk.oldStart() - 1;
            if (startIdx < cursor || startIdx > origLines.size()) {
                throw new IllegalArgumentException(
                        "Hunk out of bounds or overlapping in " + path
                                + " at line " + hunk.oldStart());
            }
            while (cursor < startIdx) {
                result.add(origLines.get(cursor++));
            }

            for (HunkLine hl : hunk.lines()) {
                switch (hl.op()) {
                    case CONTEXT -> {
                        if (cursor >= origLines.size() || !origLines.get(cursor).equals(hl.text())) {
                            String actual = cursor < origLines.size() ? origLines.get(cursor) : "<EOF>";
                            throw new IllegalArgumentException(
                                    "Context mismatch in " + path + " at line " + (cursor + 1)
                                            + ": expected '" + hl.text() + "', found '" + actual + "'");
                        }
                        result.add(origLines.get(cursor++));
                    }
                    case DELETE -> {
                        if (cursor >= origLines.size() || !origLines.get(cursor).equals(hl.text())) {
                            String actual = cursor < origLines.size() ? origLines.get(cursor) : "<EOF>";
                            throw new IllegalArgumentException(
                                    "Deletion mismatch in " + path + " at line " + (cursor + 1)
                                            + ": expected '" + hl.text() + "', found '" + actual + "'");
                        }
                        cursor++;
                    }
                    case ADD -> result.add(hl.text());
                }
            }

            if (hunk.noNewlineAtEnd()) {
                trailingNewline = false;
            }
        }

        while (cursor < origLines.size()) {
            result.add(origLines.get(cursor++));
        }

        if (result.isEmpty()) {
            return "";
        }
        String joined = String.join("\n", result);
        return trailingNewline ? joined + "\n" : joined;
    }

    private static List<String> splitLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        String normalized = content.replace("\r\n", "\n");
        String[] parts = normalized.split("\n", -1);
        int len = parts.length;
        if (len > 0 && parts[len - 1].isEmpty()) {
            len--;
        }
        List<String> lines = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            lines.add(parts[i]);
        }
        return lines;
    }

    private static String extractHeaderPath(String raw) {
        int tabIdx = raw.indexOf('\t');
        String cleaned = tabIdx >= 0 ? raw.substring(0, tabIdx) : raw;
        return cleaned.trim();
    }

    private static String stripDiffPrefix(String path, String prefix) {
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static boolean isSafeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.startsWith("\\")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
