package com.sliit.eventsphere.web;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The export used to be a button that did nothing. These are the rules
 * that make it a real one - each is a way a naive string-join export
 * corrupts data or hands a spreadsheet something dangerous.
 */
class CsvWriterTest {

    @Test
    void plainValuesAreWrittenUnquoted() {
        assertEquals("hello", CsvWriter.escape("hello"));
        assertEquals("42", CsvWriter.escape(42));
    }

    @Test
    void nullBecomesAnEmptyFieldRatherThanTheWordNull() {
        assertEquals("", CsvWriter.escape(null));
    }

    @Test
    void aCommaForcesQuoting() {
        // One comma in an event name silently shifts every later column of
        // that row if this is not done.
        assertEquals("\"Colombo, Sri Lanka\"", CsvWriter.escape("Colombo, Sri Lanka"));
    }

    @Test
    void aQuoteIsDoubledAndTheFieldIsQuoted() {
        assertEquals("\"He said \"\"hello\"\"\"", CsvWriter.escape("He said \"hello\""));
    }

    @Test
    void aNewlineInsideAFieldForcesQuoting() {
        assertEquals("\"line one\nline two\"", CsvWriter.escape("line one\nline two"));
    }

    @Test
    void aFieldStartingWithAFormulaCharacterIsNeutralised() {
        // Otherwise the spreadsheet executes it, and the value is
        // user-supplied: an attendee can call themselves anything.
        assertTrue(CsvWriter.escape("=HYPERLINK(\"http://evil\")").startsWith("\"'="),
                "an = field must be prefixed AND quoted, since it also contains a quote");
        assertTrue(CsvWriter.escape("+1234").startsWith("'"));
        assertTrue(CsvWriter.escape("-1+2").startsWith("'"));
        assertTrue(CsvWriter.escape("@SUM(A1)").startsWith("'"));
    }

    @Test
    void aBigDecimalIsWrittenPlainlyAndNeverInScientificNotation() {
        assertEquals("1000.00", CsvWriter.escape(new BigDecimal("1000.00")));
        assertEquals("1200", CsvWriter.escape(new BigDecimal("1.2E+3")));
    }

    @Test
    void aTimestampIsWrittenInASortableFormat() {
        assertEquals("2026-09-15 14:30:00",
                CsvWriter.escape(LocalDateTime.of(2026, 9, 15, 14, 30, 0)));
    }

    @Test
    void rowsAreSeparatedByCrlfAsTheStandardRequires() {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        CsvWriter.row(out, "a", "b");
        CsvWriter.row(out, 1, 2);
        out.flush();
        assertEquals("a,b\r\n1,2\r\n", sw.toString());
    }

    @Test
    void aFilenameCannotInjectAHeader() {
        // CR and LF are two characters, so they become two underscores -
        // the point is that neither survives into the header, not that the
        // length is preserved.
        assertEquals("report__2026.csv", CsvWriter.sanitizeFilename("report\r\n2026.csv"));
        assertEquals("a_b_.csv", CsvWriter.sanitizeFilename("a\"b\\.csv"));
    }
}
