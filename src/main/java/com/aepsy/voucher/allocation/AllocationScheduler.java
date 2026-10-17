package com.aepsy.voucher.allocation;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "voucher.allocation.enabled", havingValue = "true", matchIfMissing = true)
public class AllocationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AllocationScheduler.class);
    private final BatchRepository batches;
    private final BatchAllocationService allocator;
    private final MeterRegistry metrics;
    private final ThreadPoolExecutor workers;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    public AllocationScheduler(BatchRepository batches, BatchAllocationService allocator, MeterRegistry metrics,
                               @Value("${voucher.allocation.workers:2}") int workerCount) {
        if (workerCount < 1 || workerCount > 32) throw new IllegalArgumentException("Allocation workers must be between 1 and 32");
        this.batches = batches; this.allocator = allocator; this.metrics = metrics;
        this.workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), runnable -> new Thread(runnable, "voucher-allocation"));
        metrics.gauge("voucher.allocation.active", inFlight, Set::size);
    }

    @Scheduled(fixedDelayString = "${voucher.allocation.scan-interval-ms:100}")
    public void scan() {
        if (stopping) return;
        try {
            for (UUID campaign : batches.dueCampaigns()) {
                if (!inFlight.add(campaign)) continue;
                try { workers.execute(() -> allocate(campaign)); }
                catch (RejectedExecutionException full) { inFlight.remove(campaign); }
            }
        } catch (Exception failure) {
            metrics.counter("voucher.allocation.scan.failures").increment();
            LOG.warn("Allocation scan failed exception={}", failure.getClass().getSimpleName());
        }
    }

    private void allocate(UUID campaign) {
        long started = System.nanoTime();
        try {
            // Each call crosses the transactional proxy and commits before the next batch.
            for (int pass = 0; pass < 10 && !stopping; pass++) {
                var result = allocator.allocateNextBatch(campaign);
                if (result.isEmpty()) break;
                metrics.counter("voucher.allocation.batches").increment();
                metrics.counter("voucher.allocation.issued").increment(result.get().issued());
            }
        } catch (Exception failure) {
            metrics.counter("voucher.allocation.failures").increment();
            LOG.warn("Allocation rolled back campaignId={} exception={}", campaign, failure.getClass().getSimpleName());
        } finally {
            metrics.timer("voucher.allocation.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            inFlight.remove(campaign);
        }
    }

    @PreDestroy
    void shutdown() {
        stopping = true;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(15, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
