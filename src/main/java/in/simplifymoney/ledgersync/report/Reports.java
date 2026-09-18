package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {

        Map<String, Object> accounts = new LinkedHashMap<>();

        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4)
                .toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;
            int microCount = 0;

            for (NormalizedTxn t : ledger) {

                if (!t.accountLast4().equals(acct)) {
                    continue;
                }

                switch (t.category()) {

                    case SPEND ->
                            spend = spend.add(t.amount());

                    case INCOME ->
                            income = income.add(t.amount());

                    case MICRO -> {
                        microTotal = microTotal.add(t.amount());
                        microCount++;
                    }

                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) {
                            transferredOut = transferredOut.add(t.amount());
                        } else {
                            transferredIn = transferredIn.add(t.amount());
                        }
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();

            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());

            accounts.put(acct, a);
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);

        return doc;
    }

    public static Map<String, Object> ledgerDocument(
            List<NormalizedTxn> ledger) {

        List<Object> rows = ledger.stream().map(t -> {

            Map<String, Object> r = new LinkedHashMap<>();

            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());

            return (Object) r;

        }).toList();

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);

        return doc;
    }

    public static Map<String, Object> reconciliation(
            List<NormalizedTxn> ledger,
            List<in.simplifymoney.ledgersync.store.BalanceObservation> observations) {

        List<Object> discrepancies = new java.util.ArrayList<>();

        Map<String, in.simplifymoney.ledgersync.store.BalanceObservation> previous =
                new java.util.HashMap<>();

        for (var current : observations) {
            var prior = previous.get(current.accountLast4());

            if (prior != null) {
                BigDecimal expected = prior.statedBalance();

                // Account for all intermediate transactions in ledger occurring between prior and current balance observations
                for (NormalizedTxn t : ledger) {
                    if (t.accountLast4().equals(current.accountLast4())
                            && t.occurredAt().isAfter(prior.occurredAt())
                            && t.occurredAt().isBefore(current.occurredAt())) {
                        if (t.direction() == Direction.DEBIT) {
                            expected = expected.subtract(t.amount());
                        } else {
                            expected = expected.add(t.amount());
                        }
                    }
                }

                // Apply current observation transaction amount
                if ("DEBIT".equals(current.direction())) {
                    expected = expected.subtract(current.amount());
                } else {
                    expected = expected.add(current.amount());
                }

                expected = expected.setScale(2);

                if (expected.compareTo(current.statedBalance()) != 0) {
                    Map<String, Object> discrepancy = new LinkedHashMap<>();

                    discrepancy.put("account_last4", current.accountLast4());
                    discrepancy.put("occurred_at", current.occurredAt().toString());
                    discrepancy.put("amount", current.amount().toPlainString());
                    discrepancy.put("expected_balance", expected.toPlainString());
                    discrepancy.put("stated_balance",
                            current.statedBalance().toPlainString());
                    discrepancy.put("note",
                            "Stated balance does not match the previous "
                                    + "stated balance after applying this transaction.");

                    discrepancies.add(discrepancy);
                }
            }

            previous.put(current.accountLast4(), current);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("discrepancies", discrepancies);
        return result;
    }

    public static Map<Category, BigDecimal> byCategory(
            List<NormalizedTxn> ledger) {

        Map<Category, BigDecimal> out = new LinkedHashMap<>();

        for (Category c : Category.values()) {
            out.put(c, ZERO);
        }

        for (NormalizedTxn t : ledger) {
            out.put(
                    t.category(),
                    out.get(t.category()).add(t.amount())
            );
        }

        return out;
    }
}