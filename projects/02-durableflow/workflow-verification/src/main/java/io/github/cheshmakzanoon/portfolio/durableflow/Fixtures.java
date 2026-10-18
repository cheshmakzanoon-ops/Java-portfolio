package io.github.cheshmakzanoon.portfolio.durableflow;

import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import java.util.*;

public final class Fixtures {
  private Fixtures() {}

  public static Node node(
      String id,
      Kind kind,
      List<String> dependencies,
      String activity,
      Safety safety,
      String compensation,
      long seconds) {
    return new Node(
        id,
        kind,
        dependencies,
        safety == Safety.PURE && compensation == null ? "pure" : activity,
        safety == Safety.PURE && compensation != null ? Safety.IDEMPOTENT_WITH_KEY : safety,
        compensation,
        Type.TEXT,
        Type.TEXT,
        seconds,
        "approval",
        null,
        null);
  }

  public static Definition order(int version, Safety safety) {
    return new Definition(
        "order-fulfilment",
        version,
        List.of(
            node("reserve", Kind.ACTIVITY, List.of(), "reserve", safety, "release", 0),
            node("approval", Kind.SIGNAL, List.of("reserve"), null, null, null, 3600),
            node(
                "ship",
                Kind.ACTIVITY,
                List.of("approval"),
                "ship",
                Safety.IDEMPOTENT_WITH_KEY,
                "refund",
                0)),
        Map.of("order", Type.TEXT));
  }

  public static Definition media() {
    return new Definition(
        "media-processing",
        1,
        List.of(
            node("upload", Kind.ACTIVITY, List.of(), "pure", Safety.PURE, null, 0),
            node(
                "transcode",
                Kind.ACTIVITY,
                List.of("upload"),
                "transcode",
                Safety.IDEMPOTENT_WITH_KEY,
                "unpublish",
                0),
            node(
                "thumbnail",
                Kind.ACTIVITY,
                List.of("upload"),
                "thumbnail",
                Safety.IDEMPOTENT_WITH_KEY,
                null,
                0),
            node("join", Kind.PARALLEL_ALL, List.of("transcode", "thumbnail"), null, null, null, 0),
            node(
                "publish",
                Kind.ACTIVITY,
                List.of("join"),
                "publish",
                Safety.IDEMPOTENT_WITH_KEY,
                "unpublish",
                0)));
  }
}
