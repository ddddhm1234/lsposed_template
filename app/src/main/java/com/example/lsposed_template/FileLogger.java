package com.example.lsposed_template;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class FileLogger {
    private static final long MAX_LOG_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_BACKUP_COUNT = 3;
    private static final String LOG_DIR_NAME = "lsp_logs";

    private final File logFile;
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private FileLogger(File logFile) {
        this.logFile = logFile;
    }

    static FileLogger create(Context context, String processName) {
        File dir = context.getExternalFilesDir(LOG_DIR_NAME);
        if (!ensureDir(dir)) {
            File filesDir = context.getFilesDir();
            dir = filesDir != null ? new File(filesDir, LOG_DIR_NAME) : null;
        }
        if (!ensureDir(dir)) {
            Log.e(MainHook.TAG, "failed to create log dir: " + dir);
            return null;
        }

        FileLogger logger = new FileLogger(new File(dir, buildLogFileName(context, processName)));
        logger.writeJava(Log.INFO,
                MainHook.TAG,
                "FileLogger",
                0,
                "file logger initialized path=" + logger.logFile.getAbsolutePath());
        return logger;
    }

    File getLogFile() {
        return logFile;
    }

    void writeJava(int priority, String tag, String file, int line, String message) {
        writeLine(priority, tag, file, line, message);
    }

    public void writeFromNative(int priority, String tag, String file, int line, String message) {
        writeLine(priority, tag, file, line, message);
    }

    private synchronized void writeLine(int priority,
                                        String tag,
                                        String file,
                                        int line,
                                        String message) {
        rotateIfNeeded();

        StringBuilder lineBuilder = new StringBuilder(256);
        lineBuilder.append(timeFormat.format(new Date()))
                .append(" pid=").append(Process.myPid())
                .append(" tid=").append(Process.myTid())
                .append(' ')
                .append(priorityToChar(priority))
                .append('/')
                .append(tag != null ? tag : "NO_TAG");

        if (file != null && !file.isEmpty()) {
            lineBuilder.append(' ').append(file);
            if (line > 0) {
                lineBuilder.append(':').append(line);
            }
        }

        lineBuilder.append(" - ")
                .append(message != null ? message : "")
                .append('\n');

        byte[] bytes = lineBuilder.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(logFile, true)) {
            output.write(bytes);
        } catch (IOException e) {
            Log.e(MainHook.TAG, "failed to write file log: " + logFile.getAbsolutePath(), e);
        }
    }

    private void rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_LOG_BYTES) {
            return;
        }

        File oldestBackup = backupFile(MAX_BACKUP_COUNT);
        if (oldestBackup.exists() && !oldestBackup.delete()) {
            Log.w(MainHook.TAG, "failed to delete oldest backup log: " + oldestBackup);
        }
        for (int i = MAX_BACKUP_COUNT - 1; i >= 1; i--) {
            File src = backupFile(i);
            File dst = backupFile(i + 1);
            if (src.exists() && !src.renameTo(dst)) {
                Log.w(MainHook.TAG, "failed to rotate log " + src + " -> " + dst);
            }
        }

        File firstBackup = backupFile(1);
        if (firstBackup.exists() && !firstBackup.delete()) {
            Log.w(MainHook.TAG, "failed to delete old backup log: " + firstBackup);
        }
        if (!logFile.renameTo(firstBackup)) {
            Log.w(MainHook.TAG, "failed to rotate current log: " + logFile);
        }
    }

    private File backupFile(int index) {
        return new File(logFile.getParentFile(), logFile.getName() + "." + index);
    }

    private static boolean ensureDir(File dir) {
        return dir != null && (dir.exists() || dir.mkdirs()) && dir.isDirectory();
    }

    private static String buildLogFileName(Context context, String processName) {
        String packageName = context.getPackageName();
        String suffix = "main";
        if (processName != null && !processName.equals(packageName)) {
            if (processName.startsWith(packageName + ":")) {
                suffix = processName.substring(packageName.length() + 1);
            } else {
                suffix = processName;
            }
        }
        return "native_" + sanitizeName(suffix) + ".log";
    }

    private static String sanitizeName(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }

        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch >= 'a' && ch <= 'z') ||
                    (ch >= 'A' && ch <= 'Z') ||
                    (ch >= '0' && ch <= '9') ||
                    ch == '_' ||
                    ch == '-') {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private static char priorityToChar(int priority) {
        switch (priority) {
            case Log.DEBUG:
                return 'D';
            case Log.INFO:
                return 'I';
            case Log.WARN:
                return 'W';
            case Log.ERROR:
                return 'E';
            default:
                return '?';
        }
    }
}
