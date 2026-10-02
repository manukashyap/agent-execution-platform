package com.conversive.aep.cost;

import com.conversive.aep.cost.persistence.BudgetRepository;
import com.conversive.aep.observability.AepMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class CostConfig {

    /** Supersedes {@link NoOpBudgetService}. */
    @Bean
    @Primary
    BudgetService jdbcBudgetService(BudgetRepository repository, PlatformTransactionManager transactionManager,
                                    CostProperties props, AepMetrics metrics) {
        return new JdbcBudgetService(repository, new TransactionTemplate(transactionManager), props, metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "aep.cost.reaper.enabled", matchIfMissing = true)
    BudgetReservationReaper budgetReservationReaper(BudgetRepository repository, BudgetService budget,
                                                    ReaperProperties props) {
        return new BudgetReservationReaper(repository, budget, props);
    }

    /** Turns on {@code @Scheduled} (the reaper is its first user). */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
    }
}
