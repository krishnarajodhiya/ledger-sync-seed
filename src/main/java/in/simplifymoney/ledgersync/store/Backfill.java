package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        long read = 0;
        long written = 0;
        long skipped = 0;

        for (NormalizedTxn txn : source.all()) {
            read++;

            boolean alreadyExists = txn.sourceMessageIds()
                    .stream()
                    .anyMatch(id -> target.byMessageId(id).isPresent());

            if (alreadyExists) {
                skipped++;
                continue;
            }

            target.save(txn);
            written++;
        }

        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
