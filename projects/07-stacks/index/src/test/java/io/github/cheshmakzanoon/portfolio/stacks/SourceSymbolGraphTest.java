package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceSymbolGraphTest {
  private static final SourceSymbolGraph.Platform LANGUAGE_ONLY =
      new SourceSymbolGraph.Platform() {
        public Set<String> types() {
          return Set.of("java.lang.Object", "java.lang.String");
        }

        public Set<String> returns(String owner, String method, int arity) {
          return Set.of();
        }

        public String fieldType(String owner, String field) {
          return null;
        }
      };

  private static SourceSymbolGraph.Document document(String name, String source) throws Exception {
    var facts = SourceJavaParser.parse(source);
    assertTrue(facts.declarationInventoryKnown(), name);
    return new SourceSymbolGraph.Document("tenant/repository/" + name, facts);
  }

  private static SourceSymbolGraph graph(SourceSymbolGraph.Document... documents)
      throws IOException {
    return new SourceSymbolGraph("tenant", "repository", List.of(documents), LANGUAGE_ONLY);
  }

  @Test
  void exactPrivateMemberDiversityDeduplicatesRepeatedSitesAndSelfRecursion() throws Exception {
    var a =
        document(
            "First.java",
            """
        final class First {
          private static int bounded(int n) { if(n>0) return bounded(n-1); return n; }
          int read(int n) { bounded(n); bounded(n); return 0; }
        }
        """);
    var b =
        document(
            "Second.java",
            """
        final class Second {
          private static int bounded(int n) { return n; }
          int left(int n) { return bounded(n); }
          int right(int n) { return bounded(n); }
        }
        """);
    var view = graph(a, b).observe(List.of(a.key(), b.key()), "bounded");
    assertEquals(1, view.get(a.key()).members().getFirst().observedCallers());
    assertTrue(view.get(a.key()).members().getFirst().usable());
    assertEquals(2, view.get(b.key()).members().getFirst().observedCallers());
    assertTrue(view.get(b.key()).members().getFirst().usable());
    assertTrue(graph(a, b).observe(List.of(a.key()), "unrelated").get(a.key()).members().isEmpty());
  }

  @Test
  void crossFileExplicitFieldAndDeclaredReturnTypesBindWithoutExecutingHelpers() throws Exception {
    var store = document("Store.java", "package p; final class Store { void put(byte[] b){} }");
    var caller =
        document(
            "Caller.java",
            """
        package p;
        final class Caller {
          final Store store = new Store();
          Store get() { return store; }
          void first(byte[] b) { store.put(b); store.put(b); }
          void second(byte[] b) { var local = get(); local.put(b); }
        }
        """);
    var wrong =
        document(
            "Different.java",
            """
        package p;
        final class Different {
          void put(byte[] b){}
          void caller(byte[] b) { put(b); }
        }
        """);
    assertEquals(
        "var",
        caller.facts().members().stream()
            .filter(m -> m.name().equals("second"))
            .findFirst()
            .orElseThrow()
            .locals()
            .getFirst()
            .type());
    var graph = graph(store, caller, wrong);
    var view = graph.observe(List.of(store.key(), wrong.key()), "put");
    assertEquals(2, view.get(store.key()).members().getFirst().observedCallers());
    assertTrue(view.get(store.key()).members().getFirst().usable());
    assertEquals(1, view.get(wrong.key()).members().getFirst().observedCallers());
  }

  @Test
  void constantSpecificEnumBodiesPreventExactInstanceDispatchButPreserveStaticBindings()
      throws Exception {
    var source =
        document(
            "Mode.java",
            """
            enum Mode {
              FIRST { @Override int read() { return 2; } }, SECOND;
              int read() { return 1; }
              static int shared() { return 3; }
              int caller() { read(); return shared(); }
            }
            """);
    var type =
        source.facts().types().stream()
            .filter(t -> t.name().equals("Mode"))
            .findFirst()
            .orElseThrow();
    assertFalse(type.closed(), "a constant-specific body makes an enum extensible");
    var reads = graph(source).observe(List.of(source.key()), "read").get(source.key());
    assertEquals(2, reads.members().size());
    assertTrue(reads.members().stream().noneMatch(SourceSymbolGraph.Usage::usable));
    var shared = graph(source).observe(List.of(source.key()), "shared").get(source.key());
    assertEquals(1, shared.members().getFirst().observedCallers());
    assertTrue(shared.members().getFirst().usable());

    var finalEnum =
        document(
            "FinalMode.java",
            "enum FinalMode { FIRST, SECOND; int read(){return 1;} int caller(){return read();} }");
    assertTrue(finalEnum.facts().types().getFirst().closed());
    var exact = graph(finalEnum).observe(List.of(finalEnum.key()), "read").get(finalEnum.key());
    assertEquals(1, exact.members().getFirst().observedCallers());
    assertTrue(exact.members().getFirst().usable());
  }

  @Test
  void inheritedOverloadsNullGenericAndUnknownReceiversAreNotGuessed() throws Exception {
    var inherited =
        document(
            "A.java",
            """
        class A extends Missing {
          private static void put(Object o){}
          void call() { put("x"); }
        }
        """);
    var overload =
        document(
            "B.java",
            """
        final class B {
          private static void put(String s){}
          private static void put(Object o){}
          void call() { put(null); }
        }
        """);
    var partial =
        document(
            "C.java",
            """
        final class C {
          void put(byte[] b){}
          <T> void generic(T t) { put(t); }
        }
        """);
    var view =
        graph(inherited, overload, partial)
            .observe(List.of(inherited.key(), overload.key(), partial.key()), "put");
    for (var inventory : view.values())
      assertTrue(inventory.members().stream().noneMatch(SourceSymbolGraph.Usage::usable));
    assertEquals(2, view.get(overload.key()).members().size());
  }

  @Test
  void unsupportedInventoryRemainsUnknownAndCrossRepositoryInputsAreRejected() throws Exception {
    var known = document("Known.java", "final class Known { void read(int x){} }");
    var unsupported =
        new SourceSymbolGraph.Document(
            "tenant/repository/Large.java",
            SourceFacts.Unit.unknown(SourceFacts.Status.SOURCE_QUOTA));
    var view = graph(known, unsupported).observe(List.of(known.key(), unsupported.key()), "read");
    assertFalse(view.get(unsupported.key()).membershipKnown());
    assertTrue(view.get(unsupported.key()).members().isEmpty());
    assertFalse(view.get(known.key()).members().getFirst().usable());
    var denied = new SourceSymbolGraph.Document("tenant/denied/Elsewhere.java", known.facts());
    assertThrows(IOException.class, () -> graph(known, denied));
    assertThrows(
        SecurityException.class, () -> graph(known).observe(List.of(denied.key()), "read"));
    assertThrows(IOException.class, () -> graph(known, known));
  }

  @Test
  void nestedOwnersAndOverloadsNeverPoolAndTypeMatchesCannotBecomeMentions() throws Exception {
    var unit =
        document(
            "Owners.java",
            """
        final class Owners {
          private static int bytes(int n) { return n; }
          int caller(int n) { return bytes(n); }
          static final class Other {
            private static int bytes(int n) { return n; }
            int caller(int n) { return bytes(n); }
          }
        }
        """);
    var view = graph(unit).observe(List.of(unit.key()), "bytes").get(unit.key());
    assertEquals(2, view.members().size());
    assertEquals(
        2, view.members().stream().map(SourceSymbolGraph.Usage::identity).distinct().count());
    assertTrue(view.members().stream().allMatch(x -> x.observedCallers() == 1 && x.usable()));
    var type = graph(unit).observe(List.of(unit.key()), "Owners").get(unit.key());
    assertFalse(type.members().getFirst().usable());
  }

  @Test
  void localSummaryRequiresActualDirectFinalReturnAndNeverComposesSecondCustomHop()
      throws Exception {
    var unit =
        document(
            "Helpers.java",
            """
        final class Helpers {
          private static int direct(int value) { return value; }
          private static int conditional(int value) { if(value>0) return value; }
          private static int nested(int value) { return direct(value); }
          int caller(int value) { direct(value); conditional(value); return nested(value); }
        }
        """);
    var caller =
        unit.facts().members().stream()
            .filter(m -> m.name().equals("caller"))
            .findFirst()
            .orElseThrow();
    var graph = graph(unit);
    for (var call : caller.calls()) {
      var summary = graph.localHelper(unit.key(), caller, call, new SourceSymbolGraph.Work());
      assertEquals(
          call.expression().text().equals("direct"), summary.isPresent(), call.expression().text());
    }
    assertEquals(unit.facts(), SourceWire.decode(SourceWire.encode(unit.facts())));
  }

  @Test
  void laterOrDisjointFieldShadowingNeverFabricatesAnIncomingCall() throws Exception {
    String types = "final class A { void put(){} } final class B { void put(){} } ";
    for (String body :
        List.of(
            "x.put(); {B x=new B();}",
            "{B x=new B();} x.put();",
            "{B x=new B(); x.put();} x.put();")) {
      var source =
          document(
              "Scoped.java", types + "final class X {final A x=new A(); void run(){" + body + "}}");
      var inventory = graph(source).observe(List.of(source.key()), "put").get(source.key());
      assertEquals(2, inventory.members().size());
      assertTrue(
          inventory.members().stream().allMatch(m -> m.observedCallers() == 0 && !m.usable()),
          "unsupported field/local shadowing must abstain, never invent a B caller");
    }
    var unshadowed =
        document(
            "Unshadowed.java",
            types
                + "final class X { final A x=new A(); void run(){x.put(); {B different=new B();}} }");
    var facts = graph(unshadowed).observe(List.of(unshadowed.key()), "put").get(unshadowed.key());
    assertEquals(
        List.of(0, 1),
        facts.members().stream().map(SourceSymbolGraph.Usage::observedCallers).sorted().toList());
    assertEquals(1, facts.members().stream().filter(SourceSymbolGraph.Usage::usable).count());
  }

  @Test
  void omittedLambdaCallsAreUnknownCallInventoryNotACompleteZero() throws Exception {
    var source =
        document(
            "Lambda.java",
            """
        final class Lambda {
          private static int touch(int value){return value;}
          int known(int value){return touch(value);}
          void deferred(int value){ Runnable task=()->touch(value); }
        }
        """);
    var usage =
        graph(source)
            .observe(List.of(source.key()), "touch")
            .get(source.key())
            .members()
            .getFirst();
    assertEquals(1, usage.observedCallers());
    assertFalse(usage.usable());
    assertTrue(source.facts().declarationInventoryKnown());
    assertTrue(source.facts().members().stream().anyMatch(m -> !m.callsComplete()));
    assertEquals(source.facts(), SourceWire.decode(SourceWire.encode(source.facts())));
  }

  @Test
  void implicitStaticImportsAndEnclosingCallsNeverBecomeFavorablyCompletePartialCounts()
      throws Exception {
    var target =
        document(
            "A.java",
            "package p; final class A {static void put(int n){} static void g(){put(1);}}");
    var imported =
        document("B.java", "package p; import static p.A.put; final class B {void f(){put(1);}}");
    var usage =
        graph(target, imported)
            .observe(List.of(target.key()), "put")
            .get(target.key())
            .members()
            .getFirst();
    assertEquals(1, usage.observedCallers());
    assertFalse(usage.usable(), "unresolved imported caller cannot be a complete count");
    var explicit = document("B.java", "package p; final class B {void f(){A.put(1);}}");
    var resolved =
        graph(target, explicit)
            .observe(List.of(target.key()), "put")
            .get(target.key())
            .members()
            .getFirst();
    assertEquals(2, resolved.observedCallers());
    assertTrue(resolved.usable());
    var nested =
        document(
            "Nest.java",
            "final class Nest {private static void put(int n){} static void g(){put(1);} static final class Inner {void f(){put(1);}}}");
    var inner =
        graph(nested).observe(List.of(nested.key()), "put").get(nested.key()).members().getFirst();
    assertEquals(1, inner.observedCallers());
    assertFalse(inner.usable());
    var member =
        nested.facts().members().stream()
            .filter(m -> m.name().equals("f"))
            .findFirst()
            .orElseThrow();
    assertEquals("implicit", member.calls().getFirst().expression().children().getFirst().kind());
  }

  @Test
  void provenThirtyTwoCallersSaturateWithoutPoolingRepeatedSitesAndUnknownInventoryRemainsExplicit()
      throws Exception {
    var documents = new ArrayList<SourceSymbolGraph.Document>();
    var target = document("Target.java", "final class Target {static void pulse(int value){}}");
    documents.add(target);
    for (int i = 0; i < 33; i++)
      documents.add(
          document(
              "Caller" + i + ".java",
              "final class Caller" + i + " {void run(){Target.pulse(1);Target.pulse(1);}}"));
    documents.add(
        new SourceSymbolGraph.Document(
            "tenant/repository/Unknown.java",
            SourceFacts.Unit.unknown(SourceFacts.Status.SOURCE_QUOTA)));
    var graph = new SourceSymbolGraph("tenant", "repository", documents, LANGUAGE_ONLY);
    var usage =
        graph.observe(List.of(target.key()), "pulse").get(target.key()).members().getFirst();
    assertTrue(usage.usable());
    assertEquals(32, usage.observedCallers());
    var work = new SourceSymbolGraph.Work();
    for (int i = 0; i < 65_536; i++) work.step();
    assertThrows(SourceSymbolGraph.Budget.class, work::step);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SourceSymbolGraph("../tenant", "repository", documents, LANGUAGE_ONLY));
    assertThrows(
        IllegalArgumentException.class,
        () -> graph.observe(List.of(target.key()), "x".repeat(129)));
  }

  @Test
  void recordAccessorAndPrimitiveOverloadBindingNeverMergeSameTerminalNames() throws Exception {
    var source =
        document(
            "Values.java",
            """
      record Box(int value) {}
      final class Values {
        private static int take(int n){return n;}
        private static boolean take(boolean n){return n;}
        int first(Box box){return take(box.value());}
        boolean second(){return take(true);}
        void unrelated(String value){value.take(1,2);}
      }
      """);
    var inventory = graph(source).observe(List.of(source.key()), "take").get(source.key());
    assertEquals(2, inventory.members().size());
    assertTrue(inventory.members().stream().allMatch(m -> m.usable() && m.observedCallers() == 1));
    var copy =
        document(
            "Renamed.java",
            """
      record Holder(int component) {}
      final class Renamed {
        private static int perform(int n){return n;}
        private static boolean perform(boolean n){return n;}
        int left(Holder box){return perform(box.component());}
        boolean right(){return perform(true);}
      }
      """);
    var renamed = graph(copy).observe(List.of(copy.key()), "perform").get(copy.key());
    assertEquals(
        inventory.members().stream().map(SourceSymbolGraph.Usage::observedCallers).toList(),
        renamed.members().stream().map(SourceSymbolGraph.Usage::observedCallers).toList());
  }

  @Test
  void
      ambiguousImportsDuplicateOwnersUnknownWildcardsAndMethodReferencesNeverYieldPartialCompleteCounts()
          throws Exception {
    var a =
        document(
            "p/A.java",
            "package p; final class A {static void pulse(int n){}static void known(){pulse(1);}}");
    var b = document("q/A.java", "package q; final class A {static void pulse(int n){}}");
    for (String imports :
        List.of(
            "import p.A;import q.A;", "import p.*;import q.*;", "import p.*;import missing.*;")) {
      var caller =
          document("Caller.java", imports + "final class Caller {void run(){A.pulse(1);}}");
      var usage =
          graph(a, b, caller).observe(List.of(a.key()), "pulse").get(a.key()).members().getFirst();
      assertEquals(1, usage.observedCallers());
      assertFalse(usage.usable(), imports);
    }
    var duplicate =
        document("duplicate/A.java", "package p; final class A {static void pulse(int n){}}");
    assertTrue(
        graph(a, duplicate).observe(List.of(a.key()), "pulse").get(a.key()).members().stream()
            .noneMatch(SourceSymbolGraph.Usage::usable));
    var reference =
        document(
            "Ref.java",
            "final class Ref {private static void pulse(int n){}void known(){pulse(1);}Object ref(){return Ref::pulse;}}");
    var observed =
        graph(reference)
            .observe(List.of(reference.key()), "pulse")
            .get(reference.key())
            .members()
            .getFirst();
    assertEquals(1, observed.observedCallers());
    assertFalse(observed.usable());
    assertThrows(
        IllegalArgumentException.class,
        () -> graph(a).observe(Collections.nCopies(501, a.key()), "pulse"));
    assertFalse(
        graph(a)
            .observe(List.of("tenant/repository/Absent.java"), "pulse")
            .values()
            .iterator()
            .next()
            .membershipKnown());
  }

  @Test
  void
      nonfinalVirtualReceiversAndWideningOverloadAmbiguityAbstainWhileUniqueStaticCallsRemainUsable()
          throws Exception {
    var virtual =
        document("Virtual.java", "class Virtual {void pulse(int n){}void caller(){pulse(1);}}");
    assertFalse(
        graph(virtual)
            .observe(List.of(virtual.key()), "pulse")
            .get(virtual.key())
            .members()
            .getFirst()
            .usable());
    var statik =
        document(
            "Static.java", "class Static {static void pulse(int n){}void caller(){pulse(1);}}");
    var unique =
        graph(statik)
            .observe(List.of(statik.key()), "pulse")
            .get(statik.key())
            .members()
            .getFirst();
    assertTrue(unique.usable());
    assertEquals(1, unique.observedCallers());
    var widening =
        document(
            "Widening.java",
            "final class Widening {static void pulse(int n){}static void pulse(long n){}void call(){pulse(1);}}");
    assertTrue(
        graph(widening)
            .observe(List.of(widening.key()), "pulse")
            .get(widening.key())
            .members()
            .stream()
            .noneMatch(SourceSymbolGraph.Usage::usable));
    var unrelated =
        document(
            "Unrelated.java",
            "final class Unrelated {private static void pulse(int n){}void known(){pulse(1);}void other(String value){value.pulse(1);}}");
    var isolated =
        graph(unrelated)
            .observe(List.of(unrelated.key()), "pulse")
            .get(unrelated.key())
            .members()
            .getFirst();
    assertTrue(isolated.usable());
    assertEquals(1, isolated.observedCallers());
  }
}
