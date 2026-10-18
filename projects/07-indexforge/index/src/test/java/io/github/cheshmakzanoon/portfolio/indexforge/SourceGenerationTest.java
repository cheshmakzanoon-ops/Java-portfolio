package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceGenerationTest {
  @TempDir Path root;

  private static SnapshotManifest manifest(ContentAddressedStore cas,String revision,String body) throws Exception {
    return new SnapshotManifest("tenant","repository",revision,true,List.of(
        new SnapshotManifest.Entry("Source.java",cas.put(body.getBytes(StandardCharsets.UTF_8)),"java","source",List.of("pulse"),"","")),Set.of());
  }
  private static int callers(Path generation) throws Exception {
    var facts=SourceRepositoryFacts.read(generation,"tenant","repository");
    var graph=new SourceSymbolGraph("tenant","repository",facts.documents(),new SourceSymbolGraph.Platform(){
      public Set<String> types(){return Set.of();}public Set<String> returns(String owner,String method,int arity){return Set.of();}public String fieldType(String owner,String field){return null;}
    });
    return graph.observe(List.of("tenant/repository/Source.java"),"pulse").values().iterator().next().members().getFirst().observedCallers();
  }

  @Test
  void publishedGraphChangesAtomicallyWhileOldLeaseKeepsItsOwnCanonicalPartition() throws Exception {
    Path index=root.resolve("index");var cas=new ContentAddressedStore(root.resolve("cas"));var builder=new GenerationBuilder(index,cas,GenerationBuilder.MAX_SOURCE_BYTES,true);
    String prefix="final class Source {private static int pulse(int n){return n;}int first(int n){return pulse(n);}";
    try(var publisher=new GenerationPublisher(index)) {
      var old=builder.build(manifest(cas,"old",prefix+"}"),List.of(),GenerationBuilder.Fault.none(),()->false);publisher.publish(old,GenerationBuilder.Fault.none());
      try(var lease=publisher.acquire()) {
        Path oldPath=index.resolve("generations").resolve(old.id());assertEquals(1,callers(oldPath));
        assertEquals(SourceFacts.SCHEMA,lease.reader().getIndexCommit().getUserData().get("source_schema"));
        assertEquals(SourceParserClient.bundleIdentity(),lease.reader().getIndexCommit().getUserData().get("source_worker"));
        var updated=builder.build(manifest(cas,"new",prefix+"int second(int n){return pulse(n);}}"),publisher.sources(),GenerationBuilder.Fault.none(),()->false);
        publisher.publish(updated,GenerationBuilder.Fault.none());
        assertNotEquals(old.id(),publisher.currentId());assertEquals(2,callers(index.resolve("generations").resolve(updated.id())));
        assertEquals(1,callers(oldPath));assertEquals(old.id(),lease.id());assertEquals(1,lease.reader().numDocs());
      }
    }
    try(var recovered=new GenerationPublisher(index)) {assertEquals(2,callers(index.resolve("generations").resolve(recovered.currentId())));}
  }

  @Test
  void postGraphPreCommitFailureAndCancellationLeaveCurrentUntouchedAndCorruptionCannotRecoverAsValid() throws Exception {
    Path index=root.resolve("index");var cas=new ContentAddressedStore(root.resolve("cas"));var builder=new GenerationBuilder(index,cas,GenerationBuilder.MAX_SOURCE_BYTES,true);
    var manifest=manifest(cas,"old","final class Source {private static int pulse(int n){return n;}int first(int n){return pulse(n);}}");String oldId;
    try(var publisher=new GenerationPublisher(index)) {
      var old=builder.build(manifest,List.of(),GenerationBuilder.Fault.none(),()->false);publisher.publish(old,GenerationBuilder.Fault.none());oldId=old.id();
      var denied=new AtomicBoolean(true);
      assertTrue(assertThrows(IOException.class,()->builder.build(manifest,publisher.sources(),GenerationBuilder.Fault.none(),denied::get)).getMessage().contains("cancelled"));
      assertEquals(oldId,publisher.currentId());
      IOException diskFull=new IOException("injected pre-commit storage failure");
      assertSame(diskFull,assertThrows(IOException.class,()->builder.build(manifest,publisher.sources(),boundary->{
        if(boundary==GenerationBuilder.Boundary.BEFORE_COMMIT)throw diskFull;
      },()->false)));
      assertEquals(oldId,publisher.currentId());assertEquals(1,callers(index.resolve("generations").resolve(oldId)));
    }
    // This boundary occurs after the graph write but before any pointer: staged partials quarantine.
    try(var recovered=new GenerationPublisher(index)){assertEquals(oldId,recovered.currentId());try(var lease=recovered.acquire()){assertEquals(1,lease.reader().numDocs());}}
    Path graph=index.resolve("generations").resolve(oldId).resolve(SourceRepositoryFacts.fileName("tenant","repository"));
    byte[] corrupt=Files.readAllBytes(graph);corrupt[corrupt.length-1]^=1;Files.write(graph,corrupt);
    assertThrows(IOException.class,()->new GenerationPublisher(index));
  }
}
