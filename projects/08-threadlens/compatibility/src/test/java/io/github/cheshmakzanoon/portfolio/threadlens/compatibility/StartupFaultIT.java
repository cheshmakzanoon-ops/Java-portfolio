package io.github.cheshmakzanoon.portfolio.threadlens.compatibility;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.threadlens.agent.AgentConfig;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupFaultIT {
  @TempDir Path directory;

  @Test
  void priorObserverArtifactsDisableCollectionWithoutMixingRunsOrPreventingHostStartup()
      throws Exception {
    Path classes = compileHost();
    for (String artifact :
        List.of(
            "observer-status.txt", "methods.tsv", "timing-summary.tsv", "timing-histograms.hlog")) {
      Path output = Files.createDirectory(directory.resolve(artifact + "-output"));
      Path previous = output.resolve(artifact);
      Files.writeString(previous, "retained observation");
      Path config = directory.resolve(artifact + ".yaml");
      Files.writeString(
          config,
          "allowPackages: [sample.application]\nmethodNames: [unit]\noutputDirectory: "
              + output
              + "\n");
      String stdout = runHost(classes, config);
      assertTrue(stdout.contains("host-result=50005000"), stdout);
      assertTrue(stdout.contains("initialization disabled"), stdout);
      assertEquals("retained observation", Files.readString(previous));
      try (var files = Files.list(output)) {
        assertEquals(List.of(previous), files.toList());
      }
    }
  }

  @Test
  void systemExitSealsAdmissionAndReportsPausedProducerAfterConsumerFinishes() throws Exception {
    Path source = directory.resolve("sample/application/ShutdownRaceHost.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        """
      package sample.application;
      import java.nio.file.*;
      import java.util.concurrent.*;
      import java.util.concurrent.atomic.*;
      import io.github.cheshmakzanoon.portfolio.threadlens.probe.TimingProbe;
      public class ShutdownRaceHost {
        static int unit(){return 42;}
        public static void main(String[] args)throws Exception {
          CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
          AtomicInteger result=new AtomicInteger();
          TimingProbe.injectFaultAction(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}});
          Thread worker=Thread.ofPlatform().daemon().start(()->result.set(unit()));
          if(!entered.await(5,TimeUnit.SECONDS))throw new AssertionError("probe did not reach pause");
          Runtime.getRuntime().addShutdownHook(new Thread(()->{
            try {
              Path status=Path.of(args[0],"observer-status.txt");long deadline=System.nanoTime()+10_000_000_000L;
              while(System.nanoTime()<deadline) {
                if(Files.exists(status)&&Files.readString(status).contains("statusComplete=true")) {
                  release.countDown();worker.join(2000);
                  if(worker.isAlive()||result.get()!=42)throw new AssertionError("host result changed");
                  System.out.println("post-shutdown-host-result=42");return;
                }
                Thread.sleep(10);
              }
              throw new AssertionError("observer did not finish bounded shutdown");
            }catch(Exception e){throw new AssertionError(e);}finally{release.countDown();}
          }));
          System.exit(0);
        }
      }
      """);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                directory.toString(),
                source.toString()));
    Path output = directory.resolve("shutdown-observer"),
        config = directory.resolve("shutdown.yaml");
    Files.writeString(
        config,
        "allowPackages: [sample.application]\nmethodNames: [unit]\nmaxMethods: 1\nqueueCapacity: 2\noutputDirectory: "
            + output
            + "\n");
    Path agent =
        Path.of("..", "agent", "target", "threadlens-agent-1.0.0-SNAPSHOT.jar").toAbsolutePath();
    Path log = directory.resolve("shutdown.log");
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xverify:all",
                "-javaagent:" + agent + "=config=" + config,
                "-cp",
                directory.toString(),
                "sample.application.ShutdownRaceHost",
                output.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    assertTrue(process.waitFor(20, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue(), Files.readString(log));
    assertTrue(
        Files.readString(log).contains("post-shutdown-host-result=42"), Files.readString(log));
    String status = Files.readString(output.resolve("observer-status.txt"));
    assertTrue(status.contains("unfinishedProbeExits=1"), status);
    assertTrue(status.contains("producerQuiesced=false"), status);
    assertTrue(status.contains("incomplete=true"), status);
    var timing =
        new io.github.cheshmakzanoon.portfolio.threadlens.report.TimingImporter().read(output);
    assertTrue(timing.statusComplete());
    assertTrue(timing.incomplete());
    assertEquals(1, timing.unfinishedProbeExits());
    assertEquals(0, timing.exits());
    assertEquals(0, timing.producerObservedEvents());
    assertEquals(0, timing.acceptedReservations());
  }

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

  @Test
  void methodCeilingIsExplicitInActualPackagedReport() throws Exception {
    Path classes = compileHost(),
        output = directory.resolve("limited"),
        config = directory.resolve("limited.yaml");
    Files.writeString(
        config,
        "allowPackages: [sample.application]\nmethodNames: [unit, extra]\nmaxMethods: 1\nqueueCapacity: 65536\noutputDirectory: "
            + output
            + "\n");
    assertTrue(runHost(classes, config).contains("host-result=50005000"));
    var importer = new io.github.cheshmakzanoon.portfolio.threadlens.report.TimingImporter();
    var timing = importer.read(output);
    assertEquals(1, timing.methodSelectionOverflow());
    assertEquals(1, timing.selectionSkips().get("methodCeiling"));
    assertEquals(10000, timing.exits());
    assertEquals(0, timing.queueDrops());
    assertTrue(timing.incomplete());
    assertTrue(timing.timings().keySet().stream().allMatch(k -> k.contains(".unit")));
    var profile =
        importer.merge(
            new io.github.cheshmakzanoon.portfolio.threadlens.analysis.ProfileAccumulator(1, 1, 1)
                .snapshot(),
            timing);
    var document =
        io.github.cheshmakzanoon.portfolio.threadlens.report.ReportDocument.create(
            "selection-limit", profile, Map.of(), Map.of(), profile.counters());
    Path json = directory.resolve("selection.json"), html = directory.resolve("selection.html");
    io.github.cheshmakzanoon.portfolio.threadlens.report.ReportWriter.json(json, document);
    io.github.cheshmakzanoon.portfolio.threadlens.report.ReportWriter.html(html, document);
    assertEquals(
        1,
        io.github.cheshmakzanoon.portfolio.threadlens.report.ReportWriter.read(json)
            .lossCounters()
            .get("timingMethodSelectionOverflow"));
    assertTrue(Files.readString(html).contains("timingSelectionSkip.methodCeiling"));
  }

  private Path compileHost() throws Exception {
    Path source = directory.resolve("sample/application/Host.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        "package sample.application; public class Host {static int unit(int n){return n;} static int extra(int n){return n;} public static void main(String[] a){long sum=0;for(int i=1;i<=10000;i++)sum+=unit(i);sum+=extra(0);System.out.println(\"host-result=\"+sum);}}");
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
