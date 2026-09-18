package in.simplifymoney.ledgersync.store;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BalanceObservation(
        String accountLast4,
        OffsetDateTime occurredAt,
        BigDecimal amount,
        String direction,
        BigDecimal statedBalance,
        String sourceMessageId) {
}
