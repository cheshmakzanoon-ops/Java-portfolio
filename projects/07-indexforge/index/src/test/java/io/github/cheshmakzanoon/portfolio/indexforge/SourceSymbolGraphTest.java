package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceSymbolGraphTest {
  private static final SourceSymbolGraph.Platform LANGUAGE_ONLY=new SourceSymbolGraph.Platform() {
    public Set<String> types(){return Set.of("java.lang.Object","java.lang.String");}
    public Set<String> returns(String owner,String method,int arity){return Set.of();}
    public String fieldType(String owner,String field){return null;}
  };
  private static SourceSymbolGraph.Document document(String name,String source)throws Exception {
    var facts=SourceJavaParser.parse(source);assertTrue(facts.declarationInventoryKnown(),name);
    return new SourceSymbolGraph.Document("tenant/repository/"+name,facts);
  }
  private static SourceSymbolGraph graph(SourceSymbolGraph.Document... documents)throws IOException {
    return new SourceSymbolGraph("tenant","repository",List.of(documents),LANGUAGE_ONLY);
  }

  @Test void exactPrivateMemberDiversityDeduplicatesRepeatedSitesAndSelfRecursion()throws Exception {
    var a=document("First.java","""
        final class First {
          private static int bounded(int n) { if(n>0) return bounded(n-1); return n; }
          int read(int n) { bounded(n); bounded(n); return 0; }
        }
        """);
    var b=document("Second.java","""
        final class Second {
          private static int bounded(int n) { return n; }
          int left(int n) { return bounded(n); }
          int right(int n) { return bounded(n); }
        }
        """);
    var view=graph(a,b).observe(List.of(a.key(),b.key()),"bounded");
    assertEquals(1,view.get(a.key()).members().getFirst().observedCallers());
    assertTrue(view.get(a.key()).members().getFirst().usable());
    assertEquals(2,view.get(b.key()).members().getFirst().observedCallers());
    assertTrue(view.get(b.key()).members().getFirst().usable());
    assertTrue(graph(a,b).observe(List.of(a.key()),"unrelated").get(a.key()).members().isEmpty());
  }

  @Test void crossFileExplicitFieldAndDeclaredReturnTypesBindWithoutExecutingHelpers()throws Exception {
    var store=document("Store.java","package p; final class Store { void put(byte[] b){} }");
    var caller=document("Caller.java","""
        package p;
        final class Caller {
          final Store store = new Store();
          Store get() { return store; }
          void first(byte[] b) { store.put(b); store.put(b); }
          void second(byte[] b) { var local = get(); local.put(b); }
        }
        """);
    var wrong=document("Different.java","""
        package p;
        final class Different {
          void put(byte[] b){}
          void caller(byte[] b) { put(b); }
        }
        """);
    var graph=graph(store,caller,wrong);var view=graph.observe(List.of(store.key(),wrong.key()),"put");
    assertEquals(2,view.get(store.key()).members().getFirst().observedCallers());
    assertTrue(view.get(store.key()).members().getFirst().usable());
    assertEquals(1,view.get(wrong.key()).members().getFirst().observedCallers());
  }

  @Test void inheritedOverloadsNullGenericAndUnknownReceiversAreNotGuessed()throws Exception {
    var inherited=document("A.java","""
        class A extends Missing {
          private static void put(Object o){}
          void call() { put("x"); }
        }
        """);
    var overload=document("B.java","""
        final class B {
          private static void put(String s){}
          private static void put(Object o){}
          void call() { put(null); }
        }
        """);
    var partial=document("C.java","""
        final class C {
          void put(byte[] b){}
          <T> void generic(T t) { put(t); }
        }
        """);
    var view=graph(inherited,overload,partial).observe(List.of(inherited.key(),overload.key(),partial.key()),"put");
    for(var inventory:view.values())assertTrue(inventory.members().stream().noneMatch(SourceSymbolGraph.Usage::usable));
    assertEquals(2,view.get(overload.key()).members().size());
  }

  @Test void unsupportedInventoryRemainsUnknownAndCrossRepositoryInputsAreRejected()throws Exception {
    var known=document("Known.java","final class Known { void read(int x){} }");
    var unsupported=new SourceSymbolGraph.Document("tenant/repository/Large.java",SourceFacts.Unit.unknown(SourceFacts.Status.SOURCE_QUOTA));
    var view=graph(known,unsupported).observe(List.of(known.key(),unsupported.key()),"read");
    assertFalse(view.get(unsupported.key()).membershipKnown());
    assertTrue(view.get(unsupported.key()).members().isEmpty());
    assertFalse(view.get(known.key()).members().getFirst().usable());
    var denied=new SourceSymbolGraph.Document("tenant/denied/Elsewhere.java",known.facts());
    assertThrows(IOException.class,()->graph(known,denied));
    assertThrows(SecurityException.class,()->graph(known).observe(List.of(denied.key()),"read"));
    assertThrows(IOException.class,()->graph(known,known));
  }

  @Test void nestedOwnersAndOverloadsNeverPoolAndTypeMatchesCannotBecomeMentions()throws Exception {
    var unit=document("Owners.java","""
        final class Owners {
          private static int bytes(int n) { return n; }
          int caller(int n) { return bytes(n); }
          static final class Other {
            private static int bytes(int n) { return n; }
            int caller(int n) { return bytes(n); }
          }
        }
        """);
    var view=graph(unit).observe(List.of(unit.key()),"bytes").get(unit.key());
    assertEquals(2,view.members().size());
    assertEquals(2,view.members().stream().map(SourceSymbolGraph.Usage::identity).distinct().count());
    assertTrue(view.members().stream().allMatch(x->x.observedCallers()==1&&x.usable()));
    var type=graph(unit).observe(List.of(unit.key()),"Owners").get(unit.key());
    assertFalse(type.members().getFirst().usable());
  }
}
