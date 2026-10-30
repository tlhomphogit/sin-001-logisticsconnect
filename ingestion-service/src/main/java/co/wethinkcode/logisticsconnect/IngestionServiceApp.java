package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class IngestionServiceApp {

    private static final Logger log = LoggerFactory.getLogger(IngestionServiceApp.class);
    public static final int DEFAULT_PORT = 7050;

    public static Javalin createApp(List<HubRecord> records) {
        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));
        app.get("/hubs", ctx -> ctx.json(records));

        return app;
    }

    public static void main(String[] args) {
        log.info("Starting IngestionServiceApp on port {}...", DEFAULT_PORT);

        HubCsvCleaner.IngestionResult result = HubCsvCleaner.loadFromClasspath();
        log.info("Ingestion completed: {} input rows, {} cleaned records, {} duplicates merged, {} conflicts, {} discarded",
            result.totalInputRows(),
            result.cleanedRecords().size(),
            result.duplicatesMerged().size(),
            result.duplicateConflicts().size(),
            result.discardedRecords().size());

        if (!result.duplicateConflicts().isEmpty()) {
            for (HubCsvCleaner.DuplicateConflict conflict : result.duplicateConflicts()) {
                log.warn("Conflict in entity [{}]: Retained winner {} (active={}), duplicate {} had active={}",
                    conflict.entityKey(), conflict.winnerId(), conflict.winnerActive(),
                    conflict.duplicateId(), conflict.duplicateActive());
            }
        }

        Javalin app = createApp(result.cleanedRecords());
        app.start(DEFAULT_PORT);
    }
}
