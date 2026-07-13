package com.tailor.transcritorata.deps;

/**
 * Outcome of checking a single external dependency.
 *
 * @param name           user-facing name of the dependency, in Portuguese
 * @param ok             whether the dependency was found and looks usable
 * @param detail         short detail shown next to the status (e.g. resolved path or version)
 * @param instructions   Portuguese, human-readable installation instructions, shown only when {@code !ok}
 * @param helpUrl        optional link with more information (may be {@code null})
 */
public record DependencyStatus(String name, boolean ok, String detail, String instructions, String helpUrl) {
}
