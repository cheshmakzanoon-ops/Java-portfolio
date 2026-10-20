package io.github.cheshmakzanoon.portfolio.durableflow;

import io.github.cheshmakzanoon.portfolio.durableflow.Definition.Type;
import java.util.*;
import java.util.regex.Pattern;

/** Restricted immutable data algebra. No string-to-number/boolean coercion is permitted. */
public final class Data {
  private static final Pattern SENSITIVE_TEXT =
      Pattern.compile(
          "password|secret|bearer|token", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private Data() {}

  public static Output activityResult(
      String activity, Type outputType, int argumentCount, List<Value> result) {
    if (activity == null
        || !Definition.ACTIVITIES.contains(activity)
        || argumentCount < 1
        || argumentCount > 128) throw new IllegalArgumentException("activity result signature");
    Output output = Output.of(result);
    output.require(outputType);
    int expected = activity.equals("pure") ? argumentCount : 1;
    if (result.size() != expected)
      throw new IllegalArgumentException("activity result cardinality");
    return output;
  }

  /** Semantics 3 cancellation records the durable forward-lease barrier as an actual integer. */
  public static int cancelledForwardLeases(List<Value> evidence) {
    if (evidence == null
        || evidence.size() != 1
        || evidence.getFirst() == null
        || evidence.getFirst().type() != Type.INTEGER)
      throw new IllegalArgumentException("cancellation lease evidence required");
    long count = (Long) evidence.getFirst().data();
    if (count < 0 || count > 128)
      throw new IllegalArgumentException("cancellation lease count bounds");
    return (int) count;
  }

  public record Value(Type type, Object data) {
    public Value {
      if (type == null || data == null) throw new IllegalArgumentException("typed scalar required");
      switch (type) {
        case TEXT -> {
          if (!(data instanceof String s) || s.length() > 1024 || SENSITIVE_TEXT.matcher(s).find())
            throw new IllegalArgumentException("bounded public TEXT required");
        }
        case BOOLEAN -> {
          if (!(data instanceof Boolean))
            throw new IllegalArgumentException("BOOLEAN requires boolean JSON value");
        }
        case INTEGER -> {
          if (!(data instanceof Integer) && !(data instanceof Long))
            throw new IllegalArgumentException("INTEGER requires integral JSON value");
          data = ((Number) data).longValue();
        }
      }
    }

    public static Value text(String text) {
      return new Value(Type.TEXT, text);
    }

    public static Value integer(long number) {
      return new Value(Type.INTEGER, number);
    }

    public static Value bool(boolean truth) {
      return new Value(Type.BOOLEAN, truth);
    }

    public static Value literal(Type type, String literal) {
      return switch (type) {
        case TEXT -> text(literal);
        case BOOLEAN -> {
          if (!Set.of("true", "false").contains(literal))
            throw new IllegalArgumentException("boolean literal");
          yield bool(Boolean.parseBoolean(literal));
        }
        case INTEGER -> {
          if (!literal.matches("-?(0|[1-9][0-9]{0,18})"))
            throw new IllegalArgumentException("integer literal");
          yield integer(Long.parseLong(literal));
        }
      };
    }
  }

  public record ExecutionInput(Value payload, Map<String, Value> context) {
    public ExecutionInput {
      if (payload == null || context == null || context.size() > 32)
        throw new IllegalArgumentException("execution input required");
      TreeMap<String, Value> sorted = new TreeMap<>();
      for (var entry : context.entrySet()) {
        String name = entry.getKey();
        if (name == null
            || !name.matches("[a-zA-Z][a-zA-Z0-9_]{0,31}")
            || name.toLowerCase(Locale.ROOT)
                .matches(".*(password|secret|token|authorization).*|payload")
            || entry.getValue() == null) throw new IllegalArgumentException("context field");
        sorted.put(name, entry.getValue());
      }
      context = Collections.unmodifiableMap(sorted);
    }

    /** Explicit Java convenience adapter: every old string remains TEXT, never coerced. */
    public static ExecutionInput textContext(Map<String, String> context) {
      if (context == null) throw new IllegalArgumentException("input required");
      Map<String, Value> typed = new TreeMap<>();
      context.forEach((key, value) -> typed.put(key, Value.text(value)));
      return new ExecutionInput(Value.text("input"), typed);
    }

    public void validate(Definition definition) {
      if (payload.type() != definition.root().inputType()
          || !context.keySet().equals(definition.variables().keySet()))
        throw new IllegalArgumentException("execution schema mismatch");
      context.forEach(
          (name, value) -> {
            if (value.type() != definition.variables().get(name))
              throw new IllegalArgumentException("context type mismatch");
          });
    }
  }

  public enum Outcome {
    VALUE,
    SKIPPED,
    TIMED_OUT
  }

  public record Output(Outcome outcome, List<Value> values) {
    public Output {
      if (outcome == null || values == null) throw new IllegalArgumentException("output required");
      values = List.copyOf(values);
      if (values.size() > 128 || (outcome == Outcome.VALUE) == values.isEmpty())
        throw new IllegalArgumentException("output shape");
      Type element = values.isEmpty() ? null : values.getFirst().type();
      if (!values.isEmpty() && values.stream().anyMatch(v -> v.type() != element))
        throw new IllegalArgumentException("homogeneous output required");
    }

    public static Output of(List<Value> values) {
      return new Output(Outcome.VALUE, values);
    }

    public static Output skipped() {
      return new Output(Outcome.SKIPPED, List.of());
    }

    public static Output timedOut() {
      return new Output(Outcome.TIMED_OUT, List.of());
    }

    public void require(Type type) {
      if (outcome != Outcome.VALUE || values.stream().anyMatch(value -> value.type() != type))
        throw new IllegalArgumentException("result type mismatch");
    }
  }

  public record TaskInput(
      ExecutionInput execution, List<Value> arguments, Map<String, Output> predecessors) {
    public TaskInput {
      if (execution == null
          || arguments == null
          || predecessors == null
          || arguments.isEmpty()
          || arguments.size() > 128
          || predecessors.size() > 16) throw new IllegalArgumentException("task input shape");
      arguments = List.copyOf(arguments);
      predecessors = Collections.unmodifiableMap(new TreeMap<>(predecessors));
    }
  }
}
