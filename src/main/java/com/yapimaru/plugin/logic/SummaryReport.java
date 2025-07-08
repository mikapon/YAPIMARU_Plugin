package com.yapimaru.plugin.logic;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class SummaryReport {
    private LocalDateTime executionTime = LocalDateTime.now();
    private long durationMillis = 0;
    private String reason;
    private List<String> targetLogFiles;
    private int totalSessions;

    public void setExecutionTime(long durationMillis) { this.durationMillis = durationMillis; }
    public void setReason(String reason) { this.reason = reason; }
    public void setTargetLogFiles(List<String> targetLogFiles) { this.targetLogFiles = targetLogFiles; }
    public void setTotalSessions(int totalSessions) { this.totalSessions = totalSessions; }

    public String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Log Add Summary ---\n");
        sb.append("処理日時: ").append(executionTime.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))).append("\n");
        sb.append("処理時間: ").append(String.format("%.2f秒", durationMillis / 1000.0)).append("\n");
        if (reason != null && !reason.isEmpty()) {
            sb.append("実行理由: ").append(reason).append("\n");
        }
        sb.append("\n");
        sb.append("== 処理概要 ==\n");
        sb.append("対象ログファイル数: ").append(targetLogFiles != null ? targetLogFiles.size() : 0).append("件\n");
        sb.append("検出セッション数: ").append(totalSessions).append("件\n");

        sb.append("\n");
        sb.append("== 対象ファイルリスト ==\n");
        if (targetLogFiles != null) {
            targetLogFiles.forEach(file -> sb.append("- ").append(file).append("\n"));
        }

        sb.append("\n--- End of Report ---\n");
        return sb.toString();
    }
}