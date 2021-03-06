/*
 * Copyright 2018 Massimo Neri <hello@mneri.me>
 *
 * This file is part of mneri/csv.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.mneri.csv;

import java.io.*;
import java.nio.charset.Charset;
import java.util.NoSuchElementException;

/**
 * Read csv streams and automatically transform lines into Java objects.
 *
 * @param <T> The type of the Java objects to read.
 * @author Massimo Neri &lt;<a href="mailto:hello@mneri.me">hello@mneri.me</a>&gt;
 */
public final class CsvReader<T> implements Closeable {
    //@formatter:off
    private static final byte SOL = 0; // Start of line
    private static final byte SOF = 1; // Start of field
    private static final byte QOT = 2; // Quotation
    private static final byte ESC = 3; // Escape
    private static final byte TXT = 4; // Text
    private static final byte CAR = 5; // Carriage return
    private static final byte EOL = 6; // End of line
    private static final byte EOF = 7; // End of file
    private static final byte ERR = 8; // Error


    private static final byte[][] TRANSITIONS = {
    //        *           "           ,           \r          \n          EOF
            { TXT       , QOT       , SOF       , CAR       , EOL       , EOF       },  // SOL
            { TXT       , QOT       , SOF       , CAR       , EOL       , EOF       },  // SOF
            { QOT       , ESC       , QOT       , QOT       , QOT       , ERR       },  // QOT
            { ERR       , QOT       , SOF       , CAR       , EOL       , EOF       },  // ESC
            { TXT       , TXT       , SOF       , CAR       , EOL       , EOF       },  // TXT
            { ERR       , ERR       , ERR       , ERR       , EOL       , ERR       }}; // CAR

    private static final byte NOP = 0; // No operation
    private static final byte APP = 1; // Append
    private static final byte MKF = 2; // Make field
    private static final byte MKL = 4; // Make line

    private static final byte[][] ACTIONS = {
    //        *           "           ,           \r          \n          EOF
            { APP       , NOP       , MKF       , NOP       , MKF | MKL , NOP       },  // SOL
            { APP       , NOP       , MKF       , NOP       , MKF | MKL , MKF | MKL },  // SOF
            { APP       , NOP       , APP       , APP       , APP       , NOP       },  // QOT
            { NOP       , APP       , MKF       , NOP       , MKF | MKL , MKF | MKL },  // ESC
            { APP       , APP       , MKF       , NOP       , MKF | MKL , MKF | MKL },  // TXT
            { NOP       , NOP       , NOP       , NOP       , MKF | MKL , NOP       }}; // CAR
    //@formatter:on

    //@formatter:off
    private static final int ELEMENT_NOT_READ = 0;
    private static final int ELEMENT_READ     = 1;
    private static final int NO_SUCH_ELEMENT  = 2;
    private static final int CLOSED           = 3;
    //@formatter:on

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private char[] buffer;
    private final char delimiter;
    private CsvDeserializer<T> deserializer;
    private RecyclableCsvLine line;
    private int lines;
    private int next;
    private final char quotation;
    private Reader reader;
    private int size;
    private int state = ELEMENT_NOT_READ;

    private CsvReader(Reader reader, CsvOptions options, CsvDeserializer<T> deserializer) {
        this.reader = reader;
        this.deserializer = deserializer;

        buffer = new char[DEFAULT_BUFFER_SIZE];
        line = new RecyclableCsvLine();

        delimiter = options.getDelimiter();
        quotation = options.getQuotation();
    }

    private void checkClosedState() {
        if (state == CLOSED) {
            throw new IllegalStateException("The reader is closed.");
        }
    }

    /**
     * Closes the stream and releases any system resources associated with it. Once the stream has been closed, further
     * {@link CsvReader#hasNext()}, {@link CsvReader#next()} and {@link CsvReader#skip(int)} invocations will throw an
     * {@link IOException}. Closing a previously closed stream has no effect.
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void close() throws IOException {
        if (state == CLOSED) {
            return;
        }

        state = CLOSED;

        buffer = null;
        deserializer = null;
        line = null;
        reader.close();
        reader = null;
    }

    private int columnOf(int charCode) {
        switch (charCode) {
            //@formatter:off
            case '\r': return 3;
            case '\n': return 4;
            case -1  : return 5; // EOF
            default  :
                if (charCode == delimiter) return 2;
                if (charCode == quotation) return 1;

                return 0;
            //@formatter:on
        }
    }

    /**
     * Returns {@code true} if the reader has more elements. (In other words, returns {@code true} if
     * {@link CsvReader#next()} would return an element rather than throwing an exception).
     *
     * @return {@code true} if the reader has more elements.
     * @throws CsvException if the csv is not properly formatted.
     * @throws IOException  if an I/O error occurs.
     */
    public boolean hasNext() throws CsvException, IOException {
        //@formatter:off
        if      (state == ELEMENT_READ)    { return true; }
        else if (state == NO_SUCH_ELEMENT) { return false; }
        //@formatter:on

        checkClosedState();

        byte row = SOL;
        int nextChar;

        do {
            nextChar = read();
            int column = columnOf(nextChar);
            int action = ACTIONS[row][column];

            if ((action & APP) != 0) {
                line.append((char) nextChar);
            } else if ((action & MKF) != 0) {
                line.markField();

                if ((action & MKL) != 0) {
                    lines++;
                    state = ELEMENT_READ;
                    return true;
                }
            }

            row = TRANSITIONS[row][column];
        } while (row < EOF);

        if (row == EOF) {
            state = NO_SUCH_ELEMENT;
            return false;
        }

        throw new UnexpectedCharacterException(lines, nextChar);
    }

    /**
     * Return the next element in the reader.
     *
     * @return The next element.
     * @throws CsvException if the csv is not properly formatted.
     * @throws IOException  if an I/O error occurs.
     */
    public T next() throws CsvException, IOException {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        try {
            T element = deserializer.deserialize(line);

            state = ELEMENT_NOT_READ;
            line.clear();

            return element;
        } catch (Exception e) {
            throw new CsvConversionException(line, e);
        }
    }

    /**
     * Opens a file for reading, returning a {@code CsvReader}. Bytes from the file are decoded into characters using
     * the default JVM charset. Reading commences at the beginning of the file.
     *
     * @param file         the file to open.
     * @param deserializer the deserializer used to convert csv lines into objects.
     * @param <T>          the type of the objects to read.
     * @return A new {@code CsvReader} to read the specified file.
     * @throws IOException if an I/O error occurs.
     */
    public static <T> CsvReader<T> open(File file, CsvDeserializer<T> deserializer) throws IOException {
        return open(file, CsvOptions.defaultOptions(), deserializer);
	}

	/**
	 * Opens a file for reading using {@link DefaultCsvDeserializer}, returning a {@code CsvReader}. Bytes from the file are
	 * decoded into characters using the default JVM charset. Reading commences at the beginning of the file.
	 * 
	 * @param file  the file to open.
	 * @param clazz The class of the objects.
	 * @param       <T> the type of the objects to read.
	 * @return A new {@code CsvReader} to read the specified file.
	 * @throws IOException if an I/O error occurs.
	 */
	public static <T> CsvReader<T> open(File file, Class<T> clazz) throws IOException {
		return open(file, CsvOptions.defaultOptions(), new DefaultCsvDeserializer<T>(clazz));
	}

	/**
	 * Opens a file for reading using {@link DefaultCsvDeserializer}, returning a {@code CsvReader}. Bytes from the file are
	 * decoded into characters using the default JVM charset. Reading commences at the beginning of the file.
	 *
	 * @param file    the file to open.
	 * @param options reading options.
	 * @param clazz   The class of the objects.
	 * @param         <T> the type of the objects to read.
	 * @return A new {@code CsvReader} to read the specified file.
	 * @throws IOException if an I/O error occurs.
	 */
	public static <T> CsvReader<T> open(File file, CsvOptions options, Class<T> clazz) throws IOException {
		return open(file, TextUtil.defaultCharset(), options, new DefaultCsvDeserializer<T>(clazz));
	}

	/**
	 * Opens a file for reading using {@link DefaultCsvDeserializer}, returning a {@code CsvReader}. Bytes from the file are
	 * decoded into characters using the specified charset. Reading commences at the beginning of the file.
	 *
	 * @param file    the file to open.
	 * @param charset the charset of the file.
	 * @param clazz   The class of the objects.
	 * @param         <T> the type of the objects to read.
	 * @return A new {@code CsvReader} to read the specified file.
	 * @throws IOException if an I/O error occurs.
	 */
	public static <T> CsvReader<T> open(File file, Charset charset, Class<T> clazz) throws IOException {
		return open(file, charset, CsvOptions.defaultOptions(), new DefaultCsvDeserializer<T>(clazz));
	}
	
    /**
     * Opens a file for reading, returning a {@code CsvReader}. Bytes from the file are decoded into characters using
     * the default JVM charset. Reading commences at the beginning of the file.
     *
     * @param file         the file to open.
     * @param options      reading options.
     * @param deserializer the deserializer used to convert csv lines into objects.
     * @param <T>          the type of the objects to read.
     * @return A new {@code CsvReader} to read the specified file.
     * @throws IOException if an I/O error occurs.
     */
    public static <T> CsvReader<T> open(File file, CsvOptions options, CsvDeserializer<T> deserializer)
            throws IOException {
        return open(file, TextUtil.defaultCharset(), options, deserializer);
    }

    /**
     * Opens a file for reading, returning a {@code CsvReader}. Bytes from the file are decoded into characters using
     * the specified charset. Reading commences at the beginning of the file.
     *
     * @param file         the file to open.
     * @param charset      the charset of the file.
     * @param deserializer the deserializer used to convert csv lines into objects.
     * @param <T>          the type of the objects to read.
     * @return A new {@code CsvReader} to read the specified file.
     * @throws IOException if an I/O error occurs.
     */
    public static <T> CsvReader<T> open(File file, Charset charset, CsvDeserializer<T> deserializer) throws IOException {
        return open(file, charset, CsvOptions.defaultOptions(), deserializer);
    }

    /**
     * Opens a file for reading, returning a {@code CsvReader}. Bytes from the file are decoded into characters using
     * the specified charset. Reading commences at the beginning of the file.
     *
     * @param file         the file to open.
     * @param charset      the charset of the file.
     * @param options      reading options.
     * @param deserializer the deserializer used to convert csv lines into objects.
     * @param <T>          the type of the objects to read.
     * @return A new {@code CsvReader} to read the specified file.
     * @throws IOException if an I/O error occurs.
     */
    public static <T> CsvReader<T> open(File file, Charset charset, CsvOptions options, CsvDeserializer<T> deserializer)
            throws IOException {
        return open(new InputStreamReader(new FileInputStream(file), charset), options, deserializer);
    }

    /**
     * Return a new {@code CsvReader} using the specified {@link Reader} for reading. Bytes from the file are decoded
     * into characters using the reader's charset. Reading commences at the point specified by the reader.
     *
     * @param reader       the {@link Reader} to read from.
     * @param deserializer the deserializer used to convert csv lines into objects.
     * @param <T>          the type of the objects to read.
     * @return A new {@code CsvReader} to read the specified file.
     */
    public static <T> CsvReader<T> open(Reader reader, CsvDeserializer<T> deserializer) {
        return open(reader, CsvOptions.defaultOptions(), deserializer);
    }

    /**
     * Return a new {@code CsvReader} using the specified {@link Reader} for reading. Bytes from the file are decoded
     * into characters using the reader's charset. Reading commences at the point specified by the reader.
     *
     * @param reader       the {@link Reader} to read from.
     * @param options      reading options.
     * @param deserializer the deserializer used to convert csv lines into objects.
     * @param <T>          the type of the objects to read.
     * @return A new {@code CsvReader} to read the specified file.
     */
    public static <T> CsvReader<T> open(Reader reader, CsvOptions options, CsvDeserializer<T> deserializer) {
        return new CsvReader<>(reader, options, deserializer);
    }

    private int read() throws IOException {
        if (next >= size) {
            if ((size = reader.read(buffer, 0, buffer.length)) < 0) {
                return -1;
            }

            next = 0;
        }

        return buffer[next++];
    }

    /**
     * Skip the next elements of the reader.
     *
     * @param n The number of elements to skip.
     * @throws CsvException if the csv is not properly formatted.
     * @throws IOException  if an I/O error occurs.
     */
    public void skip(int n) throws CsvException, IOException {
        checkClosedState();

        if (state == NO_SUCH_ELEMENT) {
            return;
        }

        int toSkip = n;

        if (state == ELEMENT_READ) {
            state = ELEMENT_NOT_READ;
            line.clear();

            if (--toSkip == 0) {
                return;
            }
        }

        byte row = SOL;
        int nextChar;

        do {
            nextChar = read();
            int column = columnOf(nextChar);
            int action = ACTIONS[row][column];

            if ((action & MKL) != 0) {
                lines++;

                if (--toSkip == 0) {
                    return;
                }

                row = SOL;
            } else {
                row = TRANSITIONS[row][column];
            }
        } while (row < EOF);

        if (row == EOF) {
            state = NO_SUCH_ELEMENT;
            return;
        }

        throw new UnexpectedCharacterException(lines, nextChar);
    }
}
