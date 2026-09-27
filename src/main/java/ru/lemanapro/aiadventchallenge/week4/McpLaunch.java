package ru.lemanapro.aiadventchallenge.week4;

import io.modelcontextprotocol.client.transport.ServerParameters;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Week 4 helper: launch parameters for a stdio MCP server implemented in this project
 * (a class with its own main()), started as a separate JVM with the same classpath.
 */
final class McpLaunch {

    private McpLaunch() {
    }

    /** `java -cp <project classpath> <serverMain> args...` */
    static ServerParameters javaServer(Class<?> serverMain, String... args) {
        String javaBin = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> all = new ArrayList<>(List.of("-cp", runtimeClasspath(), serverMain.getName()));
        all.addAll(Arrays.asList(args));
        return ServerParameters.builder(javaBin).args(all).build();
    }

    /** The command line for logs/reports, with the (very long) classpath collapsed. */
    static String display(ServerParameters params) {
        return (params.getCommand() + " " + String.join(" ", params.getArgs()))
                .replaceAll("-cp \\S+", "-cp <classpath проекта>");
    }

    /**
     * Under `mvn exec:java` the project classes and dependencies live in a child URLClassLoader,
     * not in java.class.path — so collect URLs from the loader chain to start the child JVM.
     */
    static String runtimeClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        for (ClassLoader cl = McpLaunch.class.getClassLoader(); cl != null; cl = cl.getParent()) {
            if (cl instanceof URLClassLoader ucl) {
                for (URL u : ucl.getURLs()) {
                    try {
                        entries.add(Path.of(u.toURI()).toString());
                    } catch (Exception ignored) {
                        // non-file URL, skip
                    }
                }
            }
        }
        entries.addAll(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        entries.removeIf(String::isBlank);
        return String.join(File.pathSeparator, entries);
    }
}
