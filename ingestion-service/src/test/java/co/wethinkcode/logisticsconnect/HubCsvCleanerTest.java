package co.wethinkcode.logisticsconnect;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HubCsvCleanerTest {

    @Test
    void shouldCleanCompleteClasspathCsvWithExactFigures() {
        HubCsvCleaner.IngestionResult result = HubCsvCleaner.loadFromClasspath();

        // 18 data rows in hubs-global.csv
        assertEquals(18, result.totalInputRows(), "Total input data rows should be 18");

        // 7 duplicates merged
        assertEquals(7, result.duplicatesMerged().size(), "7 duplicate records should be merged");
        assertTrue(result.duplicatesMerged().containsAll(List.of("H-504", "H-505", "H-506", "H-510", "H-512", "H-515", "H-516")));

        // 0 records discarded (H-508 preserved with null province)
        assertEquals(0, result.discardedRecords().size(), "No records should be discarded");

        // Exactly 11 unique cleaned records
        assertEquals(11, result.cleanedRecords().size(), "Cleaned unique records count should be 11");

        // Exactly 1 conflict detected and reported (H-510 active=false vs H-500 active=true)
        assertEquals(1, result.duplicateConflicts().size(), "One duplicate conflict should be reported");
        HubCsvCleaner.DuplicateConflict conflict = result.duplicateConflicts().get(0);
        assertEquals("H-500", conflict.winnerId());
        assertEquals(Boolean.TRUE, conflict.winnerActive());
        assertEquals("H-510", conflict.duplicateId());
        assertEquals(Boolean.FALSE, conflict.duplicateActive());
    }

    @Test
    void shouldNormalizeWhitespaceAndCasing() {
        String csv = """
                hub_id, Province ,sorting_center,active
                h-501,western cape,Cape Town  Port,yes
                """;
        HubCsvCleaner.IngestionResult result = HubCsvCleaner.clean(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, result.cleanedRecords().size());
        HubRecord record = result.cleanedRecords().get(0);
        assertEquals("H-501", record.hubId(), "ID should be uppercase trimmed");
        assertEquals("Western Cape", record.province(), "Province should be title-cased");
        assertEquals("Cape Town Port", record.sortingCenter(), "Internal double space should be collapsed");
        assertEquals(Boolean.TRUE, record.active(), "'yes' should be normalized to true");
    }

    @Test
    void shouldNormalizeProvinceSpellingVariants() {
        String csv = """
                hub_id, Province ,sorting_center,active
                H-503,KwaZulu-Natal,Durban Harbour,1
                H-506,Kwa-Zulu Natal,Durban Harbour,1
                H-516,KwaZulu Natal,Durban Harbour,true
                """;
        HubCsvCleaner.IngestionResult result = HubCsvCleaner.clean(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        // All 3 describe the same facility; should deduplicate to lowest ID H-503 with canonical province name
        assertEquals(1, result.cleanedRecords().size());
        HubRecord record = result.cleanedRecords().get(0);
        assertEquals("H-503", record.hubId());
        assertEquals("KwaZulu-Natal", record.province(), "Should normalize all variants to KwaZulu-Natal");
        assertEquals("Durban Harbour", record.sortingCenter());
        assertEquals(Boolean.TRUE, record.active());
    }

    @Test
    void shouldPreserveMissingProvinceAsNullWithoutDiscarding() {
        String csv = """
                hub_id, Province ,sorting_center,active
                H-508,,Pretoria North,yes
                """;
        HubCsvCleaner.IngestionResult result = HubCsvCleaner.clean(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, result.cleanedRecords().size(), "H-508 should be preserved, not discarded");
        HubRecord record = result.cleanedRecords().get(0);
        assertEquals("H-508", record.hubId());
        assertNull(record.province(), "Missing province should be preserved as null without hallucinating data");
        assertEquals("Pretoria North", record.sortingCenter());
        assertEquals(Boolean.TRUE, record.active());
    }

    @Test
    void shouldRepresentUnknownAndPlaceholderActiveValuesAsNull() {
        String csv = """
                hub_id, Province ,sorting_center,active
                H-511,Limpopo,Polokwane Hub,unknown
                H-517,Northern Cape,Kimberley Hub,N/A
                H-518,Free State,Welkom Hub,TBD
                H-519,North West,Rustenburg Hub,-
                H-520,Gauteng,Midrand Hub,
                """;
        HubCsvCleaner.IngestionResult result = HubCsvCleaner.clean(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertEquals(5, result.cleanedRecords().size());
        for (HubRecord r : result.cleanedRecords()) {
            assertNull(r.active(), "Placeholder active status for " + r.hubId() + " should be null");
        }
    }

    @Test
    void shouldDeduplicateByRetainingLowestCanonicalHubIdAndReportingConflicts() {
        String csv = """
                hub_id, Province ,sorting_center,active
                H-515,Gauteng,Johannesburg Central,YES
                H-504,Gauteng,Johannesburg Central,true
                H-510,Gauteng,johannesburg central,FALSE
                H-500, Gauteng ,Johannesburg Central,Y
                """;
        HubCsvCleaner.IngestionResult result = HubCsvCleaner.clean(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, result.cleanedRecords().size());
        HubRecord winner = result.cleanedRecords().get(0);
        assertEquals("H-500", winner.hubId(), "Lowest canonical ID H-500 must win");
        assertEquals(Boolean.TRUE, winner.active(), "H-500 fields must be retained");

        assertEquals(1, result.duplicateConflicts().size(), "Conflicting active value from H-510 must be reported");
        assertEquals("H-510", result.duplicateConflicts().get(0).duplicateId());
        assertEquals(Boolean.FALSE, result.duplicateConflicts().get(0).duplicateActive());
    }

    @Test
    void shouldReturnRecordsDeterministicallySortedByHubId() {
        HubCsvCleaner.IngestionResult result = HubCsvCleaner.loadFromClasspath();

        List<String> ids = result.cleanedRecords().stream().map(HubRecord::hubId).toList();
        List<String> expectedIds = List.of(
                "H-500", "H-501", "H-502", "H-503", "H-507", "H-508", "H-509", "H-511", "H-513", "H-514", "H-517"
        );
        assertEquals(expectedIds, ids, "Cleaned records must be deterministically ordered by canonical hub ID");
    }

    @Test
    void shouldNotMergeDistinctHubsWhenProvinceIsMissingForBoth() {
        String csv = """
                hub_id, Province ,sorting_center,active
                H-508,,Pretoria North,yes
                H-599,,Pretoria North,no
                """;
        HubCsvCleaner.IngestionResult result = HubCsvCleaner.clean(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, result.cleanedRecords().size(), "Distinct hubs with missing province must not be merged");
        List<String> ids = result.cleanedRecords().stream().map(HubRecord::hubId).toList();
        assertEquals(List.of("H-508", "H-599"), ids);
    }

    @Test
    void shouldNotMergeDistinctHubsWhenSortingCenterIsMissingForBoth() {
        String csv = """
                hub_id, Province ,sorting_center,active
                H-601,Gauteng,,true
                H-602,Gauteng,,false
                """;
        HubCsvCleaner.IngestionResult result = HubCsvCleaner.clean(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, result.cleanedRecords().size(), "Distinct hubs with missing sorting center must not be merged");
        List<String> ids = result.cleanedRecords().stream().map(HubRecord::hubId).toList();
        assertEquals(List.of("H-601", "H-602"), ids);
    }

    @Test
    void shouldNotMergeDistinctHubsWhenBothLocationFieldsAreMissing() {
        String csv = """
                hub_id, Province ,sorting_center,active
                H-701,,,true
                H-702,,,false
                """;
        HubCsvCleaner.IngestionResult result = HubCsvCleaner.clean(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, result.cleanedRecords().size(), "Distinct hubs with both location fields missing must not be merged");
        List<String> ids = result.cleanedRecords().stream().map(HubRecord::hubId).toList();
        assertEquals(List.of("H-701", "H-702"), ids);
    }
}
