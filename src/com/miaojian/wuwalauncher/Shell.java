package com.miaojian.wuwalauncher;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Shizuku shell 执行封装。
 *
 * 全项目唯一一处调用 shell 的地方，其它模块都通过这里走。
 *
 * 两个实现要点：
 *   1. Shizuku.newProcess 是私有方法，必须反射调用
 *   2. stdout 与 stderr 必须并发读取。顺序读取会在输出超过管道缓冲区
 *      （通常 64KB）时死锁 —— dumpsys 这类命令很容易触发。
 */
public final class Shell {

    public interface Log {
        void line(String s);
    }

    /** 一次执行的结果 */
    public static final class Result {
        public final boolean started;   // 进程是否成功拉起
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        Result(boolean started, int exitCode, String stdout, String stderr) {
            this.started = started;
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        /** 命令正常结束且退出码为 0 */
        public boolean success() {
            return started && exitCode == 0;
        }
    }

    private Shell() {
    }

    // ---------------- 状态 ----------------

    /** Shizuku 服务是否在线 */
    public static boolean available() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已获得 Shizuku 授权 */
    public static boolean granted() {
        try {
            return Shizuku.checkSelfPermission()
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean ready() {
        return available() && granted();
    }

    // ---------------- 执行 ----------------

    /**
     * 执行命令并返回完整结果。
     * 不会返回 null —— 失败时 started=false，调用方看 success()。
     */
    public static Result run(String cmd, Log log) {
        if (log != null) log.line("> " + cmd);

        Process p = null;
        try {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            if (p == null) {
                if (log != null) log.line("  !! 拿不到 shell 进程");
                return new Result(false, -1, "", "no process");
            }

            // stderr 必须与 stdout 并发读，否则可能死锁
            final InputStream es = p.getErrorStream();
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            Thread errThread = new Thread(new Runnable() {
                public void run() {
                    pipe(es, errBuf);
                }
            }, "shell-stderr");
            errThread.setDaemon(true);
            errThread.start();

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            pipe(p.getInputStream(), outBuf);

            int code = p.waitFor();
            errThread.join(1000);

            String out = outBuf.toString("UTF-8").trim();
            String err = errBuf.toString("UTF-8").trim();

            if (log != null) {
                if (out.length() > 0) log.line("  " + out.replace("\n", "\n  "));
                if (err.length() > 0) log.line("  !! " + err.replace("\n", "\n  !! "));
                if (code != 0) log.line("  [exit " + code + "]");
            }
            return new Result(true, code, out, err);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            if (log != null) log.line("  !! 执行失败：" + cause);
            return new Result(false, -1, "", String.valueOf(cause));
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 只关心 stdout，命令失败时返回 null */
    public static String exec(String cmd, Log log) {
        Result r = run(cmd, log);
        return r.success() ? r.stdout : null;
    }

    /** 只关心成败 */
    public static boolean ok(String cmd, Log log) {
        return run(cmd, log).success();
    }

    private static void pipe(InputStream in, ByteArrayOutputStream out) {
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Throwable ignored) {
        }
    }
}
