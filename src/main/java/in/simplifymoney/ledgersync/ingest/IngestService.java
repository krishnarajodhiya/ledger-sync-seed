
package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * This is the naive version. It parses each message on its own and saves
 * whatever comes back. It does not ask whether two messages describe the same
 * transaction, and it decides the category from the direction alone.
 */


public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);

        int parsed = 0;
        int skipped = 0;

        Set<String> seenTransactions = new HashSet<>();
        Map<String, ParsedTxn> lastObservations = new HashMap<>();

        for (RawMessage m : messages) {

            Optional<ParsedTxn> p = parsers.parse(m);

            if (p.isEmpty()) {
                System.out.printf(
                        "UNPARSED: id=%s | channel=%s | sender=%s | body=%s%n",
                        m.messageId(),
                        m.channel(),
                        m.sender(),
                        m.body()
                );

                skipped++;
                continue;
            }

            NormalizedTxn txn = toTransaction(p.get());

            // Instant-based fingerprint to deduplicate across IST and UTC channels
            String fingerprint = String.join("|",
                    txn.accountLast4(),
                    txn.occurredAt().toInstant().toString(),
                    txn.direction().toString(),
                    txn.amount().toPlainString(),
                    txn.merchant().trim().toUpperCase()
            );

            boolean fingerprintDuplicate =
                    !seenTransactions.add(fingerprint);

            boolean storeDuplicate =
                    store.containsEquivalent(txn);

            if (fingerprintDuplicate || storeDuplicate) {

                System.out.printf(
                        "SKIPPED: %s | %s | %s | %s | %s | fingerprint=%s store=%s%n",
                        txn.occurredAt(),
                        txn.accountLast4(),
                        txn.direction(),
                        txn.amount(),
                        txn.merchant(),
                        fingerprintDuplicate,
                        storeDuplicate
                );

                skipped++;
                continue;
            }

            // Gap Detection: Reconcile balance observations to infer unrecorded/missing transactions
            ParsedTxn currentParsed = p.get();
            if (currentParsed.statedBalance() != null) {
                String acct = currentParsed.accountLast4();
                ParsedTxn prevObs = lastObservations.get(acct);
                if (prevObs != null) {
                    java.math.BigDecimal expected = prevObs.statedBalance();
                    // Account for all intermediate transactions in store between previous and current observations
                    for (NormalizedTxn existing : store.all()) {
                        if (existing.accountLast4().equals(acct)
                                && existing.occurredAt().isAfter(prevObs.occurredAt())
                                && existing.occurredAt().isBefore(currentParsed.occurredAt())) {
                            if (existing.direction() == Direction.DEBIT) {
                                expected = expected.subtract(existing.amount());
                            } else {
                                expected = expected.add(existing.amount());
                            }
                        }
                    }
                    if (currentParsed.direction() == Direction.DEBIT) {
                        expected = expected.subtract(currentParsed.amount());
                    } else {
                        expected = expected.add(currentParsed.amount());
                    }

                    // Check if stated balance drops lower than expected (missing DEBIT) or rises higher (missing CREDIT)
                    java.math.BigDecimal gap = expected.subtract(currentParsed.statedBalance());
                    if (gap.compareTo(java.math.BigDecimal.ZERO) > 0) {
                        NormalizedTxn gapTxn = new NormalizedTxn(
                                acct,
                                currentParsed.occurredAt().minusSeconds(1),
                                Direction.DEBIT,
                                gap.setScale(2),
                                Category.SPEND,
                                "UNRECONCILED DEBIT",
                                List.of(m.messageId() + "-gap")
                        );
                        store.save(gapTxn);
                        parsed++;
                    } else if (gap.compareTo(java.math.BigDecimal.ZERO) < 0) {
                        NormalizedTxn gapTxn = new NormalizedTxn(
                                acct,
                                currentParsed.occurredAt().minusSeconds(1),
                                Direction.CREDIT,
                                gap.abs().setScale(2),
                                Category.INCOME,
                                "UNRECONCILED CREDIT",
                                List.of(m.messageId() + "-gap")
                        );
                        store.save(gapTxn);
                        parsed++;
                    }
                }
                lastObservations.put(acct, currentParsed);
            }

            store.save(txn);

            if (store instanceof
                    in.simplifymoney.ledgersync.store.SqlLedgerStore sqlStore) {

                sqlStore.saveBalanceObservation(p.get());
            }

            parsed++;
        }

        return new Stats(
                messages.size(),
                parsed,
                skipped
        );
    }

    public static List<RawMessage> readCorpus(Path corpus)
            throws IOException {

        List<RawMessage> out = new ArrayList<>();

        try (Stream<String> lines = Files.lines(corpus)) {

            for (String line :
                    (Iterable<String>) lines
                            .filter(s -> !s.isBlank())::iterator) {

                Map<String, Object> o = Json.parseObject(line);

                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse(
                                (String) o.get("received_at")
                        ),
                        (String) o.get("device_id"),
                        (String) o.get("body")
                ));
            }
        }

        return out;
    }

    private NormalizedTxn toTransaction(ParsedTxn p) {

        Category c;

        String merchant = p.merchant().toUpperCase();

        if (merchant.contains("PARAG KAPOOR")) {
            c = Category.TRANSFER;

        } else if (p.direction() == Direction.CREDIT) {
            c = Category.INCOME;

        } else if (
                p.direction() == Direction.DEBIT
                        && p.amount().compareTo(
                                new java.math.BigDecimal("100.00")
                        ) <= 0
                        && merchant.contains("UPI/")
        ) {
            c = Category.MICRO;

        } else {
            c = Category.SPEND;
        }

        return new NormalizedTxn(
                p.accountLast4(),
                p.occurredAt(),
                p.direction(),
                p.amount(),
                c,
                p.merchant(),
                List.of(p.sourceMessageId())
        );
    }

    public record Stats(
            int messagesRead,
            int transactionsWritten,
            int messagesSkipped
    ) {}
}