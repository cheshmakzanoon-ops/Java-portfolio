package io.github.cheshmakzanoon.portfolio.byteguard;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.InstructionId;
import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.SourceIdentity;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceIdentityInputTest {
  @Test
  void malformedIdentityAlwaysFailsWithAnInputException() {
    for (String invalid :
        Arrays.asList(
            null,
            "",
            "A:",
            "S:",
            "S::",
            "S:0::0:x",
            "S:1:A:",
            "S:1:A:x:x",
            "S:1:A:0:",
            "S:1:A:-1:x",
            "S:1:A:00:x",
            "S:01:A:0:x",
            "S:2147483647:A:0:x",
            "S:99999999999999999999999999:A:0:x",
            "S:1:A:0",
            "S:1:A:0:x".repeat(30000))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> SourceIdentity.fromLabel(invalid),
          () -> "accepted malformed identity: " + invalid);
    }
  }

  @Test
  void canonicalRoundTripIsUnambiguousEvenWithSeparatorsInMethodIdentity() {
    List<String> methods =
        List.of(
            "Class.method(Ljava/lang/String;)V",
            "a:b:c",
            "S:3:abc",
            "@:[]:$",
            "\uD83D\uDE00:method",
            "Class$Inner.lambda$0()V");
    for (String method : methods) {
      for (int ordinal : List.of(0, 1, 10, 1024, Integer.MAX_VALUE)) {
        SourceIdentity original =
            new SourceIdentity(new InstructionId(method, ordinal), "descriptor:[]@");
        assertEquals(original, SourceIdentity.fromLabel(original.label()));
        assertEquals(original.label(), SourceIdentity.fromLabel(original.label()).label());
      }
    }
  }

  @Test
  void randomizedValidIdentitiesPreserveEveryUtf16Unit() {
    Random random = new Random(349106);
    String alphabet = "aAB:()[]/;@$_\uD83D\uDE00";
    for (int trial = 0; trial < 5000; trial++) {
      StringBuilder name = new StringBuilder();
      int length = 1 + random.nextInt(64);
      for (int i = 0; i < length; i++)
        name.append(alphabet.charAt(random.nextInt(alphabet.length())));
      SourceIdentity identity =
          new SourceIdentity(
              new InstructionId(name.toString(), trial), "Ljava/lang/Object;:boundary");
      assertEquals(identity, SourceIdentity.fromLabel(identity.label()));
    }
  }

  @Test
  void randomMalformedStringsDoNotEscapeBoundsWithLowLevelParserExceptions() {
    Random random = new Random(123412);
    String alphabet = "Aa:@;[]?~$:!0123456789";
    for (int trial = 0; trial < 25000; trial++) {
      StringBuilder candidate = new StringBuilder();
      for (int n = random.nextInt(40); n > 0; n--)
        candidate.append(alphabet.charAt(random.nextInt(alphabet.length())));
      String encoded = candidate.toString();
      try {
        SourceIdentity accepted = SourceIdentity.fromLabel(encoded);
        assertEquals(encoded, accepted.label(), "noncanonical source label accepted");
      } catch (IllegalArgumentException expected) {
        // Malformed labels must fail at the public parser boundary, not through indexing.
      }
    }
  }
}
