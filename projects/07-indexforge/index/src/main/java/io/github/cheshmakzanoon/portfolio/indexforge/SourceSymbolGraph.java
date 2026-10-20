package io.github.cheshmakzanoon.portfolio.indexforge;

import static io.github.cheshmakzanoon.portfolio.indexforge.SourceBindingResolver.*;

import java.io.IOException;
import java.util.*;

/** Repository-local source observations, never complete-program usage or javac attribution. */
public final class SourceSymbolGraph {
  public static final String SCHEMA = "observed-source-callers-v1";
  public static final int CALLER_CAP = 32;

  /** Generated, pinned JDK API signatures. There is no reflection/loading of source classes. */
  public interface Platform {
    Set<String> types();

    Set<String> returns(String owner, String method, int arity);

    String fieldType(String owner, String field);
  }

  public record Document(String key, SourceFacts.Unit facts) {
    public Document {
      Objects.requireNonNull(key);
      Objects.requireNonNull(facts);
      if (key.length() > 2048) throw new IllegalArgumentException("source key");
    }
  }

  public record Usage(String identity, String javaName, int observedCallers, boolean usable) {}

  public record Inventory(boolean membershipKnown, List<Usage> members) {
    public Inventory {
      members = List.copyOf(members);
    }
  }

  public static final class Work {
    private int remaining = 65_536;

    public void step() {
      if (--remaining < 0) throw new Budget();
    }
  }

  public static final class Budget extends RuntimeException {}

  private final String prefix;
  private final Platform platform;
  private final Map<String, Document> documents = new TreeMap<>();
  private final SourceBindingResolver bindings;
  private final Map<String, List<CallRef>> calls = new HashMap<>();
  private final Set<String> incompleteCallDocuments = new HashSet<>();
  private final boolean sourceInventoryObserved;

  public SourceSymbolGraph(
      String tenant, String repository, Collection<Document> source, Platform platform)
      throws IOException {
    if (!tenant.matches("[a-zA-Z0-9_-]{1,64}") || !repository.matches("[a-zA-Z0-9_-]{1,64}"))
      throw new IllegalArgumentException("graph scope");
    this.platform = Objects.requireNonNull(platform);
    prefix = tenant + "/" + repository + "/";
    if (source.size() > 100_000) throw new IOException("graph document quota");
    Map<String, List<TypeRef>> types = new HashMap<>();
    Map<String, List<MemberRef>> methods = new HashMap<>();
    int memberCount = 0, edgeCount = 0;
    long bytes = 0, decodedBytes = 0;
    boolean observed = true;
    for (var document : source) {
      if (!document.key().startsWith(prefix)
          || documents.putIfAbsent(document.key(), document) != null)
        throw new IOException("cross-repository/duplicate graph unit");
      byte[] encoded = SourceWire.encode(document.facts());
      bytes += encoded.length + 3L * document.key().length();
      decodedBytes += SourceWire.decodedBytes(encoded);
      if (bytes > 33_554_432) throw new IOException("graph serialized quota");
      if (decodedBytes > 8_388_608) throw new IOException("graph decoded query-work quota");
      observed &= document.facts().declarationInventoryKnown();
      for (var type : document.facts().types()) {
        types.computeIfAbsent(type.name(), k -> new ArrayList<>()).add(new TypeRef(document, type));
        if (types.size() > 65_536) throw new IOException("graph type quota");
      }
      for (var member : document.facts().members()) {
        if (!member.callsComplete()) incompleteCallDocuments.add(document.key());
        if (++memberCount > 65_536) throw new IOException("graph member quota");
        var ref = new MemberRef(document, member);
        methods
            .computeIfAbsent(member.owner() + "#" + member.name(), k -> new ArrayList<>())
            .add(ref);
        for (var call : member.calls()) {
          if (++edgeCount > 262_144) throw new IOException("graph call quota");
          calls
              .computeIfAbsent(call.expression().text(), k -> new ArrayList<>())
              .add(new CallRef(ref, call));
        }
      }
    }
    sourceInventoryObserved = observed;
    bindings = new SourceBindingResolver(platform, types, methods);
  }

  /**
   * Caller supplies only authorized source keys; one work budget spans the whole candidate view.
   */
  public Map<String, Inventory> observe(Collection<String> candidateKeys, String query) {
    return observe(candidateKeys, query, new Work());
  }

  public Map<String, Inventory> observe(Collection<String> candidateKeys, String query, Work work) {
    if (candidateKeys.size() > 500 || query.length() > 128)
      throw new IllegalArgumentException("graph query quota");
    var result = new LinkedHashMap<String, Inventory>();
    for (String key : candidateKeys) {
      if (!key.startsWith(prefix)) throw new SecurityException("graph scope mismatch");
      Document document = documents.get(key);
      if (document == null || !document.facts().declarationInventoryKnown()) {
        result.put(key, new Inventory(false, List.of()));
        continue;
      }
      List<Usage> usage = new ArrayList<>();
      // A known type declaration is not silently omitted from the exact-name subset. This
      // wave has no type-reference count: such a match preserves the whole original order.
      for (var type : document.facts().types())
        if (simple(type.name()).equalsIgnoreCase(query))
          usage.add(
              new Usage(
                  Digests.sha256(key + "/type/" + type.name()), simple(type.name()), 0, false));
      for (var member : document.facts().members())
        if (member.name().equalsIgnoreCase(query))
          usage.add(observe(new MemberRef(document, member), work));
      result.put(key, new Inventory(true, usage));
    }
    return Collections.unmodifiableMap(result);
  }

  public Optional<SourceApiCatalog.Entry> api(
      String sourceKey, SourceFacts.Member member, SourceFacts.Invocation call, Work work) {
    Document document = documents.get(sourceKey);
    if (document == null || !document.facts().members().contains(member))
      throw new IllegalArgumentException("API source identity");
    if (!(platform instanceof SourceApiCatalog catalog) || isReference(call.expression()))
      return Optional.empty();
    var context = new MemberRef(document, member);
    var expression = call.expression();
    Bound receiver =
        bindings.typeOf(expression.children().getFirst(), context, work, 0, new HashSet<>());
    if (receiver.source() != null || !catalog.types().contains(receiver.name()))
      return Optional.empty();
    List<String> args = new ArrayList<>();
    for (int i = 1; i < expression.children().size(); i++) {
      Bound arg = bindings.typeOf(expression.children().get(i), context, work, 0, new HashSet<>());
      if (!arg.known() || arg.name().startsWith("nominal-") || arg.name().equals("null"))
        return Optional.empty();
      args.add(arg.name());
    }
    return catalog.unique(receiver.name(), expression.text(), args);
  }

  /** Actual selected catalog field; the same source/shadow rules as API receivers apply. */
  public Optional<SourceApiCatalog.Entry> field(
      String sourceKey, SourceFacts.Member member, SourceFacts.Expression expression, Work work) {
    Document document = documents.get(sourceKey);
    if (document == null || !document.facts().members().contains(member))
      throw new IllegalArgumentException("field source identity");
    if (!(platform instanceof SourceApiCatalog catalog)
        || member.bindingUnknown()
        || !expression.kind().equals("select")) return Optional.empty();
    Bound receiver =
        bindings.typeOf(
            expression.children().getFirst(),
            new MemberRef(document, member),
            work,
            0,
            new HashSet<>());
    if (receiver.source() != null || !catalog.types().contains(receiver.name()))
      return Optional.empty();
    return catalog.field(receiver.name(), expression.text());
  }

  /** A single supported source helper in this compilation unit; never external attribution. */
  public Optional<SourceFacts.Member> localHelper(
      String sourceKey, SourceFacts.Member caller, SourceFacts.Invocation call, Work work) {
    Document document = documents.get(sourceKey);
    if (document == null || !document.facts().members().contains(caller))
      throw new IllegalArgumentException("helper source identity");
    Resolution resolved =
        bindings.resolve(
            new CallRef(new MemberRef(document, caller), call), work, 0, new HashSet<>());
    if (resolved.target() == null
        || resolved.unresolved()
        || !resolved.target().document().key().equals(sourceKey)) return Optional.empty();
    var target = resolved.target().member();
    if (target.signature().equals(caller.signature())
        || target.bindingUnknown()
        || target.controlUnknown()
        || !target.callsComplete()
        || target.returned().size() != 1
        || !target.returned().getFirst().directFinal()) return Optional.empty();
    if (!target.privateMember() && !target.staticMember()) return Optional.empty();
    // Exactly one custom hop. Every invocation inside this helper must itself be a supported
    // actual JDK endpoint; a recursive, virtual or second custom call cannot be summarized.
    for (var nested : target.calls())
      if (api(sourceKey, target, nested, work).isEmpty()) return Optional.empty();
    return Optional.of(target);
  }

  private Usage observe(MemberRef target, Work work) {
    var member = target.member();
    Set<String> callers = new HashSet<>();
    TypeRef owner = bindings.unique(member.owner());
    boolean unknown =
        owner == null
            || owner.type().unknownSupertype()
            || member.bindingUnknown()
            || (!member.privateMember() && !sourceInventoryObserved)
            || (member.privateMember()
                ? incompleteCallDocuments.contains(target.document().key())
                : !incompleteCallDocuments.isEmpty());
    for (var call : calls.getOrDefault(member.name(), List.of())) {
      work.step();
      if (call.caller().key().equals(target.key())) continue;
      if (member.privateMember() && !call.caller().document().key().equals(target.document().key()))
        continue;
      // No caller outside this repository was added to the map or type-resolution context.
      Resolution bound = bindings.resolve(call, work, 0, new HashSet<>());
      if (bound.target() != null && bound.target().key().equals(target.key())) {
        if (!call.caller().key().equals(target.key())) callers.add(call.caller().key());
      } else if (bound.unresolved() && bindings.potential(call, target, work)) unknown = true;
      if (callers.size() == CALLER_CAP)
        return new Usage(Digests.sha256(target.key()), member.name(), CALLER_CAP, true);
    }
    return new Usage(
        Digests.sha256(target.key()),
        member.name(),
        callers.size(),
        !unknown && !callers.isEmpty());
  }
}
