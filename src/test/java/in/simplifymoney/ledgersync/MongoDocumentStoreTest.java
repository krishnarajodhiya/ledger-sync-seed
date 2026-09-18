package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.*;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MongoDocumentStoreTest {

    @Test
    void mongoCrudWorks() {
        try (MongoDocumentStore store = new MongoDocumentStore("mongodb://localhost:27017", "ledger_sync_test_" + System.nanoTime())) {
            NormalizedTxn txn = new NormalizedTxn(
                    "9999",
                    OffsetDateTime.parse("2026-09-18T10:00:00+05:30"),
                    Direction.DEBIT,
                    new BigDecimal("50.00"),
                    Category.MICRO,
                    "TEST MERCHANT",
                    List.of("mongo-test-001")
            );

            store.save(txn);

            assertTrue(store.byMessageId("mongo-test-001").isPresent());
            assertEquals(1, store.forAccountMonth(
                    "9999", YearMonth.of(2026, 9)).size());
            assertEquals(new BigDecimal("50.00"),
                    store.categoryTotals("9999").get(Category.MICRO));
        }
    }
}
