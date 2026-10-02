package com.conversive.aep.cost;

import com.conversive.aep.cost.persistence.BudgetRepository;
import com.conversive.aep.observability.AepMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
}
