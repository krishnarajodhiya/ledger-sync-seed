package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.HdfcSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class HdfcSmsParserTest {

    @Test
    void parsesTransactionAmountNotAvailableBalance() {
        RawMessage message = new RawMessage(
                "test-water-can",
                "sms",
                HdfcSmsParser.SENDER,
                OffsetDateTime.parse("2026-09-10T10:00:00+05:30"),
                "device-1",
                "Rs.5 debited from a/c **4821 on 10-09-26 at 10:00 "
                        + "to UPI/WATER CAN. Avl Bal: Rs.92,213.10."
        );

        HdfcSmsParser parser = new HdfcSmsParser();
        Optional<ParsedTxn> result = parser.parse(message);

        assertTrue(result.isPresent());
        assertEquals("5.00", result.get().amount().toPlainString());
        assertEquals("4821", result.get().accountLast4());
    }
}
