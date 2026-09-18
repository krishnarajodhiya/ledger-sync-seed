package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryDocumentStore implements DocumentStore {

    private final List<NormalizedTxn> rows = new ArrayList<>();

    @Override
    public void save(NormalizedTxn txn) {
        rows.add(txn);
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(
            String accountLast4,
            YearMonth month) {

        return rows.stream()
                .filter(t -> t.accountLast4().equals(accountLast4))
                .filter(t -> YearMonth.from(t.occurredAt()).equals(month))
                .sorted(java.util.Comparator.comparing(NormalizedTxn::occurredAt).reversed())
                .toList();
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(
            String accountLast4) {

        Map<Category, BigDecimal> totals =
                new EnumMap<>(Category.class);

        for (Category category : Category.values()) {
            totals.put(category, BigDecimal.ZERO.setScale(2));
        }

        for (NormalizedTxn txn : rows) {
            if (txn.accountLast4().equals(accountLast4)) {
                totals.put(
                        txn.category(),
                        totals.get(txn.category()).add(txn.amount())
                );
            }
        }

        return totals;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        return rows.stream()
                .filter(txn -> txn.sourceMessageIds().contains(messageId))
                .findFirst();
    }



    public List<NormalizedTxn> all() {
        return List.copyOf(rows);
    }
}
