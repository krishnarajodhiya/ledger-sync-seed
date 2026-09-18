package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(
            SqlLedgerStore sql,
            DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        for (NormalizedTxn sqlTxn : sql.all()) {
            for (String messageId : sqlTxn.sourceMessageIds()) {
                var documentTxn = documents.byMessageId(messageId);

                if (documentTxn.isEmpty()) {
                    divergences.add(new Divergence(
                            "missing message " + messageId,
                            describe(sqlTxn),
                            "missing"
                    ));
                    continue;
                }

                NormalizedTxn docTxn = documentTxn.get();

                if (!same(sqlTxn, docTxn)) {
                    divergences.add(new Divergence(
                            "changed transaction " + messageId,
                            describe(sqlTxn),
                            describe(docTxn)
                    ));
                }
            }
        }

        return divergences;
    }

    private static boolean same(
            NormalizedTxn a,
            NormalizedTxn b) {

        return Objects.equals(a.accountLast4(), b.accountLast4())
                && Objects.equals(a.occurredAt(), b.occurredAt())
                && Objects.equals(a.direction(), b.direction())
                && Objects.equals(a.amount(), b.amount())
                && Objects.equals(a.category(), b.category())
                && Objects.equals(a.merchant(), b.merchant());
    }

    private static String describe(NormalizedTxn txn) {
        return "account=" + txn.accountLast4()
                + ", occurred_at=" + txn.occurredAt()
                + ", direction=" + txn.direction()
                + ", amount=" + txn.amount()
                + ", category=" + txn.category()
                + ", merchant=" + txn.merchant();
    }

    public record Divergence(
            String what,
            String inSql,
            String inDocuments) {}
}
