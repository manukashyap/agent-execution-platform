package com.conversive.aep.cost;

import com.conversive.aep.cost.persistence.BudgetRepository;
import com.conversive.aep.cost.persistence.BudgetRepository.OpenReservation;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Releases reservations whose call died between {@code tryReserve} and {@code confirm}/{@code cancel} (worker
 * crash, lost activity). Each one goes through {@link BudgetService#cancel}, so the execution cap and the tenant
 * budget are both released; a confirm that still arrives later records its spend (confirm accepts CANCELLED).
 */
public class BudgetReservationReaper {

    private static final Logger log = LoggerFactory.getLogger(BudgetReservationReaper.class);

    private final BudgetRepository repository;
    private final BudgetService budget;
    private final ReaperProperties props;

    public BudgetReservationReaper(BudgetRepository repository, BudgetService budget, ReaperProperties props) {
        this.repository = repository;
        this.budget = budget;
        this.props = props;
    }

    @Scheduled(initialDelayString = "${aep.cost.reaper.interval:60s}",
            fixedDelayString = "${aep.cost.reaper.interval:60s}")
    public void scheduled() {
        try {
            reapOnce();
        } catch (RuntimeException e) {
            log.warn("budget reservation reaper run failed; retrying next interval", e);
        }
    }

    /** Cancels one batch of abandoned reservations; returns how many it found. */
    public int reapOnce() {
        List<OpenReservation> abandoned = repository.findAbandoned(props.maxAge().toSeconds(), props.batchSize());
        for (OpenReservation r : abandoned) {
            budget.cancel(new Reservation(r.id().toString(), r.tenantId(), r.amountUsd()));
        }
        if (!abandoned.isEmpty()) {
            log.info("released {} budget reservations older than {}", abandoned.size(), props.maxAge());
        }
        return abandoned.size();
    }
}
