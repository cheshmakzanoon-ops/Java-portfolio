package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.IOException;
import java.util.*;

/**
 * Bounded observed syntax/value interactions. No control-flow or runtime-safety proof is claimed.
 */
public final class SourceRelations {
  public static final String SCHEMA = "source-interactions-v2";
  private static final Set<String> PRIMITIVES =
      Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double");

  public record Options(boolean identifiers, boolean apiDocumentation, boolean relations) {}

  private record Origin(String identity, Set<String> anchors) {}

  private record Context(SourceFacts.Member member, Map<String, Argument> arguments, int hops) {}

  private record Argument(
      Context context, SourceFacts.Expression expression, int position, int block) {}

  private final String key;
  private final SourceFacts.Unit unit;
  private final SourceSymbolGraph graph;
  private final SourceSymbolGraph.Work work;
  private final Options options;
  private final Set<String> query;

  private SourceRelations(
      String key,
      SourceFacts.Unit unit,
      SourceSymbolGraph graph,
      Set<String> query,
      Options options,
      SourceSymbolGraph.Work work) {
    if (query.size() > 64) throw new IllegalArgumentException("source query term quota");
    this.key = key;
    this.unit = Objects.requireNonNull(unit);
    this.graph = Objects.requireNonNull(graph);
    this.query = Set.copyOf(query);
    this.options = Objects.requireNonNull(options);
    this.work = Objects.requireNonNull(work);
  }

  /** Six dimensions10..15; a derived anchor quota never changes declaration membership. */
  public static double[] extract(
      String sourceKey,
      SourceFacts.Unit unit,
      SourceSymbolGraph graph,
      Set<String> normalizedQuery,
      Options options,
      SourceSymbolGraph.Work work)
      throws IOException {
    var extractor = new SourceRelations(sourceKey, unit, graph, normalizedQuery, options, work);
    if (!unit.declarationInventoryKnown() || normalizedQuery.isEmpty()) return new double[6];
    try {
      return extractor.extract();
    } catch (SourceAnchors.Quota derivedQuota) {
      return new double[6];
    }
  }

  private double[] extract() throws IOException {
    double[] result = new double[6];
    for (var member : unit.members()) {
      work.step();
      Set<String> executableTerms = new TreeSet<>();
      if (options.identifiers()) add(executableTerms, SourceAnchors.terms(member.identifiers()));
      var context = new Context(member, Map.of(), 0);
      Map<SourceFacts.Invocation, Set<String>> apiEndpoints = new LinkedHashMap<>();
      Set<String> apiTerms = new TreeSet<>();
      if (!member.bindingUnknown())
        for (var call : member.calls()) {
          work.step();
          var api = graph.api(key, member, call, work);
          if (api.isEmpty()) continue;
          if (options.apiDocumentation()) add(apiTerms, api.get().terms());
          apiEndpoints.put(call, endpoint(context, call.expression(), api.get()));
        }
      if (!member.bindingUnknown()) {
        var expressions = new ArrayList<SourceFacts.Expression>();
        member.locals().forEach(binding -> expressions.add(binding.initializer()));
        member.calls().forEach(call -> expressions.add(call.expression()));
        member.guards().forEach(guard -> expressions.add(guard.condition()));
        member.returned().forEach(returned -> expressions.add(returned.expression()));
        for (var expression : expressions)
          fieldAnchors(context, expression, executableTerms, apiTerms);
      }
      result[0] = Math.max(result[0], coverage(executableTerms));
      result[1] = Math.max(result[1], coverage(apiTerms));
      if (!options.relations() || member.bindingUnknown() || member.controlUnknown()) continue;
      for (var consumer : apiEndpoints.entrySet()) {
        work.step();
        var call = consumer.getKey();
        Map<String, Origin> consumed = originsOfArguments(context, call);
        for (var origin : consumed.values())
          result[2] = Math.max(result[2], interaction(origin.anchors(), consumer.getValue()));
        String receiver =
            receiver(
                context,
                call.expression().children().getFirst(),
                call.position(),
                call.block(),
                0,
                new HashSet<>());
        if (receiver != null)
          for (var producer : apiEndpoints.entrySet()) {
            work.step();
            var before = producer.getKey();
            if (before.position() >= call.position() || before.block() != call.block()) continue;
            String earlier =
                receiver(
                    context,
                    before.expression().children().getFirst(),
                    before.position(),
                    before.block(),
                    0,
                    new HashSet<>());
            if (receiver.equals(earlier)
                && noUnsupportedBetween(context, before.position(), call.position(), call.block()))
              result[3] =
                  Math.max(result[3], interaction(producer.getValue(), consumer.getValue()));
          }
        if (consumed.isEmpty()) continue;
        for (var guard : member.guards()) {
          work.step();
          if (guard.block() != call.block()
              || guard.end() > call.position()
              || !noUnsupportedBetween(context, guard.end(), call.position(), call.block()))
            continue;
          var observed =
              conditionOrigins(context, guard.condition(), guard.end(), guard.block(), 0);
          if (Collections.disjoint(consumed.keySet(), observed.keySet())) continue;
          Set<String> guardAnchors = expressionAnchors(context, guard.condition());
          double value = interaction(guardAnchors, consumer.getValue());
          result[4] = Math.max(result[4], value);
          // The parser emits only an if-without-else whose branch ends immediately in a
          // return/throw. Its later same-block effect is the observed alternate path.
          result[5] = Math.max(result[5], value);
        }
      }
    }
    return result;
  }

  private Map<String, Origin> originsOfArguments(Context context, SourceFacts.Invocation call)
      throws IOException {
    Map<String, Origin> result = new LinkedHashMap<>();
    for (int i = 1; i < call.expression().children().size(); i++)
      merge(
          result,
          origins(
              context,
              call.expression().children().get(i),
              call.position(),
              call.block(),
              true,
              0,
              new HashSet<>()));
    return result;
  }

  private Map<String, Origin> origins(
      Context context,
      SourceFacts.Expression expression,
      int position,
      int block,
      boolean embedded,
      int depth,
      Set<String> seen)
      throws IOException {
    work.step();
    if (depth >= 8 || context.member().bindingUnknown()) return Map.of();
    if (expression.kind().equals("name")) {
      String name = expression.text();
      if (!seen.add(context.member().signature() + "/" + name)) return Map.of();
      var argument = context.arguments().get(name);
      if (argument != null)
        return origins(
            argument.context(),
            argument.expression(),
            argument.position(),
            argument.block(),
            true,
            depth + 1,
            seen);
      for (var parameter : context.member().parameters())
        if (parameter.name().equals(name)) {
          if (parameter.ambiguous()) return Map.of();
          String primitive = primitiveElement(parameter.type());
          if (!options.identifiers() || primitive == null) return Map.of();
          Set<String> anchors =
              union(SourceAnchors.terms(List.of(name)), SourceApiCatalog.terms(primitive));
          var origin = new Origin(context.member().signature() + "/parameter/" + name, anchors);
          return Map.of(origin.identity(), origin);
        }
      var binding = local(context.member(), name);
      if (binding == null
          || binding.ambiguous()
          || binding.position() < 0
          || binding.position() >= position
          || binding.block() != block) return Map.of();
      return origins(context, binding.initializer(), position, block, false, depth + 1, seen);
    }
    if (expression.kind().equals("call")) {
      var call = uniqueCall(context.member(), expression, position, block, embedded);
      if (call == null) return Map.of();
      var api = graph.api(key, context.member(), call, work);
      if (api.isPresent()) {
        var origin =
            new Origin(
                context.member().signature() + "/call/" + call.position(),
                endpoint(context, expression, api.get()));
        return Map.of(origin.identity(), origin);
      }
      if (context.hops() != 0) return Map.of();
      var helper = graph.localHelper(key, context.member(), call, work);
      if (helper.isEmpty()) return Map.of();
      Map<String, Argument> arguments = new HashMap<>();
      for (int i = 0; i < helper.get().parameters().size(); i++)
        arguments.put(
            helper.get().parameters().get(i).name(),
            new Argument(context, expression.children().get(i + 1), position, block));
      var returned = helper.get().returned().getFirst();
      return origins(
          new Context(helper.get(), Map.copyOf(arguments), 1),
          returned.expression(),
          returned.position(),
          returned.block(),
          true,
          depth + 1,
          seen);
    }
    // Fields, casts, array accesses, unresolved object state and literal payloads do not acquire
    // parameter provenance. A declared primitive type in an unused signature is never an edge.
    return Map.of();
  }

  private Map<String, Origin> conditionOrigins(
      Context context, SourceFacts.Expression expression, int position, int block, int depth)
      throws IOException {
    work.step();
    if (depth >= 8) return Map.of();
    if (Set.of("compare", "not").contains(expression.kind())) {
      var result = new LinkedHashMap<String, Origin>();
      for (var child : expression.children())
        merge(result, conditionOrigins(context, child, position, block, depth + 1));
      return result;
    }
    return origins(context, expression, position, block, false, depth, new HashSet<>());
  }

  private String receiver(
      Context context,
      SourceFacts.Expression expression,
      int position,
      int block,
      int depth,
      Set<String> seen) {
    work.step();
    if (depth >= 8 || !expression.kind().equals("name") || !seen.add(expression.text()))
      return null;
    for (var parameter : context.member().parameters())
      if (parameter.name().equals(expression.text()))
        return parameter.ambiguous()
            ? null
            : context.member().signature() + "/parameter/" + parameter.name();
    var binding = local(context.member(), expression.text());
    if (binding == null
        || binding.ambiguous()
        || binding.position() < 0
        || binding.position() >= position
        || binding.block() != block) return null;
    if (binding.initializer().kind().equals("name"))
      return receiver(context, binding.initializer(), binding.position(), block, depth + 1, seen);
    if (!Set.of("new", "call").contains(binding.initializer().kind())) return null;
    return context.member().signature() + "/local/" + binding.position();
  }

  private boolean noUnsupportedBetween(Context context, int start, int end, int block) {
    for (var call : context.member().calls()) {
      work.step();
      if (call.position() > start
          && call.position() < end
          && call.block() == block
          && graph.api(key, context.member(), call, work).isEmpty()) return false;
    }
    return true;
  }

  private Set<String> endpoint(
      Context context, SourceFacts.Expression expression, SourceApiCatalog.Entry api)
      throws IOException {
    Set<String> out = new TreeSet<>();
    if (options.identifiers()) add(out, expressionIdentifiers(context.member(), expression));
    if (options.apiDocumentation()) add(out, api.terms());
    fieldAnchors(context, expression, out, out);
    return Collections.unmodifiableSet(out);
  }

  private Set<String> expressionAnchors(Context context, SourceFacts.Expression expression)
      throws IOException {
    Set<String> out = new TreeSet<>();
    if (options.identifiers()) add(out, expressionIdentifiers(context.member(), expression));
    fieldAnchors(context, expression, out, out);
    if (options.apiDocumentation()) {
      var pending = new ArrayDeque<SourceFacts.Expression>();
      pending.push(expression);
      while (!pending.isEmpty()) {
        work.step();
        var next = pending.pop();
        if (next.kind().equals("call"))
          for (var call : context.member().calls()) {
            work.step();
            if (call.expression().equals(next)) {
              var api = graph.api(key, context.member(), call, work);
              if (api.isPresent()) add(out, api.get().terms());
            }
          }
        next.children().forEach(pending::push);
      }
    }
    return out;
  }

  private void fieldAnchors(
      Context context,
      SourceFacts.Expression expression,
      Set<String> identifiers,
      Set<String> documentation)
      throws IOException {
    var pending = new ArrayDeque<SourceFacts.Expression>();
    pending.push(expression);
    while (!pending.isEmpty()) {
      work.step();
      var next = pending.pop();
      if (next.kind().equals("select")) {
        var field = graph.field(key, context.member(), next, work);
        if (field.isPresent()) {
          if (options.identifiers())
            add(identifiers, SourceAnchors.terms(List.of(field.get().name())));
          if (options.apiDocumentation()) add(documentation, field.get().terms());
        }
      }
      next.children().forEach(pending::push);
    }
  }

  private Set<String> expressionIdentifiers(
      SourceFacts.Member member, SourceFacts.Expression expression) throws IOException {
    Set<String> names = new TreeSet<>();
    var pending = new ArrayDeque<SourceFacts.Expression>();
    pending.push(expression);
    while (!pending.isEmpty()) {
      work.step();
      var next = pending.pop();
      if (Set.of("name", "select", "call").contains(next.kind())
          && member.identifiers().contains(next.text())) names.add(next.text());
      next.children().forEach(pending::push);
    }
    return SourceAnchors.terms(names);
  }

  private static SourceFacts.Invocation uniqueCall(
      SourceFacts.Member member,
      SourceFacts.Expression expression,
      int position,
      int block,
      boolean embedded) {
    SourceFacts.Invocation found = null;
    for (var call : member.calls())
      if (call.block() == block
          && call.expression().equals(expression)
          && (embedded || call.position() < position)) {
        if (found != null) return null;
        found = call;
      }
    return found;
  }

  private static SourceFacts.Binding local(SourceFacts.Member member, String name) {
    SourceFacts.Binding found = null;
    for (var binding : member.locals())
      if (binding.name().equals(name)) {
        if (found != null) return null;
        found = binding;
      }
    return found;
  }

  private static String primitiveElement(String type) {
    String name = type;
    while (name.endsWith("[]")) name = name.substring(0, name.length() - 2);
    return PRIMITIVES.contains(name) ? name : null;
  }

  private static void merge(Map<String, Origin> into, Map<String, Origin> values) {
    if (into.size() + values.size() > 128) throw new SourceAnchors.Quota();
    into.putAll(values);
  }

  private static Set<String> union(Set<String> first, Set<String> second) {
    Set<String> out = new TreeSet<>(first);
    add(out, second);
    return Collections.unmodifiableSet(out);
  }

  private static void add(Set<String> into, Set<String> values) {
    into.addAll(values);
    if (into.size() > 128) throw new SourceAnchors.Quota();
  }

  private double coverage(Set<String> anchors) {
    int matched = 0;
    for (String term : query) if (anchors.contains(term)) matched++;
    return (double) matched / query.size();
  }

  private double interaction(Set<String> first, Set<String> second) {
    Set<String> a = new HashSet<>(first);
    a.retainAll(query);
    Set<String> b = new HashSet<>(second);
    b.retainAll(query);
    if (a.isEmpty() || b.isEmpty()) return 0;
    a.addAll(b);
    return a.size() < 2 ? 0 : (double) a.size() / query.size();
  }
}
