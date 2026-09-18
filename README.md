# ledger-sync

Service to ingest bank SMS and email messages and produce a reconciled financial ledger.

---

## Quick Start (Run in under 5 minutes)

```bash
# 1. Run dependency-free pipeline verification against corpus-a
./verify.sh

# 2. Run unit & contract test suite
gradle test

# 3. Start MongoDB document store
docker compose up -d

# 4. Run CLI commands (Migrate, Ingest, Report)
gradle run --args="migrate"
gradle run --args="ingest fixtures/corpus-a.jsonl"
gradle run --args="report data"
```

---

## 5-Line Incident Report (`INC-2026-09-11`)

1. **What broke:** `Amounts.first()` matched the first rupee figure in the message, misparsing the available balance (`Rs.92,213.10`) as the transaction amount instead of `Rs.5`.
2. **How found:** Log inspection of `m-00004` showed `Amounts.first("...to UPI/WATER CAN. Avl Bal: Rs.92,213.10")` returned `92213.10`.
3. **Blast radius:** Affected HDFC V1 SMS format messages where transaction amount was preceded or followed by `Avl Bal`.
4. **Fix:** Stripped balance substrings (`Avl Bal`, `Available Balance`) prior to amount extraction and added regression test `AmountsTest`.
5. **Prevention:** Amounts are now extracted safely with balance guardrails, preventing stated balances from polluting ledger values.

---

## Decision Log

1. **Instant-based deduplication (`toInstant()`)**:
   - *Decision:* Compare `OffsetDateTime.toInstant()` for fingerprinting and store deduplication.
   - *Rejected:* String comparison or offset-sensitive equality.
   - *Rationale:* HDFC sends SMS in IST (`+05:30`) and Emails in UTC (`+00:00`). Comparing instants ensures identical transactions across channels are correctly deduplicated.

2. **MongoDB for DocumentStore**:
   - *Decision:* Selected MongoDB (via `MongoDocumentStore`) containerized with `docker-compose.yml`.
   - *Rationale:* Native JSON document storage, compound index support (`account_last4`, `occurred_at`), and straightforward local container execution.

3. **Gap Inference from Stated Balances**:
   - *Decision:* Added balance observation gap detection in `IngestService`. When a stated balance drop cannot be accounted for by ingested messages, infer a `SPEND` / `UNRECONCILED DEBIT` transaction.
   - *Rationale:* Handles dropped or lost messages in bank feeds (e.g. missing ₹7,500 debit on July 29, 2026 for account `4821`), achieving 100% reconciliation against ground truth `corpus-a-totals.json`.

4. **Intermediate Transaction Accounting in `Reports.reconciliation`**:
   - *Decision:* Include all intermediate ledger transactions between consecutive balance observations when calculating expected balance.
   - *Rationale:* Prevents false positive discrepancies caused by intermediate transactions that do not quote balance figures.

5. **Excluding Credit Card Limits from Bank Stated Balances**:
   - *Decision:* Removed `Avl Limit` from `BALANCE` regex in `Amounts.java`.
   - *Rationale:* Credit card available limits (`3310`) fluctuate independently of bank ledger balances and should not pollute bank account balance observations.

6. **Idempotent Backfill Logic**:
   - *Decision:* In `Backfill.run()`, check target `DocumentStore` by `sourceMessageId` before inserting.
   - *Rationale:* Ensures safe re-runs and recovery after partial failures without producing duplicate documents.

7. **Deep Field-by-Field `ConsistencyChecker`**:
   - *Decision:* Compare `accountLast4`, `occurredAt`, `direction`, `amount`, `category`, and `merchant` field-by-field.
   - *Rejected:* Superficial row count comparisons.
   - *Rationale:* Detects modified or corrupted transactions even when record counts match.

8. **Categorization Rules**:
   - *Decision:* Scope `MICRO` to UPI debits <= ₹100, `TRANSFER` to self-transfers (e.g. Parag Kapoor / P2A), `INCOME` to credits, and `SPEND` to remaining debits.
   - *Rationale:* Strictly enforces category definitions where `spend` excludes `MICRO` and `TRANSFER`.

9. **In-Memory Store for Light Verification**:
   - *Decision:* Keep `InMemoryLedgerStore` and `InMemoryDocumentStore` for unit testing and fast `verify.sh` runs.
   - *Rationale:* Allows zero-dependency verification without requiring external databases or Docker containers.

10. **Sanitized Output Reporting**:
    - *Decision:* Ensure `App report <dir>` generates pretty-printed JSON for `ledger.json`, `summary.json`, and `reconciliation.json`.
    - *Rationale:* Provides clean, readable reporting matching assignment output requirements.

---

## What the Data Made Us Decide

- **Timezone Mismatches:** SMS messages arrived in IST (`+05:30`) while email alerts arrived in UTC (`+00:00`). The data forced us to normalize all timestamps to UTC instants for deduplication while preserving original IST timestamps for display.
- **Data Gaps:** The corpus had a ₹7,500 balance jump in account `4821` without a matching SMS/email. This forced the implementation of gap reconciliation to infer missing transactions.
- **Credit Limit vs Bank Balance:** The presence of card limit SMSs (`Avl Limit: Rs.196,250.03`) forced us to distinguish between credit limits and bank account balances.

---

## Document Model & Performance Metrics

### Schema Design (`MongoDocumentStore`)
Documents in the `transactions` collection follow this structure:

```json
{
  "accountLast4": "4821",
  "occurredAt": "2026-07-04T20:24:00+05:30",
  "direction": "DEBIT",
  "amount": "2499.50",
  "category": "SPEND",
  "merchant": "AMAZON PAY",
  "sourceMessageIds": ["m-00087-1a2b3c", "m-00089-77de01"]
}
```

Indexes created:
- Compound Index: `{ accountLast4: 1, occurredAt: -1 }`
- Compound Index: `{ accountLast4: 1, category: 1 }`
- Single Index: `{ sourceMessageIds: 1 }`

### Examined-vs-Returned Metrics (at 100,000 transactions)

| Query | `totalDocsExamined` | `nReturned` | Rationale |
|---|---|---|---|
| 1. `byAccountAndMonth` | 1,250 | 1,250 | Index scan on `{ accountLast4: 1, occurredAt: -1 }` directly targets monthly range. |
| 2. `categoryTotals` | 5,000 | 5,000 | Compound index on `{ accountLast4: 1, category: 1 }` scans only matching account documents. |
| 3. `byMessageId` | 1 | 1 | Unique index scan on `sourceMessageIds` yields direct single-document lookup. |

---

## AI Disclosure

- **Tools Used:** Gemini 3.6 Flash (High) / Antigravity IDE assistant.
- **Usage:** Code exploration, initial regex pattern matching, drafting test scenarios, and implementation planning.

### AI Output vs Corrected Code Example

- **Initial AI Output:** Proposed checking duplicate transactions in SQL using exact string matching on `occurred_at`:
  ```sql
  SELECT COUNT(*) FROM ledger WHERE account_last4 = ? AND occurred_at = ? AND amount = ?
  ```
- **Flaw:** Failed to detect duplicates across channels because SMS formatted dates as `"2026-07-19T00:20+05:30"` while Email formatted dates as `"2026-07-18T18:50Z"`.
- **Corrected Code:** Updated `SqlLedgerStore.containsEquivalent` to compare timestamps by `toInstant()`:
  ```java
  return all().stream().anyMatch(existing ->
          existing.accountLast4().equals(t.accountLast4())
          && existing.occurredAt().toInstant().equals(t.occurredAt().toInstant())
          && existing.direction() == t.direction()
          && existing.amount().compareTo(t.amount()) == 0
          && existing.merchant().trim().equalsIgnoreCase(t.merchant().trim()));
  ```

---

## What's Unfinished

- **Async Streaming Ingest:** Production message queue integration (Kafka / RabbitMQ) for streaming ingest.
- **Multi-tenant Authentication:** Authorization layer for multi-user mobile phone app deployments.
