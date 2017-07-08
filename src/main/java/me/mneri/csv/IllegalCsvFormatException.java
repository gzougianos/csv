package me.mneri.csv;

public class IllegalCsvFormatException extends CsvException {
    IllegalCsvFormatException(int line, String message) {
        super(String.format("Error at line %d: %s", line, message));
    }
}
