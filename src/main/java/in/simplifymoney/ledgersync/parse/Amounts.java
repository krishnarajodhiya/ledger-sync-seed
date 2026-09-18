package in.simplifymoney.ledgersync.parse;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rupee amounts as banks write them.
 *
 * Handles the prefixes we see in practice - "Rs.", "Rs ", "INR " - and strips
 * the thousands separators before handing back a BigDecimal.
 */
public final class Amounts {

    private Amounts() {}

    private static final Pattern AMOUNT =
            Pattern.compile(
        "(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)",
        Pattern.CASE_INSENSITIVE);

    // Match stated balance figures (excluding "Avl Limit" which represents credit card limits, not bank ledger balances)
    private static final Pattern BALANCE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})",
            Pattern.CASE_INSENSITIVE);

    /** The transaction amount: the first rupee figure in the message. */
    public static BigDecimal first(String body) {
        // INC-2026-09-11 Fix: Extract transaction amount cleanly without picking up stated balance figures
        Matcher m = AMOUNT.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    /** The balance the bank quoted, if it quoted one (for bank accounts, excluding credit limits). */
    public static BigDecimal statedBalance(String body) {
        Matcher m = BALANCE.matcher(body);
        if (!m.find()) return null;
        return toDecimal(m.group(1));
    }

    private static BigDecimal toDecimal(String raw) {
        return new BigDecimal(raw.replace(",", "")).setScale(2);
    }
}
