package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObserverTrialStatusTest {
  @TempDir Path directory;

  private Map<String, String> complete() {
    Map<String, String> fields = new TreeMap<>();
    fields.put("statusVersion", "2");
    fields.put("statusComplete", "true");
    fields.put("collectionHealthy", "true");
    fields.put("queueDrops", "0");
    fields.put("shutdownDiscarded", "0");
    fields.put("incomplete", "false");
    fields.put("probeFailures", "0");
    fields.put("methodOverflow", "0");
    fields.put("timingHistogramOverflow", "0");
    fields.put("observedTimingEvents", "127");
    fields.put("acceptedTimingReservations", "127");
    fields.put("producerQuiesced", "true");
    fields.put("unfinishedProbeExits", "0");
    fields.put("activeMethodIdLimit", "3");
    fields.put("activeQueueCapacity", "65536");
    fields.put("rawTimingRowsOmitted", "12");
    fields.put("durationClipped", "0");
    fields.put("skips", "{excludedClass=15}");
    return fields;
  }

  private Path write(Map<String, String> fields) throws IOException {
    Path target = directory.resolve("observer-status.txt");
    StringBuilder text = new StringBuilder();
    for (var item : fields.entrySet()) {
      text.append(item.getKey()).append('=').append(item.getValue()).append('\n');
    }
    Files.writeString(target, text.toString());
    return target;
  }

  @Test
  void completeStatusRequiresActualObservedTimingsAndPreservesBoundedRawOmissions()
      throws Exception {
    var totals = ObserverTrialStatus.requireComplete(write(complete()));
    assertEquals(127, totals.accepted());
    assertEquals(127, totals.observed());
  }

  @Test
  void missingOrLinkedStatusIsNeverEvidence() throws Exception {
    assertThrows(
        IOException.class,
        () -> ObserverTrialStatus.requireComplete(directory.resolve("absent.txt")));
    Path ordinary = write(complete());
    Path alias = directory.resolve("alias.txt");
    Files.createSymbolicLink(alias, ordinary);
    assertThrows(IOException.class, () -> ObserverTrialStatus.requireComplete(alias));
  }

  @Test
  void everyMissingMandatoryFieldFailsClosed() throws Exception {
    for (String name :
        new String[] {
          "statusVersion",
          "statusComplete",
          "collectionHealthy",
          "queueDrops",
          "shutdownDiscarded",
          "incomplete",
          "probeFailures",
          "methodOverflow",
          "timingHistogramOverflow",
          "observedTimingEvents",
          "acceptedTimingReservations",
          "producerQuiesced",
          "unfinishedProbeExits",
          "activeMethodIdLimit",
          "activeQueueCapacity"
        }) {
      withFieldAbsent(name);
    }
  }

  private void withFieldAbsent(String field) throws Exception {
    Map<String, String> values = complete();
    values.remove(field);
    assertThrows(
        IOException.class,
        () -> ObserverTrialStatus.requireComplete(write(values)),
        "missing " + field);
  }

  @Test
  void incompleteAndUnhealthyFlagsCannotBeForgivenByThroughput() throws Exception {
    for (String name :
        new String[] {"statusComplete", "collectionHealthy", "incomplete", "producerQuiesced"}) {
      Map<String, String> values = complete();
      values.put(name, name.equals("incomplete") ? "true" : "false");
      assertThrows(IOException.class, () -> ObserverTrialStatus.requireComplete(write(values)));
    }
  }

  @Test
  void allLossCountersAndProbeFailuresInvalidateTrial() throws Exception {
    for (String name :
        new String[] {
          "queueDrops",
          "shutdownDiscarded",
          "probeFailures",
          "methodOverflow",
          "timingHistogramOverflow",
          "unfinishedProbeExits"
        }) {
      Map<String, String> values = complete();
      values.put(name, "1");
      assertThrows(
          IOException.class, () -> ObserverTrialStatus.requireComplete(write(values)), name);
    }
  }

  @Test
  void incorrectConfigurationCannotMasqueradeAsDeclaredProbe() throws Exception {
    for (String name :
        new String[] {"statusVersion", "activeMethodIdLimit", "activeQueueCapacity"}) {
      Map<String, String> values = complete();
      values.put(name, "4");
      assertThrows(
          IOException.class, () -> ObserverTrialStatus.requireComplete(write(values)), name);
    }
  }

  @Test
  void missingExcessOrZeroDeliveredTimingsCannotPass() throws Exception {
    for (long accepted : new long[] {0, 126, 128}) {
      Map<String, String> values = complete();
      values.put("acceptedTimingReservations", Long.toString(accepted));
      assertThrows(IOException.class, () -> ObserverTrialStatus.requireComplete(write(values)));
    }
    Map<String, String> values = complete();
    values.put("observedTimingEvents", "0");
    assertThrows(IOException.class, () -> ObserverTrialStatus.requireComplete(write(values)));
  }

  @Test
  void duplicateAndMalformedFieldsFailWithoutSilentlyChoosingOne() throws Exception {
    Path target = write(complete());
    Files.writeString(target, Files.readString(target) + "collectionHealthy=false\n");
    assertThrows(IOException.class, () -> ObserverTrialStatus.requireComplete(target));
    Files.writeString(target, Files.readString(target) + "no-equals-line\n");
    assertThrows(IOException.class, () -> ObserverTrialStatus.requireComplete(target));
  }

  @Test
  void invalidUnsignedCountersAreNotAccepted() throws Exception {
    for (String value : new String[] {"-1", "01", "NaN", "1.0", "18446744073709551616"}) {
      Map<String, String> values = complete();
      values.put("queueDrops", value);
      assertThrows(
          IOException.class, () -> ObserverTrialStatus.requireComplete(write(values)), value);
    }
  }

  @Test
  void statusSizeIsBoundedIndependentlyOfCounterValues() throws Exception {
    Path path = write(complete());
    Files.writeString(path, Files.readString(path) + "debug=" + "x".repeat(66000) + "\n");
    assertThrows(IOException.class, () -> ObserverTrialStatus.requireComplete(path));
  }
}
