package io.shadereply;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootCommand {
    record Result(int status, String output) {
        boolean succeeded() { return status == 0; }
    }

    private RootCommand() {}

    // Commands return small status values. Diagnostics from Android services stay on the device.
    static Result run(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "[ \"$(id -u)\" = 0 ] || exit 77\n" + command)
                    .redirectError(new File("/dev/null")).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) return new Result(124, "");
            return new Result(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException error) {
            return new Result(77, "");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Result(130, "");
        } finally {
            if (process != null && process.isAlive()) process.destroy();
        }
    }
}
