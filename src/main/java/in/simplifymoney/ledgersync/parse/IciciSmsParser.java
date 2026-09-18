
package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) "
                    + "(?<dir>Dr|Cr) "
                    + "(?<amount>(?:INR|Rs\\.?)\\s*[0-9,]+(?:\\.[0-9]{1,2})?) "
                    + "on (?<when>\\d{2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>.+?) ref no",
            Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter V2_DATE =
            DateTimeFormatter.ofPattern(
                    "dd-MMM-yyyy HH:mm", Locale.ENGLISH);

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher v1 = V1.matcher(m.body());

        if (v1.find()) {
            BigDecimal amount = Amounts.first(m.body());
            OffsetDateTime at = Dates.ist(v1.group("when"));

            if (amount == null || at == null) {
                return Optional.empty();
            }

            Direction d = "debited".equalsIgnoreCase(v1.group("dir"))
                    ? Direction.DEBIT
                    : Direction.CREDIT;

            return Optional.of(new ParsedTxn(
                    v1.group("acct"),
                    at,
                    d,
                    amount,
                    v1.group("merchant").trim(),
                    Amounts.statedBalance(m.body()),
                    m.messageId()));
        }

        Matcher v2 = V2.matcher(m.body());

        if (v2.find()) {
            BigDecimal amount = Amounts.first(m.body());

            OffsetDateTime at;

            try {
                LocalDateTime localDateTime = LocalDateTime.parse(
                        v2.group("when"),
                        V2_DATE);

                at = localDateTime.atOffset(
                        ZoneOffset.ofHoursMinutes(5, 30));
            } catch (Exception e) {
                return Optional.empty();
            }

            if (amount == null || at == null) {
                return Optional.empty();
            }

            Direction d = "Dr".equalsIgnoreCase(v2.group("dir"))
                    ? Direction.DEBIT
                    : Direction.CREDIT;

            return Optional.of(new ParsedTxn(
                    v2.group("acct"),
                    at,
                    d,
                    amount,
                    v2.group("merchant").trim(),
                    Amounts.statedBalance(m.body()),
                    m.messageId()));
        }

        return Optional.empty();
    }
}