package co.wethinkcode.logisticsconnect;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HubCsvCleaner {

    private static final Logger log = LoggerFactory.getLogger(HubCsvCleaner.class);
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("(\\d+)");

    public record DuplicateConflict(
        String entityKey,
        String winnerId,
        Boolean winnerActive,
        String duplicateId,
        Boolean duplicateActive
    ) {}

    public record IngestionResult(
        int totalInputRows,
        List<HubRecord> cleanedRecords,
        List<String> duplicatesMerged,
        List<DuplicateConflict> duplicateConflicts,
        List<String> discardedRecords
    ) {}

    public static IngestionResult loadFromClasspath() {
        return loadFromClasspath("/hubs-global.csv");
    }

    public static IngestionResult loadFromClasspath(String resourcePath) {
        InputStream is = HubCsvCleaner.class.getResourceAsStream(resourcePath);
        if (is == null) {
            String alt = resourcePath.startsWith("/") ? resourcePath.substring(1) : "/" + resourcePath;
            is = HubCsvCleaner.class.getClassLoader().getResourceAsStream(alt);
        }
        if (is == null) {
            throw new IllegalStateException("Resource not found on classpath: " + resourcePath);
        }
        return clean(is);
    }

    public static IngestionResult clean(InputStream inputStream) {
        List<HubRecord> rawRecords = new ArrayList<>();
        int inputRowCount = 0;
        List<String> discarded = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            List<String[]> allRows = reader.readAll();
            if (allRows.isEmpty()) {
                return new IngestionResult(0, List.of(), List.of(), List.of(), List.of());
            }

            String[] header = allRows.get(0);
            int hubIdIdx = -1;
            int provinceIdx = -1;
            int sortingCenterIdx = -1;
            int activeIdx = -1;

            for (int i = 0; i < header.length; i++) {
                String col = header[i].trim().toLowerCase(Locale.ROOT).replace(" ", "_");
                if (col.equals("hub_id") || col.equals("hubid") || col.equals("id")) {
                    hubIdIdx = i;
                } else if (col.equals("province")) {
                    provinceIdx = i;
                } else if (col.equals("sorting_center") || col.equals("sortingcenter")) {
                    sortingCenterIdx = i;
                } else if (col.equals("active")) {
                    activeIdx = i;
                }
            }

            for (int r = 1; r < allRows.size(); r++) {
                String[] row = allRows.get(r);
                if (row == null || row.length == 0 || (row.length == 1 && row[0].trim().isEmpty())) {
                    continue; // Skip blank lines
                }
                inputRowCount++;

                String rawId = getField(row, hubIdIdx);
                String rawProvince = getField(row, provinceIdx);
                String rawCenter = getField(row, sortingCenterIdx);
                String rawActive = getField(row, activeIdx);

                String normalizedId = normalizeHubId(rawId);
                String normalizedProvince = normalizeProvince(rawProvince);
                String normalizedCenter = normalizeSortingCenter(rawCenter);
                Boolean normalizedActive = normalizeActive(rawActive);

                if (normalizedId == null) {
                    discarded.add("Row " + r + ": Missing hub_id");
                    continue;
                }

                rawRecords.add(new HubRecord(normalizedId, normalizedProvince, normalizedCenter, normalizedActive));
            }

        } catch (IOException | CsvException e) {
            throw new RuntimeException("Failed to read CSV input", e);
        }

        // Deduplication using one deterministic policy:
        // Group by entity identity: (province, sortingCenter).
        // Retain the record with the lowest canonical hub ID.
        // Report any conflicting active or field values among duplicates.
        Map<String, List<HubRecord>> groups = new LinkedHashMap<>();
        for (HubRecord record : rawRecords) {
            String key = buildEntityKey(record);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
        }

        List<HubRecord> cleaned = new ArrayList<>();
        List<String> duplicatesMerged = new ArrayList<>();
        List<DuplicateConflict> conflicts = new ArrayList<>();

        for (Map.Entry<String, List<HubRecord>> entry : groups.entrySet()) {
            List<HubRecord> candidates = entry.getValue();

            // Sort candidates by canonical hub ID (lowest ID first)
            candidates.sort((h1, h2) -> compareHubIds(h1.hubId(), h2.hubId()));

            HubRecord winner = candidates.get(0);
            cleaned.add(winner);

            if (candidates.size() > 1) {
                for (int i = 1; i < candidates.size(); i++) {
                    HubRecord dup = candidates.get(i);
                    duplicatesMerged.add(dup.hubId());

                    // Check for conflicting active status
                    if (dup.active() != null && winner.active() != null && !dup.active().equals(winner.active())) {
                        DuplicateConflict conflict = new DuplicateConflict(
                            entry.getKey(),
                            winner.hubId(),
                            winner.active(),
                            dup.hubId(),
                            dup.active()
                        );
                        conflicts.add(conflict);
                        log.warn("Conflicting duplicate status for entity [{}]: Retained {} (active={}), duplicate {} had conflicting active={}",
                            entry.getKey(), winner.hubId(), winner.active(), dup.hubId(), dup.active());
                    }
                }
            }
        }

        // Sort final list canonically by hub ID for deterministic output
        cleaned.sort((h1, h2) -> compareHubIds(h1.hubId(), h2.hubId()));

        return new IngestionResult(inputRowCount, cleaned, duplicatesMerged, conflicts, discarded);
    }

    private static String getField(String[] row, int index) {
        if (index >= 0 && index < row.length) {
            return row[index];
        }
        return null;
    }

    public static String buildEntityKey(HubRecord record) {
        if (record.province() != null && !record.province().isBlank()
                && record.sortingCenter() != null && !record.sortingCenter().isBlank()) {
            return "LOC:" + record.province().toLowerCase(Locale.ROOT) + "|" + record.sortingCenter().toLowerCase(Locale.ROOT);
        }
        return "ID:" + (record.hubId() != null ? record.hubId().toUpperCase(Locale.ROOT) : "");
    }

    public static int compareHubIds(String id1, String id2) {
        if (id1 == null && id2 == null) return 0;
        if (id1 == null) return 1;
        if (id2 == null) return -1;

        int num1 = extractNumber(id1);
        int num2 = extractNumber(id2);
        if (num1 != num2) {
            return Integer.compare(num1, num2);
        }
        return id1.compareToIgnoreCase(id2);
    }

    private static int extractNumber(String id) {
        Matcher matcher = NUMERIC_PATTERN.matcher(id);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return Integer.MAX_VALUE;
    }

    public static String normalizeHubId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return collapseSpaces(raw.trim()).toUpperCase(Locale.ROOT);
    }

    public static String normalizeProvince(String raw) {
        if (isPlaceholderOrEmpty(raw)) {
            return null;
        }
        String s = collapseSpaces(raw.trim());
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.equals("kwa-zulu natal") || lower.equals("kwazulu natal") || lower.equals("kwazulu-natal")) {
            return "KwaZulu-Natal";
        }
        return toTitleCase(s);
    }

    public static String normalizeSortingCenter(String raw) {
        if (isPlaceholderOrEmpty(raw)) {
            return null;
        }
        String s = collapseSpaces(raw.trim());
        return toTitleCase(s);
    }

    public static Boolean normalizeActive(String raw) {
        if (isPlaceholderOrEmpty(raw)) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "1", "y", "yes" -> Boolean.TRUE;
            case "false", "0", "n", "no" -> Boolean.FALSE;
            default -> null; // Placeholder or unrecognized -> represented as null
        };
    }

    public static boolean isPlaceholderOrEmpty(String val) {
        if (val == null) {
            return true;
        }
        String s = val.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() || s.equals("n/a") || s.equals("na") || s.equals("unknown")
                || s.equals("tbd") || s.equals("-") || s.equals("nan") || s.equals("null");
    }

    public static String collapseSpaces(String text) {
        if (text == null) return null;
        return text.trim().replaceAll("\\s+", " ");
    }

    public static String toTitleCase(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String[] parts = text.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (!part.isEmpty()) {
                if (part.contains("-")) {
                    String[] subParts = part.split("-");
                    StringBuilder subSb = new StringBuilder();
                    for (int j = 0; j < subParts.length; j++) {
                        if (!subParts[j].isEmpty()) {
                            subSb.append(Character.toUpperCase(subParts[j].charAt(0)))
                                 .append(subParts[j].substring(1).toLowerCase(Locale.ROOT));
                        }
                        if (j < subParts.length - 1) {
                            subSb.append("-");
                        }
                    }
                    sb.append(subSb);
                } else {
                    sb.append(Character.toUpperCase(part.charAt(0)))
                      .append(part.substring(1).toLowerCase(Locale.ROOT));
                }
            }
            if (i < parts.length - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }
}
