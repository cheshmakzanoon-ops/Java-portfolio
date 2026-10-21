package io.github.cheshmakzanoon.portfolio.byteguard;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.byteguard.input.InputManifest;
import java.util.*;
import org.junit.jupiter.api.Test;

class InputManifestOwnershipTest {
  @Test
  void constructorAndAccessorsCannotMutateAcceptedClassEvidence() {
    byte[] caller = {1, 2, 3, 4};
    InputManifest.Entry entry =
        new InputManifest.Entry("example/Main.class", caller, "sha256", true);
    caller[0] = 99;
    assertArrayEquals(new byte[] {1, 2, 3, 4}, entry.bytes());
    byte[] leaked = entry.bytes();
    leaked[1] = 88;
    assertArrayEquals(new byte[] {1, 2, 3, 4}, entry.bytes());
    assertEquals("example/Main.class", entry.logicalName());
    assertEquals("sha256", entry.sha256());
    assertTrue(entry.application());
    assertThrows(
        NullPointerException.class,
        () -> new InputManifest.Entry("example/Main.class", null, "sha256", true));
  }

  @Test
  void manifestMapsCannotMutateOwnedValuesThroughAnyPath() {
    byte[] bytes = {9, 8, 7, 6};
    InputManifest.Entry accepted =
        new InputManifest.Entry("example/Type.class", bytes, "hash", false);
    SortedMap<String, InputManifest.Entry> outside = new TreeMap<>();
    outside.put("example/Type.class", accepted);
    InputManifest manifest = new InputManifest(outside, List.of("diagnostic"), 4);
    outside.clear();
    bytes[0] = 42;
    assertArrayEquals(new byte[] {9, 8, 7, 6}, manifest.classes().get("example/Type.class").bytes());
    assertThrows(UnsupportedOperationException.class, () -> manifest.classes().clear());
    assertThrows(UnsupportedOperationException.class, () -> manifest.diagnostics().clear());
    manifest.classes().get("example/Type.class").bytes()[0] = 0;
    assertArrayEquals(new byte[] {9, 8, 7, 6}, manifest.classes().get("example/Type.class").bytes());
  }

  @Test
  void byteArrayContentIsPartOfValueEqualityAndHashCode() {
    var first = new InputManifest.Entry("A.class", new byte[] {1, 2, 3}, "digest", true);
    var equal = new InputManifest.Entry("A.class", new byte[] {1, 2, 3}, "digest", true);
    var changedBytes =
        new InputManifest.Entry("A.class", new byte[] {1, 2, 4}, "digest", true);
    var changedSource =
        new InputManifest.Entry("B.class", new byte[] {1, 2, 3}, "digest", true);
    var changedOrigin =
        new InputManifest.Entry("A.class", new byte[] {1, 2, 3}, "digest", false);
    var changedDigest =
        new InputManifest.Entry("A.class", new byte[] {1, 2, 3}, "other", true);
    assertEquals(first, equal);
    assertEquals(first.hashCode(), equal.hashCode());
    for (var distinct : List.of(changedBytes, changedSource, changedOrigin, changedDigest)) {
      assertNotEquals(first, distinct);
    }
    assertEquals(2, new HashSet<>(List.of(first, equal, changedBytes)).size());
    assertNotEquals(first, null);
    assertNotEquals(first, "string");
  }

  @Test
  void deterministicMutationCorpusRetainsEveryFrozenByte() {
    Random random = new Random(934091);
    for (int trial = 0; trial < 10000; trial++) {
      byte[] source = new byte[1 + random.nextInt(257)];
      random.nextBytes(source);
      byte[] expected = source.clone();
      InputManifest.Entry entry =
          new InputManifest.Entry("fixture" + trial + ".class", source, "stable", true);
      random.nextBytes(source);
      byte[] exposed = entry.bytes();
      random.nextBytes(exposed);
      assertArrayEquals(expected, entry.bytes(), "mutation changed stored provenance: " + trial);
      var equivalent =
          new InputManifest.Entry(entry.logicalName(), expected.clone(), entry.sha256(), true);
      assertEquals(entry, equivalent);
      assertEquals(entry.hashCode(), equivalent.hashCode());
    }
  }
}
