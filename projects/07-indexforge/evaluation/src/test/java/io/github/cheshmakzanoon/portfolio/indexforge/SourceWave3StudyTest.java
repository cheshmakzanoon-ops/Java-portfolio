package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic workflow fixtures only: never opens the authored relevance corpus or its scores. */
class SourceWave3StudyTest {
  @TempDir Path root;

  private record Inputs(Path fixtures,Path catalog,Path output,Path data) {}

  private Inputs inputs() throws Exception {
    Path fixtures=Files.createDirectory(root.resolve("fixtures"));
    Path wave=Files.createDirectory(fixtures.resolve("ranking-wave3"));
    var judgments=new ArrayList<String>();
    var headers=new ArrayList<Wave2Inputs.Header>();
    for(int i=0;i<60;i++) {
      String id="q"+i,split=i<40?"development":"validation",type=i%2==0?"prose":"identifier",text=i%2==0?"process source":"pulse";
      judgments.add(id+"\t"+split+"\t"+type+"\t"+text+"\tPlain.txt:3,Missing"+(i%17)+".txt:1");
      headers.add(new Wave2Inputs.Header(id,split,type,text));
    }
    // Even invalid held-out query text/grades must never be parsed by any phase.
    judgments.add("secret\theld-out\tINVALID\t( malformed\tINVALID");
    Files.write(fixtures.resolve("judgments.tsv"),judgments);
    Files.writeString(fixtures.resolve("corpus.tsv"),"Plain.txt\tpulse process source\t"+Base64.getEncoder().encodeToString("pulse process source".getBytes(StandardCharsets.UTF_8))+"\n");
    Files.writeString(fixtures.resolve("REVIEW-APPROVED.md"),"Synthetic correctness fixtures, no relevance evidence.\n");
    Files.write(fixtures.resolve("FREEZE.sha256"),List.of(SourceStudyFiles.hash(fixtures.resolve("judgments.tsv"))+"  judgments.tsv",SourceStudyFiles.hash(fixtures.resolve("corpus.tsv"))+"  corpus.tsv"));
    var devHeaders=headers.subList(0,40);var labels=Wave2Inputs.labels(fixtures,devHeaders);
    var empty=devHeaders.stream().map(h->new PairwiseData.Query(h.id(),Wave2Inputs.judgmentGroup(labels.get(h.id())),h.text(),"development",List.<PairwiseData.Row>of())).toList();
    var assignments=GroupedFolds.assign(empty);var foldLines=new ArrayList<String>();foldLines.add("query\tjudgmentVectorSha256\tfold");
    for(var query:empty)foldLines.add(query.id()+"\t"+query.group()+"\t"+assignments.get(query.id()));
    Files.write(wave.resolve("folds.tsv"),foldLines);
    var reference=new ArrayList<String>();reference.add("mode\tquery\tsplit\ttype\tndcg10\tmrr10\trecall20\treturnedPaths");
    for(var mode:QueryPlanner.Mode.legacyValues())for(var header:headers) {
      var metric=RelevanceMetrics.evaluate(List.of("Plain.txt"),Map.of("Plain.txt",3,"Missing.txt",1));
      reference.add(mode+"\t"+header.id()+"\t"+header.split()+"\t"+header.type()+"\t"+metric.ndcg10()+"\t"+metric.mrr10()+"\t"+metric.recall20()+"\tPlain.txt");
    }
    Files.write(wave.resolve("comparator-reference.tsv"),reference);Files.write(wave.resolve("r0-reference.tsv"),reference);
    Files.writeString(wave.resolve("PREREGISTRATION.md"),"Synthetic no-new-evidence workflow; original baseline tie rule.\n");
    for(int i=1;i<=5;i++)Files.writeString(wave.resolve("IMPLEMENTATION-ADDENDUM-"+i+".md"),"Synthetic protocol binding "+i);
    freeze(wave);
    Path catalogFile=root.resolve("catalog.bin");
    new SourceApiCatalog(SourceStudyFiles.hash(Path.of(System.getProperty("java.home"),"lib/src.zip")),
        SourceParserClient.bundleIdentity(),System.getProperty("java.runtime.version"),"Synthetic fixture, not production API prose",Set.of(),List.of()).writeNew(catalogFile);
    return new Inputs(fixtures,catalogFile,root.resolve("output"),root.resolve("data"));
  }

  private static void freeze(Path wave) throws Exception {
    var names=List.of("PREREGISTRATION.md","folds.tsv","comparator-reference.tsv","r0-reference.tsv",
        "IMPLEMENTATION-ADDENDUM-1.md","IMPLEMENTATION-ADDENDUM-2.md","IMPLEMENTATION-ADDENDUM-3.md",
        "IMPLEMENTATION-ADDENDUM-4.md","IMPLEMENTATION-ADDENDUM-5.md");
    var lines=new ArrayList<String>();for(String name:names)lines.add(SourceStudyFiles.hash(wave.resolve(name))+"  "+name);
    Files.write(wave.resolve("FREEZE.sha256"),lines);
  }
  private static void run(String phase,Inputs i) throws Exception {SourceWave3Study.run(phase,i.data(),i.fixtures(),i.output(),i.catalog());}

  @Test
  void allThreeActualPhasesCompleteWithoutNewEvidencePreserveBaselineAndRejectRerunsAndTampering() throws Exception {
    var i=inputs();
    run("audit",i);
    var packet=SourceFeaturePacket.read(i.output().resolve("audit/features.tsv"));assertEquals(40,packet.queries().size());
    assertTrue(packet.queries().stream().allMatch(q->q.candidates().stream().allMatch(c->{for(int d=10;d<16;d++)if(c.features()[d]!=0)return false;return true;})));
    String packetHash=SourceStudyFiles.hash(i.output().resolve("audit/features.tsv"));
    assertThrows(FileAlreadyExistsException.class,()->run("audit",i));
    run("fit",i);
    var fit=SourceStudyFiles.read(i.output().resolve("fit/identity.properties"));
    assertEquals("NONE",fit.get("R1"));assertEquals("NONE",fit.get("R2"));assertEquals("false",fit.get("R3"));
    assertTrue(Files.readString(i.output().resolve("fit/report.txt")).contains("NOT_ATTEMPTED_SOURCE_AUDIT_CONDITION"));
    assertThrows(FileAlreadyExistsException.class,()->run("fit",i));
    Path report=i.output().resolve("fit/report.txt");byte[] original=Files.readAllBytes(report);Files.writeString(report,"tamper");
    assertThrows(IOException.class,()->run("validate",i));assertFalse(Files.exists(i.output().resolve("validation")));Files.write(report,original);
    Path undeclared=i.output().resolve("fit/undeclared.bin");Files.writeString(undeclared,"injected");
    assertThrows(IOException.class,()->run("validate",i));assertFalse(Files.exists(i.output().resolve("validation")));Files.delete(undeclared);
    run("validate",i);
    assertEquals(1561,Files.readAllLines(i.output().resolve("validation/per-query.tsv")).size());
    var selection=SourceStudyFiles.read(i.output().resolve("validation/selection.properties"));
    assertEquals("ORIGINAL_BASELINE",selection.get("family"));assertEquals("BASELINE",selection.get("mode"));assertEquals("NONE",selection.get("model"));
    assertEquals(packetHash,SourceStudyFiles.hash(i.output().resolve("audit/features.tsv")));
    assertThrows(FileAlreadyExistsException.class,()->run("validate",i));
    assertFalse(Files.exists(i.output().resolve("final")));assertFalse(Files.exists(i.output().resolve("FINAL-SEAL")));
  }

  @Test
  void changedProtocolOrPacketIsRefusedBeforeAFitOrValidationBoundaryExists() throws Exception {
    var i=inputs();run("audit",i);
    Path protocol=i.fixtures().resolve("ranking-wave3/PREREGISTRATION.md");Files.writeString(protocol,"changed rule");
    assertThrows(IOException.class,()->run("fit",i));assertFalse(Files.exists(i.output().resolve("fit")));
    freeze(protocol.getParent()); // Recomputing a protocol manifest cannot disguise a changed audit binding.
    assertThrows(IOException.class,()->run("fit",i));assertFalse(Files.exists(i.output().resolve("fit")));
  }
}
