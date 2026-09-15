package com.aibusinessmanager.app.batch;

import com.aibusinessmanager.growth.api.GrowthService;
import com.aibusinessmanager.platform.tenancy.ActorContext;
import com.aibusinessmanager.platform.tenancy.TenantContext;
import com.aibusinessmanager.platform.tenant.TenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.UUID;

@Configuration
public class MetricsBatchConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsBatchConfig.class);

    private final TenantDirectory tenants;
    private final GrowthService growthService;

    public MetricsBatchConfig(TenantDirectory tenants, GrowthService growthService) {
        this.tenants = tenants;
        this.growthService = growthService;
    }

    @Bean
    Job metricsJob(JobRepository jobRepository, Step metricsStep) {
        return new JobBuilder("metricsJob", jobRepository).start(metricsStep).build();
    }

    @Bean
    Step metricsStep(JobRepository jobRepository, PlatformTransactionManager tx) {
        return new StepBuilder("metricsStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    tenants.findAll().forEach(t -> recalculateTenant(t.getId()));
                    return org.springframework.batch.infrastructure.repeat.RepeatStatus.FINISHED;
                }, tx)
                .build();
    }

    @Scheduled(cron = "${app.metrics.cron:0 15 3 * * *}")
    public void scheduledRecalculation() {
        tenants.findAll().forEach(t -> recalculateTenant(t.getId()));
    }

    private void recalculateTenant(UUID tenantId) {
        try (var ignored = TenantContext.open(tenantId)) {
            ActorContext.set(ActorContext.system());
            LocalDate end = LocalDate.now();
            growthService.recalculate(end.minusDays(90), end);
            log.info("Recalculated metrics for tenant {}", tenantId);
        } catch (Exception ex) {
            log.warn("Metrics job failed for {}: {}", tenantId, ex.getMessage());
        } finally {
            ActorContext.clear();
        }
    }
}
