package com.panopticon.data;

import com.panopticon.model.DataDefinition;
import com.panopticon.model.DataResult;
import com.panopticon.model.DataResultStatus;
import com.panopticon.model.DataSourceDefinition;
import com.panopticon.data.recording.DataRecorder;
import com.panopticon.data.recording.RecordedExecution;
import com.panopticon.registry.DataRegistry;
import com.panopticon.registry.DataSourceRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Owns the full lifecycle of resolving and running a data definition by id:
 * look it up in the {@link DataRegistry}, resolve its
 * {@link DataSourceDefinition}, dispatch to the right {@link DataProvider}
 * via {@link DataProviderRegistry}, and serve/populate the
 * {@link DataResultCache} around the whole thing.
 *
 * <p>This is provider-agnostic by design — it has no idea whether a given
 * data id means "run this SQL" or "search Jira" or anything else. That
 * knowledge lives entirely inside the resolved {@link DataProvider}. This is
 * the only path any provider execution can be reached through: there is no
 * endpoint that accepts ad hoc SQL/JQL/etc. from a client, only a data id
 * that must already resolve to a definition in {@code config/data}.
 */
@Service
public class DataEngine {

    private final DataRegistry dataRegistry;
    private final DataSourceRegistry dataSourceRegistry;
    private final DataProviderRegistry providerRegistry;
    private final DataResultCache resultCache;
    private final MeterRegistry meterRegistry;
    private final DataRecorder recorder;

    public DataEngine(
            DataRegistry dataRegistry,
            DataSourceRegistry dataSourceRegistry,
            DataProviderRegistry providerRegistry,
            DataResultCache resultCache,
            MeterRegistry meterRegistry,
            DataRecorder recorder) {
        this.dataRegistry = dataRegistry;
        this.dataSourceRegistry = dataSourceRegistry;
        this.providerRegistry = providerRegistry;
        this.resultCache = resultCache;
        this.meterRegistry = meterRegistry;
        this.recorder = recorder;
    }

    public DataResult execute(String dataId) {
        DataDefinition definition = dataRegistry.find(dataId)
                .orElseThrow(() -> new UnknownDataException(dataId));
        DataSourceDefinition datasource = dataSourceRegistry.find(definition.datasource())
                .orElseThrow(() -> new DataSourceRegistry.NoSuchDataSourceException(definition.datasource(), dataSourceRegistry.names()));
        DataProvider provider = providerRegistry.resolve(definition.provider());
        DataExecutionContext context = new DataExecutionContext(definition, datasource);

        return resultCache.getOrCompute(definition.id(), definition.cacheTtlSeconds(), () -> {
            // Timed here, inside the cache supplier, so the metric only counts real
            // provider executions — cache hits are counted by DataResultCache instead.
            long start = System.nanoTime();
            try {
                DataResult result = provider.execute(context);
                if (result.status() == DataResultStatus.ERROR) {
                    // Bridges the provider SPI's "return a result, even on failure" contract back into
                    // the exception-based flow the cache/controller/runtime-tracker already rely on.
                    throw new DataExecutionException(result.errorMessage());
                }
                recordExecution(definition.id(), "ok", start);
                recorder.record(RecordedExecution.success(definition.id(), datasource, result));
                return result;
            } catch (RuntimeException e) {
                recordExecution(definition.id(), "error", start);
                recorder.record(RecordedExecution.failure(
                        definition.id(), datasource, (System.nanoTime() - start) / 1_000_000, e.getMessage()));
                throw e;
            }
        });
    }

    private void recordExecution(String dataId, String outcome, long startNanos) {
        Timer.builder("panopticon.data.execution")
                .description("Provider executions of a data definition (cache misses only)")
                .tag("dataId", dataId)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
