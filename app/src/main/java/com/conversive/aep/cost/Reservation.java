package com.conversive.aep.cost;

import com.conversive.aep.common.TenantId;
import java.math.BigDecimal;

public record Reservation(String id, TenantId tenantId, BigDecimal amountUsd) {
}
