package com.yapimaru.plugin.logic;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.zip.GZIPInputStream;

public class MergedLogIterator implements Iterator<LogLine>, AutoCloseable {

    private final List<BufferedReader> readers;
    private final List<LogLine> nextLines;

    public MergedLogIterator(List<File> files) throws IOException {
        this.readers = new ArrayList<>();
        this.nextLines = new ArrayList<>();

        for (File file : files) {
            BufferedReader reader;
            if (file.getName().endsWith(".gz")) {
                reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8));
            } else {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            }
            readers.add(reader);
            nextLines.add(readNextLineFrom(reader));
        }
    }

    @Override
    public boolean hasNext() {
        for (LogLine line : nextLines) {
            if (line != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public LogLine next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        int oldestIndex = -1;
        LogLine oldestLine = null;

        for (int i = 0; i < nextLines.size(); i++) {
            LogLine currentLine = nextLines.get(i);
            if (currentLine != null && !currentLine.isContentOnly()) {
                if (oldestLine == null || currentLine.compareTo(oldestLine) < 0) {
                    oldestLine = currentLine;
                    oldestIndex = i;
                }
            }
        }

        if (oldestIndex == -1) {
            for (int i = 0; i < nextLines.size(); i++) {
                if(nextLines.get(i) != null) {
                    oldestLine = nextLines.get(i);
                    oldestIndex = i;
                    break;
                }
            }
        }

        if (oldestLine == null || oldestIndex == -1) {
            throw new NoSuchElementException();
        }

        BufferedReader reader = readers.get(oldestIndex);
        nextLines.set(oldestIndex, readNextLineFrom(reader));

        while (true) {
            LogLine peekedLine = nextLines.get(oldestIndex);
            if (peekedLine != null && peekedLine.isContentOnly()) {
                oldestLine.merge(peekedLine);
                nextLines.set(oldestIndex, readNextLineFrom(reader));
            } else {
                break;
            }
        }

        return oldestLine;
    }

    private LogLine readNextLineFrom(BufferedReader reader) {
        try {
            String lineStr = reader.readLine();
            if (lineStr != null) {
                return LogLine.fromString(lineStr);
            }
        } catch (IOException e) {
            // Error, stop reading from this reader
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        for (BufferedReader reader : readers) {
            try {
                reader.close();
            } catch (IOException e) {
                // close quietly
            }
        }
    }
}