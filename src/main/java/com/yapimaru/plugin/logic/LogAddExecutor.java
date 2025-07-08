package com.yapimaru.plugin.logic;

import com.yapimaru.plugin.YAPIMARU_Plugin;
import com.yapimaru.plugin.data.ParticipantData;
import com.yapimaru.plugin.managers.ParticipantManager;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class LogAddExecutor {

    private final YAPIMARU_Plugin plugin;
    private final CommandSender sender;
    private final boolean isDryRun;
    private final String fromDateStr;
    private final String toDateStr;
    private final String reason;
    private final ParticipantManager participantManager;

    private final File logDir;
    private final File processedDir;
    private final File errorDir;
    private final File backupDir;
    private final File participantDir;
    private final File dischargeDir;

    private static final Pattern LOG_FILE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.log(\\.gz)?");
    private static final DateTimeFormatter BACKUP_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final DateTimeFormatter SUMMARY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public LogAddExecutor(YAPIMARU_Plugin plugin, CommandSender sender, boolean isDryRun, String fromDateStr, String toDateStr, String reason) {
        this.plugin = plugin;
        this.sender = sender;
        this.isDryRun = isDryRun;
        this.fromDateStr = fromDateStr;
        this.toDateStr = toDateStr;
        this.reason = reason;
        this.participantManager = plugin.getParticipantManager();

        this.logDir = new File(plugin.getServer().getWorldContainer(), "logs");
        File participantInfoDir = new File(plugin.getDataFolder(), "Participant_Information");
        this.processedDir = new File(participantInfoDir, "processed_logs");
        this.errorDir = new File(participantInfoDir, "error_logs");
        this.backupDir = new File(plugin.getDataFolder(), "backups");
        this.participantDir = new File(participantInfoDir, "participant");
        this.dischargeDir = new File(participantInfoDir, "discharge");
    }

    public void execute() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long startTime = System.currentTimeMillis();
                SummaryReport summary = new SummaryReport();
                summary.setReason(reason);

                try {
                    sender.sendMessage("§aログ処理を開始します... (Dry Run: " + isDryRun + ")");

                    setupDirectories();

                    List<File> targetFiles = findAndFilterLogFiles();
                    if (targetFiles.isEmpty()) {
                        sender.sendMessage("§e処理対象のログファイルが見つかりませんでした。");
                        return;
                    }
                    summary.setTargetLogFiles(targetFiles.stream().map(File::getName).collect(Collectors.toList()));

                    sender.sendMessage("§7ステップ1/5: サーバーセッションを特定中...");
                    List<List<File>> serverSessions = groupFilesBySession(targetFiles);
                    sender.sendMessage("§a... " + serverSessions.size() + "件のセッションを発見しました。");
                    summary.setTotalSessions(serverSessions.size());

                    if (!isDryRun) {
                        sender.sendMessage("§7ステップ2/5: データのバックアップを作成中...");
                        createBackup();
                        sender.sendMessage("§a... バックアップを作成しました。");
                    } else {
                        sender.sendMessage("§7ステップ2/5: バックアップ作成をスキップします (Dry Run)。");
                    }

                    sender.sendMessage("§7ステップ3/5: 各セッションのデータを処理中...");
                    for (int i = 0; i < serverSessions.size(); i++) {
                        sender.sendMessage("§7... セッション " + (i + 1) + "/" + serverSessions.size() + " を処理しています...");
                        LogSessionProcessor processor = new LogSessionProcessor(plugin, serverSessions.get(i));
                        Map<String, ParticipantData> sessionResult = processor.process();

                        if (!isDryRun) {
                            sessionResult.values().forEach(participantManager::addLogData);
                        }
                    }
                    sender.sendMessage("§a... 全セッションのデータ処理が完了しました。");

                    if (!isDryRun) {
                        participantManager.saveAllParticipantData();
                        participantManager.reloadAllParticipants();
                    }

                    sender.sendMessage("§7ステップ4/5: ファイルを整理中...");
                    if (!isDryRun) {
                        moveFiles(targetFiles, processedDir);
                    }
                    sender.sendMessage("§a... ファイルを整理しました。");

                    sender.sendMessage("§7ステップ5/5: サマリーレポートを生成中...");
                    summary.setExecutionTime(System.currentTimeMillis() - startTime);
                    generateSummaryReport(summary);
                    sender.sendMessage("§a... レポートを生成しました。");

                    String finalMessage = isDryRun ? "§bドライランが完了しました。実際の変更は行われていません。" : "§a全てのログ処理が完了しました。";
                    sender.sendMessage(finalMessage + " 詳細は logs/summary-" + LocalDate.now().format(SUMMARY_DATE_FORMAT) + ".txt を確認してください。");

                } catch (Exception e) {
                    sender.sendMessage("§cログ処理中に予期せぬエラーが発生しました。詳細はコンソールを確認してください。");
                    plugin.getLogger().log(Level.SEVERE, "ログ処理中にエラーが発生しました。", e);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void setupDirectories() {
        processedDir.mkdirs();
        errorDir.mkdirs();
        backupDir.mkdirs();
    }

    private List<File> findAndFilterLogFiles() throws IOException {
        if (!logDir.exists() || !logDir.isDirectory()) {
            sender.sendMessage("§c'logs'ディレクトリが見つかりません。");
            return Collections.emptyList();
        }
        List<File> allLogFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(logDir.toPath(), 1)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.endsWith(".log") || fileName.endsWith(".log.gz") || fileName.endsWith(".txt");
                    })
                    .map(Path::toFile)
                    .forEach(allLogFiles::add);
        }

        allLogFiles.sort(Comparator.comparing(LogAddExecutor::getFileTimestampSafe).thenComparingInt(LogAddExecutor::getFileIndex));

        LocalDate from = (fromDateStr != null) ? LocalDate.parse(fromDateStr) : null;
        LocalDate to = (toDateStr != null) ? LocalDate.parse(toDateStr) : null;

        if (from == null && to == null) return allLogFiles;

        return allLogFiles.stream()
                .filter(file -> {
                    LocalDate fileDate = getFileTimestampSafe(file);
                    if (fileDate == null) return false;
                    boolean afterFrom = (from == null) || !fileDate.isBefore(from);
                    boolean beforeTo = (to == null) || !fileDate.isAfter(to);
                    return afterFrom && beforeTo;
                })
                .collect(Collectors.toList());
    }

    private List<List<File>> groupFilesBySession(List<File> files) {
        List<List<File>> sessions = new ArrayList<>();
        if (!files.isEmpty()) {
            sessions.add(files);
        }
        return sessions;
    }

    private void createBackup() throws IOException {
        String timestamp = LocalDateTime.now().format(BACKUP_DATE_FORMAT);
        String backupFileName = "backup-" + timestamp + ".zip";
        File backupFile = new File(backupDir, backupFileName);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(backupFile))) {
            addDirectoryToZip(participantDir, "participant", zos);
            addDirectoryToZip(dischargeDir, "discharge", zos);
        }

        File[] backups = backupDir.listFiles((dir, name) -> name.endsWith(".zip"));
        if (backups != null && backups.length > plugin.getConfigManager().getMaxBackups()) {
            Arrays.sort(backups, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < backups.length - plugin.getConfigManager().getMaxBackups(); i++) {
                backups[i].delete();
            }
        }
    }

    private void addDirectoryToZip(File dir, String parentDirName, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    addDirectoryToZip(file, parentDirName + "/" + file.getName(), zos);
                    continue;
                }
                try (FileInputStream fis = new FileInputStream(file)) {
                    ZipEntry zipEntry = new ZipEntry(parentDirName + "/" + file.getName());
                    zos.putNextEntry(zipEntry);
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    private void moveFiles(List<File> files, File destDir) {
        for (File file : files) {
            try {
                Files.move(file.toPath(), new File(destDir, file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().warning(file.getName() + " の移動に失敗しました。");
            }
        }
    }

    private void generateSummaryReport(SummaryReport summary) {
        String date = LocalDate.now().format(SUMMARY_DATE_FORMAT);
        File reportFile = new File(logDir, "summary-" + date + ".txt");

        try (FileWriter writer = new FileWriter(reportFile, false)) {
            writer.write(summary.build());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "サマリーレポートの生成に失敗しました。", e);
        }
    }

    public static LocalDate getFileTimestampSafe(File file) {
        String fileName = file.getName().replace(".txt", ".log");
        Matcher matcher = LOG_FILE_PATTERN.matcher(fileName);
        if (matcher.find()) {
            try { return LocalDate.parse(matcher.group(1)); } catch (Exception e) { return null; }
        }
        if (fileName.equalsIgnoreCase("latest.log")) return LocalDate.now();
        return null;
    }

    public static int getFileIndex(File file) {
        String fileName = file.getName().replace(".txt", ".log");
        Matcher matcher = LOG_FILE_PATTERN.matcher(fileName);
        if (matcher.find()) {
            try { return Integer.parseInt(matcher.group(2)); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }
}