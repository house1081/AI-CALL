package com.aicall.util;

import org.springframework.util.StringUtils;

/**
 * FS 所在主机 shell 差异（本机 Windows FS 不能用 Linux 的 mkdir -p / printf / wget）。
 */
public final class FsHostOs {

    public static final String LINUX = "linux";
    public static final String WINDOWS = "windows";

    private FsHostOs() {
    }

    public static boolean isWindows(String fsHostOs) {
        return WINDOWS.equalsIgnoreCase(StringUtils.hasText(fsHostOs) ? fsHostOs.trim() : LINUX);
    }

    public static String joinRemotePath(String dir, String fileName) {
        String d = dir.endsWith("/") || dir.endsWith("\\") ? dir.substring(0, dir.length() - 1) : dir;
        if (isWindowsPath(d)) {
            return d.replace('\\', '/') + "/" + fileName;
        }
        return d + "/" + fileName;
    }

    public static boolean isWindowsPath(String path) {
        return path.length() > 1 && path.charAt(1) == ':';
    }

    /**
     * 本机 wav 路径供 FS playback/broadcast 使用。
     * 禁止 {@code file://C:/...}：mod_dptools 会把 C 当 hostname 报 {@code not localhost}。
     */
    public static String normalizeLocalPlaybackPath(String path) {
        if (!StringUtils.hasText(path)) {
            return path;
        }
        String p = path.trim().replace('\\', '/');
        if (p.regionMatches(true, 0, "file://", 0, 7)) {
            p = p.substring(7);
        }
        return p;
    }

    public static String mkdirCommand(String fsHostOs, String dir) {
        if (isWindows(fsHostOs)) {
            String win = dir.replace('/', '\\');
            return "if not exist \"" + win + "\" mkdir \"" + win + "\"";
        }
        return "mkdir -p " + dir;
    }

    public static String curlDownloadCommand(String fsHostOs, String localPath, String httpUrl) {
        if (isWindows(fsHostOs)) {
            return "curl -fsSL -m 25 -o \"" + localPath + "\" \"" + httpUrl + "\"";
        }
        String escapedUrl = httpUrl.replace("'", "'\\''");
        return "curl -fsSL -m 25 -o " + localPath + " '" + escapedUrl
                + "' || wget -q -O " + localPath + " '" + escapedUrl + "'";
    }

    public static String deleteFilesCommand(String fsHostOs, String... paths) {
        if (isWindows(fsHostOs)) {
            StringBuilder sb = new StringBuilder("del /f /q");
            for (String p : paths) {
                sb.append(" \"").append(p.replace('/', '\\')).append("\"");
            }
            sb.append(" 2>nul");
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder("rm -f");
        for (String p : paths) {
            sb.append(" ").append(p);
        }
        return sb.toString();
    }
}
