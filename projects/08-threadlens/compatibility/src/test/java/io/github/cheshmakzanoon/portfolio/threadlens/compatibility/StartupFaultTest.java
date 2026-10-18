package io.github.cheshmakzanoon.portfolio.threadlens.compatibility;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.threadlens.agent.AgentConfig;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupFaultTest {
  @TempDir Path directory;

  @Test
  void malformedConfigAndUnwritableOutputDoNotPreventHostStartup() throws Exception {
    Path classes = compileHost();
    Path bad = directory.resolve("bad.yaml");
    Files.writeString(bad, "unknown: forbidden\n");
    String first = runHost(classes, bad);
    assertTrue(first.contains("host-result=50005000"));
    assertEquals(1, first.lines().filter(s -> s.startsWith("ThreadLens:")).count());
    Path blocked = directory.resolve("blocked");
    Files.writeString(blocked, "ordinary file");
    Path config = directory.resolve("unwritable.yaml");
    Files.writeString(
        config, "allowPackages: [sample.application]\noutputDirectory: " + blocked + "\n");
    String second = runHost(classes, config);
    assertTrue(second.contains("host-result=50005000"));
    assertTrue(second.contains("initialization disabled"));
  }

  @Test
  void installedShadedAgentSaturatesQueueButPreservesHost() throws Exception {
    Path classes = compileHost(),
        output = directory.resolve("observer"),
        config = directory.resolve("valid.yaml");
    Files.writeString(
        config,
        "allowPackages: [sample.application]\nmethodNames: [unit]\nmaxMethods: 2\nqueueCapacity: 2\noutputDirectory: "
            + output
            + "\n");
    String stdout = runHost(classes, config);
    assertTrue(stdout.contains("host-result=50005000"));
    String status = Files.readString(output.resolve("observer-status.txt"));
    assertTrue(status.contains("probeFailures=0"));
    long drops =
        Long.parseLong(
            status
                .lines()
                .filter(s -> s.startsWith("queueDrops="))
                .findFirst()
                .orElseThrow()
                .substring(11));
    assertTrue(drops > 0, status);
    assertTrue(status.contains("shutdownDiscarded=0"), status);
    assertEquals(
        10000 - drops,
        Files.readAllLines(output.resolve("timings.tsv")).size() - 1,
        "accepted events must drain at shutdown");
    assertTrue(
        Files.readString(output.resolve("methods.tsv")).contains("sample.application.Host.unit"));
    assertTrue(Files.size(output.resolve("timings.tsv")) > 50);
    long summaryEvents =
        Files.readAllLines(output.resolve("timing-summary.tsv")).stream()
            .skip(1)
            .mapToLong(line -> Long.parseLong(line.split("\\t")[1]))
            .sum();
    assertEquals(10000 - drops, summaryEvents, "histograms conserve all accepted timings");
  }

  @Test
  void configLimitsAndUnknownKeysAreStrict() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> AgentConfig.read(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentConfig(List.of("java"), List.of(), 1, 2, directory));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentConfig(List.of("sample.application"), List.of(), 10_001, 2, directory));
    Path bad = directory.resolve("bad.yaml");
    Files.writeString(bad, "allowPackages: [sample.application]\nmaxMethods: 1\nmaxMethods: 2\n");
    assertThrows(IllegalArgumentException.class, () -> AgentConfig.read("config=" + bad));
  }

  private Path compileHost() throws Exception {
    Path source = directory.resolve("sample/application/Host.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        "package sample.application; public class Host {static int unit(int n){return n;} public static void main(String[] a){long sum=0;for(int i=1;i<=10000;i++)sum+=unit(i);System.out.println(\"host-result=\"+sum);}}");
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", directory.toString(), source.toString()));
    return directory;
  }

  private String runHost(Path classes, Path config) throws Exception {
    Path agent =
        Path.of("..", "agent", "target", "threadlens-agent-1.0.0-SNAPSHOT.jar").toAbsolutePath();
    assertTrue(Files.isRegularFile(agent), agent.toString());
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xverify:all",
                "-javaagent:" + agent + "=config=" + config,
                "-cp",
                classes.toString(),
                "sample.application.Host")
            .redirectErrorStream(true)
            .start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    String text =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), text);
    return text;
  }
}
