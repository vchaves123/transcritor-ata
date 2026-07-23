package com.tailor.transcritorata.deps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Locates external executables and model files on disk. Extracted behind an interface so
 * {@link DependencyChecker} can be tested without touching the real filesystem or PATH.
 */
public interface ExecutableLocator {

    /** Attempts to run {@code executable -version}-style probe and returns true if it succeeded. */
    boolean canRun(List<String> command, long timeoutSeconds);

    /** Searches PATH and the given extra candidate directories for the given executable file name. */
    Optional<Path> findOnPathOrCandidates(String executableName, List<Path> extraCandidates);

    boolean exists(Path path);

    long sizeOf(Path path);

    /** Default implementation backed by the real OS process table and filesystem. */
    final class Default implements ExecutableLocator {

        @Override
        public boolean canRun(List<String> command, long timeoutSeconds) {
            Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                process = builder.start();
                process.getInputStream().readAllBytes();
                boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return false;
            } finally {
                // Defensive backstop: an exception thrown between start() and waitFor() (e.g.
                // while reading the output stream) would otherwise leave the probed executable
                // running unmanaged until it exits on its own.
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }

        @Override
        public Optional<Path> findOnPathOrCandidates(String executableName, List<Path> extraCandidates) {
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                    Path candidate = Path.of(dir).resolve(executableName);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                }
            }
            for (Path dir : extraCandidates) {
                Path candidate = dir.resolve(executableName);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }

        @Override
        public boolean exists(Path path) {
            return path != null && Files.isRegularFile(path);
        }

        @Override
        public long sizeOf(Path path) {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return -1;
            }
        }
    }
}
