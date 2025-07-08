package com.yapimaru.plugin.logic;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogLine implements Comparable<LogLine> {

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})]");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private LocalTime timestamp;
    private String content;
    private final boolean isContentOnly;

    private LogLine(LocalTime timestamp, String content, boolean isContentOnly) {
        this.timestamp = timestamp;
        this.content = content;
        this.isContentOnly = isContentOnly;
    }

    public static LogLine fromString(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = TIMESTAMP_PATTERN.matcher(line);
        if (matcher.find()) {
            try {
                LocalTime time = LocalTime.parse(matcher.group(1), TIME_FORMATTER);
                String content = line.substring(matcher.end()).trim();
                return new LogLine(time, content, false);
            } catch (Exception e) {
                return new LogLine(null, line, true);
            }
        }
        return new LogLine(null, line, true);
    }

    public boolean isContentOnly() {
        return isContentOnly;
    }

    public void merge(LogLine nextLine) {
        if (nextLine.isContentOnly()) {
            this.content += "\n" + nextLine.getContent();
        }
    }

    public LocalTime getTimestamp() {
        return timestamp;
    }

    public String getContent() {
        return content;
    }

    @Override
    public int compareTo(LogLine other) {
        if (this.timestamp == null || other.timestamp == null) {
            return 0;
        }
        return this.timestamp.compareTo(other.timestamp);
    }
}