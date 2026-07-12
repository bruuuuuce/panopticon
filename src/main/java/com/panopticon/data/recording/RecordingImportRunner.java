package com.panopticon.data.recording;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * One-shot import mode:
 *
 * <pre>
 * java -jar panopticon.jar \
 *     --import-recording=recordings/panopticon-2026-07-13.jsonl \
 *     --import-datasource=demo-h2 \
 *     --import-table=panopticon_recordings \
 *     --spring.main.web-application-type=none
 * </pre>
 *
 * When {@code --import-recording} is present the app performs the import and
 * exits (0 on success, 1 on failure) instead of serving dashboards; without
 * it this runner is a no-op and normal startup proceeds. The last flag is
 * optional but recommended — it skips starting the web server for a pure CLI
 * run. Root-level property names, same Binder convention as
 * {@code --dashboards}/{@code --recording}.
 */
@Component
public class RecordingImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecordingImportRunner.class);

    private final Environment environment;
    private final RecordingImporter importer;
    private final ConfigurableApplicationContext context;

    public RecordingImportRunner(Environment environment, RecordingImporter importer, ConfigurableApplicationContext context) {
        this.environment = environment;
        this.importer = importer;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        String file = bind("import-recording");
        if (file == null) {
            return;
        }
        String datasource = bind("import-datasource");
        String table = bind("import-table");
        int exitCode;
        if (datasource == null || table == null) {
            log.error("--import-recording requires both --import-datasource and --import-table");
            exitCode = 2;
        } else {
            exitCode = doImport(file, datasource, table);
        }
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private int doImport(String file, String datasource, String table) {
        try {
            RecordingImporter.ImportReport report = importer.importFile(Path.of(file), datasource, table);
            log.info("Imported {} into {}.{}: {} inserted, {} already present, {} malformed (of {} lines)",
                    file, datasource, table, report.inserted(), report.duplicates(), report.malformed(), report.totalLines());
            return 0;
        } catch (RuntimeException e) {
            log.error("Import failed: {}", e.getMessage());
            return 1;
        }
    }

    private String bind(String property) {
        return Binder.get(environment).bind(property, String.class).orElse(null);
    }
}
