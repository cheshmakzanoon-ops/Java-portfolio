package io.github.cheshmakzanoon.portfolio.byteguard.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class CliProcessIT {
  @Test
  void longLegalFindingRoundTripsThroughExactBaselineInPackagedCli() throws Exception {
    Path project = Path.of("..").toAbsolutePath().normalize();
    Path jar = project.resolve("cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar");
    Path input = Path.of("target/long-baseline.class");
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "LongBaseline", null, "java/lang/Object", null);
    var method =
        writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "m".repeat(60000),
            "(Ljava/sql/Statement;L" + "A".repeat(60000) + ";)V",
            null,
            null);
    method.visitCode();
    method.visitVarInsn(Opcodes.ALOAD, 0);
    method.visitLdcInsn("USER_INPUT");
    method.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/lang/System",
        "getenv",
        "(Ljava/lang/String;)Ljava/lang/String;",
        false);
    method.visitMethodInsn(
        Opcodes.INVOKEINTERFACE, "java/sql/Statement", "execute", "(Ljava/lang/String;)Z", true);
    method.visitInsn(Opcodes.POP);
    method.visitInsn(Opcodes.RETURN);
    method.visitMaxs(2, 2);
    method.visitEnd();
    writer.visitEnd();
    Files.write(input, writer.toByteArray());
    Path baseline = Path.of("target/long-baseline-first.sarif");
    for (int attempt = 0; attempt < 2; attempt++) {
      Path report = attempt == 0 ? baseline : Path.of("target/long-baseline-second.sarif");
      Path log = Path.of("target/long-baseline-" + attempt + ".log");
      List<String> command =
          new ArrayList<>(
              List.of(
                  Path.of(System.getProperty("java.home"), "bin/java").toString(),
                  "-Xmx128m",
                  "-jar",
                  jar.toString(),
                  "scan",
                  "--input",
                  input.toString(),
                  "--out",
                  report.toString()));
      if (attempt == 1) command.addAll(List.of("--baseline", baseline.toString()));
      Process process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        fail("Long identity baseline scan exceeded deadline");
      }
      assertEquals(attempt == 0 ? 1 : 0, process.exitValue(), Files.readString(log));
      JsonNode run = new ObjectMapper().readTree(report.toFile()).path("runs").get(0);
      assertTrue(run.path("invocations").get(0).path("executionSuccessful").booleanValue());
      assertEquals(1, run.path("results").size());
      assertEquals(
          attempt == 0 ? "new" : "unchanged",
          run.path("results").get(0).path("baselineState").textValue());
      assertTrue(Files.size(report) < 8 * 1024 * 1024);
    }
  }

  @Test
  void descriptorLimitsAndLongIdentitiesRemainBoundedInPackagedCli() throws Exception {
    String legalDescriptor = "(L" + "A".repeat(60000) + ";" + "[I".repeat(254) + ")V";
    checkDescriptorProcess("invalid-slots", "(" + "[I".repeat(256) + ")V", "run", 256, 2);
    checkDescriptorProcess("legal-long", legalDescriptor, "m".repeat(60000), 255, 0);
  }

  private void checkDescriptorProcess(
      String name, String descriptor, String methodName, int locals, int expectedCode)
      throws Exception {
    Path project = Path.of("..").toAbsolutePath().normalize();
    Path jar = project.resolve("cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar");
    Path input = Path.of("target/descriptor-" + name + ".class");
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        Opcodes.V25,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
        "DescriptorBoundary",
        null,
        "java/lang/Object",
        null);
    var method =
        writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, descriptor, null, null);
    method.visitCode();
    method.visitInsn(Opcodes.RETURN);
    method.visitMaxs(0, locals);
    method.visitEnd();
    writer.visitEnd();
    Files.write(input, writer.toByteArray());
    Path report = Path.of("target/descriptor-" + name + ".sarif");
    Path log = Path.of("target/descriptor-" + name + ".log");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx64m",
                "-jar",
                jar.toString(),
                "scan",
                "--input",
                input.toString(),
                "--out",
                report.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
      fail("Bounded descriptor validation timed out");
    }
    String output = Files.readString(log);
    assertEquals(expectedCode, process.exitValue(), output);
    assertFalse(output.contains("OutOfMemoryError"), output);
    JsonNode sarif = new ObjectMapper().readTree(report.toFile());
    JsonNode run = sarif.path("runs").get(0);
    if (expectedCode == 2) {
      assertTrue(output.contains("INVALID/FAILED"), output);
      assertFalse(run.path("invocations").get(0).path("executionSuccessful").asBoolean());
      assertEquals(
          "invalid input/configuration",
          run.path("properties").path("unfinishedFrontier").get(0).asText());
    } else {
      assertFalse(run.path("properties").path("partial").asBoolean());
      assertEquals(1, run.path("properties").path("applicationClasses").asInt());
      assertEquals(0, run.path("results").size());
    }
  }

  @Test
  void packagedExecutableFourStatusCodesAndSchemas() throws Exception {
    Path project = Path.of("..").toAbsolutePath().normalize();
    Path jar = project.resolve("cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar");
    assertTrue(Files.size(jar) > 1000);
    JsonSchema schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
            .getSchema(project.resolve("contracts/sarif-schema-2.1.0.json").toUri());
    for (int code = 0; code < 4; code++) {
      Path report = Path.of("target/process-" + code + ".sarif");
      List<String> args =
          new ArrayList<>(
              List.of(
                  Path.of(System.getProperty("java.home"), "bin/java").toString(),
                  "-Xmx512m",
                  "-jar",
                  jar.toString(),
                  "scan",
                  "--input",
                  code == 2
                      ? "missing.jar"
                      : "target/corpus-classes/fixtures/SQL01"
                          + (code == 0 ? "Negative" : "Positive")
                          + ".class",
                  "--out",
                  report.toString()));
      if (code == 3) args.addAll(List.of("--work-budget", "1"));
      if (code == 1)
        args.addAll(
            List.of(
                "--rules",
                project.resolve("rules/core.yaml").toString(),
                "--explain-dir",
                "target/process-explanation"));
      Process process =
          new ProcessBuilder(args)
              .redirectErrorStream(true)
              .redirectOutput(Path.of("target/process-" + code + ".log").toFile())
              .start();
      if (!process.waitFor(45, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        fail("packaged CLI timed out");
      }
      assertEquals(
          code, process.exitValue(), Files.readString(Path.of("target/process-" + code + ".log")));
      JsonNode output = new ObjectMapper().readTree(report.toFile());
      assertTrue(schema.validate(output).isEmpty(), schema.validate(output).toString());
      if (code == 3)
        assertTrue(output.path("runs").get(0).path("properties").path("partial").asBoolean());
    }
    assertTrue(Files.isRegularFile(Path.of("target/process-explanation/analysis.json")));
  }
}
