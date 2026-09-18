package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmailParser implements MessageParser {

    private static final Pattern TRANSACTION = Pattern.compile(
            "Date:\\s*(?<when>.+?)\\n"
                    + "Subject:.*?\\n\\n"
                    + "Dear Customer,\\n\\n"
                    + "Your account ending (?<acct>\\d{4}) has been "
                    + "(?<dir>debited|credited) with .*?\\.\\n"
                    + "Merchant / Remarks: (?<merchant>.+?)\\n",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter EMAIL_DATE =
            DateTimeFormatter.ofPattern(
                    "EEE, dd MMM yyyy HH:mm:ss xx",
                    Locale.ENGLISH);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher matcher = TRANSACTION.matcher(m.body());

        if (!matcher.find()) {
            return Optional.empty();
        }

        BigDecimal amount = Amounts.first(m.body());

        OffsetDateTime occurredAt;
        try {
            occurredAt = OffsetDateTime.parse(
                    matcher.group("when").trim(),
                    EMAIL_DATE);
        } catch (Exception e) {
            return Optional.empty();
        }

        if (amount == null) {
            return Optional.empty();
        }

        Direction direction =
                "debited".equalsIgnoreCase(matcher.group("dir"))
                        ? Direction.DEBIT
                        : Direction.CREDIT;

        return Optional.of(new ParsedTxn(
                matcher.group("acct"),
                occurredAt,
                direction,
                amount,
                matcher.group("merchant").trim(),
                null,
                m.messageId()));
    }
}