package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.bson.Document;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    private final MongoClient client;
    private final MongoCollection<Document> collection;

    public MongoDocumentStore() {
        this("mongodb://localhost:27017", "ledger_sync");
    }

    public MongoDocumentStore(String uri, String databaseName) {
        client = MongoClients.create(uri);
        MongoDatabase database = client.getDatabase(databaseName);
        collection = database.getCollection("transactions");

        // Compound index for Query 1 (byAccountAndMonth - newest first)
        collection.createIndex(new Document("account_last4", 1)
                .append("occurred_at", -1));

        // Index for Query 3 (byMessageId direct lookup)
        collection.createIndex(new Document("source_message_ids", 1));
    }

    @Override
    public void save(NormalizedTxn txn) {
        Document document = new Document()
                .append("account_last4", txn.accountLast4())
                .append("occurred_at", txn.occurredAt().toString())
                .append("direction", txn.direction().name())
                .append("amount", txn.amount().toPlainString())
                .append("category", txn.category().name())
                .append("merchant", txn.merchant())
                .append("source_message_ids", txn.sourceMessageIds());

        collection.insertOne(document);
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(
            String accountLast4,
            YearMonth month) {

        String start = month.atDay(1)
                .atStartOfDay()
                .atOffset(java.time.ZoneOffset.UTC)
                .toString();

        String end = month.plusMonths(1).atDay(1)
                .atStartOfDay()
                .atOffset(java.time.ZoneOffset.UTC)
                .toString();

        List<NormalizedTxn> result = new ArrayList<>();

        for (Document d : collection.find(and(
                eq("account_last4", accountLast4),
                gte("occurred_at", start),
                lt("occurred_at", end)
        )).sort(new Document("occurred_at", -1))) {
            result.add(fromDocument(d));
        }

        return result;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(
            String accountLast4) {

        Map<Category, BigDecimal> totals =
                new EnumMap<>(Category.class);

        for (Category category : Category.values()) {
            totals.put(category, BigDecimal.ZERO.setScale(2));
        }

        for (Document d : collection.find(
                eq("account_last4", accountLast4))) {

            Category category = Category.valueOf(d.getString("category"));
            BigDecimal amount =
                    new BigDecimal(d.getString("amount"));

            totals.put(category, totals.get(category).add(amount));
        }

        return totals;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document d = collection.find(
                eq("source_message_ids", messageId)).first();

        return d == null
                ? Optional.empty()
                : Optional.of(fromDocument(d));
    }

    private static NormalizedTxn fromDocument(Document d) {
        List<String> sourceIds =
                d.getList("source_message_ids", String.class);

        return new NormalizedTxn(
                d.getString("account_last4"),
                OffsetDateTime.parse(d.getString("occurred_at")),
                Direction.valueOf(d.getString("direction")),
                new BigDecimal(d.getString("amount")),
                Category.valueOf(d.getString("category")),
                d.getString("merchant"),
                sourceIds
        );
    }

    @Override
    public void close() {
        client.close();
    }
}
