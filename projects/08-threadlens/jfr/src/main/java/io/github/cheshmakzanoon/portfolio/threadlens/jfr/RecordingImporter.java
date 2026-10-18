package io.github.cheshmakzanoon.portfolio.threadlens.jfr;

import io.github.cheshmakzanoon.portfolio.threadlens.analysis.Profile;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.ProfileAccumulator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import jdk.jfr.consumer.RecordingFile;

public final class RecordingImporter {
  public record ImportResult(
      Profile profile,
      Map<String, EventCapabilities.Capability> capabilities,
      long unsupportedFields,
      long truncatedStacks,
      long clippedStrings) {}

  public ImportResult read(Path path) throws IOException {
    ProfileAccumulator accumulator = new ProfileAccumulator(10_000, 1_000_000_000L, 900);
    EventNormalizer normalizer = new EventNormalizer();
    Map<String, EventCapabilities.Capability> capabilities;
    try (RecordingFile file = new RecordingFile(path)) {
      capabilities = EventCapabilities.describe(file.readEventTypes());
      while (file.hasMoreEvents())
        normalizer.normalize(file.readEvent()).ifPresent(accumulator::accept);
    }
    if (normalizer.truncatedStacks() > 0 || normalizer.unsupportedFields() > 0)
      accumulator.markIncomplete();
    return new ImportResult(
        accumulator.snapshot(),
        capabilities,
        normalizer.unsupportedFields(),
        normalizer.truncatedStacks(),
        normalizer.clippedStrings());
  }
}
