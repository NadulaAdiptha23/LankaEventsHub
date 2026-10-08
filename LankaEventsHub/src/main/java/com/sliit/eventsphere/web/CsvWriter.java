package com.sliit.eventsphere.web;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

/**
 * Writes RFC 4180 CSV straight to the servlet response.
 *
 * There was an export button on the reporting page once. It did nothing,
 * and it was removed rather than left looking live. This is the real
 * thing, and the three details that make it real rather than a
 * string-join are:
 *
 *   1. ESCAPING. A field containing a comma, a quote or a newline is
 *      quoted, and quotes inside it are doubled. Event names and refund
 *      reasons are free text, so this is not hypothetical - one comma in
 *      an event name silently shifts every later column of that row.
 *
 *   2. FORMULA INJECTION. A field beginning with = + - or @ is prefixed
 *      with a single quote. Spreadsheets treat such a cell as a formula,
 *      and a user-supplied name of =HYPERLINK(...) then executes when an
 *      administrator opens the export. The data is untrusted; the
 *      spreadsheet does not know that.
 *
 *   3. ENCODING. A UTF-8 BOM is emitted, because Excel on Windows
 *      otherwise reads the file as the system code page and mangles every
 *      non-ASCII name in it.
 *
 * Written through the response's own writer rather than built into a
 * String first, so an export does not need to fit in memory twice.
 */
public final class CsvWriter {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CsvWriter() {
    }

    /**
     * Sets the content type, encoding and download filename, and writes
     * the byte-order mark. Call once, before the first row.
     */
    public static void prepare(HttpServletResponse response, String filename) throws IOException {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv; charset=UTF-8");
        // The quoted form is what stops a filename with a space being
        // truncated at the space by some browsers.
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + sanitizeFilename(filename) + "\"");
        // An export is a snapshot of live data; it must never be cached.
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write('﻿');
    }

    /** Writes one row, escaping each value. */
    public static void row(PrintWriter out, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(escape(values[i]));
        }
        // CRLF is what RFC 4180 specifies, and what Excel expects.
        out.write("\r\n");
    }

    static String escape(Object value) {
        if (value == null) {
            return "";
        }

        String text;
        if (value instanceof TemporalAccessor t) {
            text = TS.format(t);
        } else if (value instanceof BigDecimal d) {
            text = d.toPlainString();          // never 1.2E+3 in a spreadsheet
        } else {
            text = String.valueOf(value);
        }

        // Neutralise a cell the spreadsheet would otherwise execute.
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }

        boolean mustQuote = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;

        if (!mustQuote) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /**
     * Keeps a header-injection attempt or a stray quote out of the
     * Content-Disposition header. The filenames here are built in code,
     * but a header built by concatenation is worth hardening anyway.
     */
    static String sanitizeFilename(String name) {
        return name.replaceAll("[\\r\\n\"\\\\]", "_");
    }
}
