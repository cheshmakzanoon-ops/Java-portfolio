package io.github.cheshmakzanoon.portfolio.faultline;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.*;
import io.github.cheshmakzanoon.portfolio.faultline.cfg.*;
import io.github.cheshmakzanoon.portfolio.faultline.dataflow.*;
import io.github.cheshmakzanoon.portfolio.faultline.input.*;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.faultline.rules.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class EngineTest implements Opcodes {
  @TempDir Path tmp;

  static byte[] simple(String name, int version) {
    ClassWriter c = new ClassWriter(0);
    c.visit(version, ACC_PUBLIC, name, null, "java/lang/Object", null);
    MethodVisitor m =
        c.visitMethod(
            ACC_PUBLIC | ACC_STATIC, "id", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
    m.visitCode();
    m.visitVarInsn(ALOAD, 0);
    m.visitInsn(ARETURN);
    m.visitMaxs(1, 1);
    m.visitEnd();
    c.visitEnd();
    return c.toByteArray();
  }

  Path jar(String name, Map<String, byte[]> entries) throws Exception {
    Path p = tmp.resolve(name);
    try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
      for (var e : entries.entrySet()) {
        z.putNextEntry(new ZipEntry(e.getKey()));
        z.write(e.getValue());
        z.closeEntry();
      }
    }
    return p;
  }

  InputManifest read(Path p) throws Exception {
    return new BoundedJarReader(InputLimits.defaults(), 25).read(List.of(p), List.of());
  }

  @Test
  void inputHashesAndTypes() throws Exception {
    Path jar = jar("ok.jar", Map.of("A.class", simple("A", V25)));
    InputManifest m = read(jar);
    assertEquals(1, m.classes().size());
    assertTrue(m.expandedBytes() > 20);
    assertTrue(m.classes().get("A.class").sha256().matches("[a-f0-9]{64}"));
    var c = new ClassFactsReader().read(m);
    assertEquals(1, c.get("A").methods().size());
    assertTrue(new TypeHierarchy(c).subtype("A", "java/lang/Object"));
    assertFalse(new TypeHierarchy(c).subtype("A", "Missing"));
    assertEquals(
        List.of("A.id(Ljava/lang/String;)Ljava/lang/String;"),
        new TypeHierarchy(c).targets("A", "id", "(Ljava/lang/String;)Ljava/lang/String;", false));
    assertThrows(UnsupportedOperationException.class, () -> m.classes().clear());
  }

  @Test
  void multireleaseUsesManifestAndTarget() throws Exception {
    byte[] manifest = "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes();
    byte[] base = simple("A", V17), v25 = simple("A", V25);
    Path p =
        jar(
            "mr.jar",
            Map.of(
                "META-INF/MANIFEST.MF",
                manifest,
                "A.class",
                base,
                "META-INF/versions/25/A.class",
                v25));
    assertEquals(BoundedJarReader.sha(v25), read(p).classes().get("A.class").sha256());
    assertEquals(
        BoundedJarReader.sha(base),
        new BoundedJarReader(InputLimits.defaults(), 17)
            .read(List.of(p), List.of())
            .classes()
            .get("A.class")
            .sha256());
    Path inert = jar("inert.jar", Map.of("A.class", base, "META-INF/versions/25/A.class", v25));
    assertEquals(BoundedJarReader.sha(base), read(inert).classes().get("A.class").sha256());
  }

  @Test
  void archiveAdversariesBounded() throws Exception {
    for (String bad :
        List.of(
            "../escape.class",
            "/tmp/escape.class",
            "a/./b.class",
            "a\\evil.class",
            "C:/evil.class")) {
      Path p = jar("bad" + Math.abs(bad.hashCode()) + ".jar", Map.of(bad, simple("A", V25)));
      assertThrows(InputFailure.class, () -> read(p));
    }
    assertFalse(Files.exists(tmp.getParent().resolve("escape.class")));
    assertThrows(
        InputFailure.class, () -> read(jar("nested.jar", Map.of("inner.jar", new byte[] {1}))));
    assertThrows(
        InputFailure.class, () -> read(jar("wrong.jar", Map.of("B.class", simple("A", V25)))));
    assertThrows(
        InputFailure.class,
        () -> read(jar("malformed.jar", Map.of("A.class", new byte[] {1, 2, 3}))));
    assertThrows(
        InputFailure.class, () -> read(jar("future.jar", Map.of("A.class", simple("A", V26)))));
    Path bomb = jar("bomb.jar", Map.of("large.txt", new byte[2_000_000]));
    assertThrows(InputFailure.class, () -> read(bomb));
    Path entry = jar("entries.jar", Map.of("a.txt", new byte[1], "b.txt", new byte[1]));
    assertThrows(
        InputFailure.class,
        () ->
            new BoundedJarReader(new InputLimits(1, 100, 10, 100, 1000), 25)
                .read(List.of(entry), List.of()));
    assertThrows(
        InputFailure.class,
        () ->
            new BoundedJarReader(new InputLimits(20, 1, 10, 100, 1000), 25)
                .read(List.of(entry), List.of()));
    Path dup = jar("dup.jar", Map.of("A.class", simple("A", V25), "B.class", simple("B", V25)));
    byte[] bytes = Files.readAllBytes(dup);
    for (int i = 0; i < bytes.length - 7; i++)
      if (bytes[i] == 'B' && new String(bytes, i, 7).equals("B.class")) bytes[i] = 'A';
    Files.write(dup, bytes);
    assertThrows(InputFailure.class, () -> read(dup));
    Path crc = jar("crc.jar", Map.of("A.class", simple("A", V25)));
    byte[] corrupt = Files.readAllBytes(crc);
    for (int i = 40; i < Math.min(corrupt.length - 80, 60); i++) corrupt[i] ^= 0x44;
    Files.write(crc, corrupt);
    assertThrows(IOException.class, () -> read(crc));
  }

  @Test
  void directoriesDuplicatesAndMissing() throws Exception {
    Path directory = Files.createDirectory(tmp.resolve("classes"));
    Files.write(directory.resolve("A.class"), simple("A", V25));
    assertEquals(1, read(directory).classes().size());
    assertThrows(
        InputFailure.class,
        () ->
            new BoundedJarReader(InputLimits.defaults(), 25)
                .read(List.of(directory, directory), List.of()));
    assertThrows(InputFailure.class, () -> read(tmp.resolve("missing.jar")));
    Path link = tmp.resolve("link");
    Files.createSymbolicLink(link, directory);
    assertThrows(InputFailure.class, () -> read(link));
    assertThrows(
        IllegalArgumentException.class, () -> new BoundedJarReader(InputLimits.defaults(), 26));
    assertThrows(IllegalArgumentException.class, () -> new InputLimits(0, 1, 1, 1, 1));
  }

  @Test
  void frameCategoriesAndJoins() {
    FrameState a = new FrameState(4);
    a.store(0, AbstractValue.of(AbstractValue.Kind.LONG, Taint.source("s")));
    assertEquals(AbstractValue.TOP, a.locals[1]);
    a.store(1, AbstractValue.of(AbstractValue.Kind.INT, Taint.BOTTOM));
    assertEquals(AbstractValue.TOP, a.locals[0]);
    a.push(AbstractValue.of(AbstractValue.Kind.DOUBLE, Taint.BOTTOM));
    assertEquals(2, a.slots());
    FrameState b = a.copy();
    assertFalse(a.merge(b));
    b.stack.set(0, AbstractValue.of(AbstractValue.Kind.DOUBLE, Taint.source("s")));
    assertTrue(a.merge(b));
    assertEquals(Taint.source("s"), a.stackTaint());
    assertThrows(IllegalArgumentException.class, () -> a.merge(new FrameState(4)));
    assertThrows(IllegalArgumentException.class, () -> a.store(4, AbstractValue.constant(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AbstractValue.of(AbstractValue.Kind.INT, Taint.BOTTOM)
                .join(AbstractValue.of(AbstractValue.Kind.REF, Taint.BOTTOM)));
    assertThrows(IllegalArgumentException.class, () -> new FrameState(0).pop());
  }

  @Test
  void cfgBranchesHandlersAndComponents() {
    MethodNode n = new MethodNode(ACC_STATIC, "branch", "(I)I", null, null);
    LabelNode start = new LabelNode(),
        no = new LabelNode(),
        end = new LabelNode(),
        handler = new LabelNode();
    n.instructions.add(start);
    n.instructions.add(new VarInsnNode(ILOAD, 0));
    n.instructions.add(new JumpInsnNode(IFEQ, no));
    n.instructions.add(new InsnNode(ICONST_1));
    n.instructions.add(new InsnNode(IRETURN));
    n.instructions.add(no);
    n.instructions.add(new InsnNode(ICONST_4));
    n.instructions.add(new InsnNode(ICONST_0));
    n.instructions.add(new InsnNode(IDIV));
    n.instructions.add(end);
    n.instructions.add(new InsnNode(IRETURN));
    n.instructions.add(handler);
    n.instructions.add(new InsnNode(POP));
    n.instructions.add(new InsnNode(ICONST_M1));
    n.instructions.add(new InsnNode(IRETURN));
    n.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Exception"));
    n.maxStack = 2;
    n.maxLocals = 1;
    MethodIr ir = new MethodIr("A", null, n);
    var graph = new ControlFlowBuilder().build(ir);
    assertEquals(List.of(2, 4), graph.normal().get(1));
    assertEquals(List.of(new ExceptionEdge(6, 8, "java/lang/Exception")), graph.exceptionEdges());
    assertTrue(graph.dot().contains("style=dashed"));
    assertEquals(
        List.of(
            new BasicBlock(0, 1, List.of(2, 4)),
            new BasicBlock(2, 3, List.of()),
            new BasicBlock(4, 7, List.of()),
            new BasicBlock(8, 10, List.of())),
        graph.blocks());
    var tf =
        new TransferFunction(
            ir,
            new HeapSummary(),
            new RuleModels(),
            new CallResolver(new TypeHierarchy(Map.of())),
            Map.of(),
            AnalysisBudget.defaults(),
            new TreeSet<>());
    var result = new WorklistSolver().solve(ir, graph, tf, AnalysisBudget.defaults(), false);
    assertEquals(11, result.reachable());
    assertEquals(
        3,
        new StronglyConnectedComponents()
            .find(Map.of("a", List.of("b"), "b", List.of("a"), "c", List.of("d"), "d", List.of()))
            .size());
  }

  @Test
  void symbolicSummaryAndSchedule() throws Exception {
    Path p = jar("identity.jar", Map.of("A.class", simple("A", V25)));
    var classes = new ClassFactsReader().read(read(p));
    var a = new SummaryWorklist().analyze(classes, AnalysisBudget.defaults(), false);
    var b = new SummaryWorklist().analyze(classes, AnalysisBudget.defaults(), true);
    assertFalse(a.partial());
    assertEquals(a.summaries(), b.summaries());
    assertTrue(
        a.summaries()
            .values()
            .iterator()
            .next()
            .returns()
            .equals(Taint.parameter(a.summaries().firstKey(), 0)));
    var limited = new SummaryWorklist().analyze(classes, new AnalysisBudget(1, 1000, 32, 5), false);
    assertTrue(limited.partial());
    assertFalse(limited.unfinished().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> new AnalysisBudget(0, 1, 1, 1));
  }

  @Test
  void deepHierarchyAndCallGraphAreIterativeAndBudgeted() {
    TreeMap<String, ClassFactsReader.ClassFacts> classes = new TreeMap<>();
    TreeMap<String, Collection<String>> graph = new TreeMap<>();
    for (int i = 0; i < 10_000; i++) {
      String name = "Deep" + i;
      classes.put(
          name,
          new ClassFactsReader.ClassFacts(
              name,
              i == 0 ? "java/lang/Object" : "Deep" + (i - 1),
              List.of(),
              ACC_PUBLIC,
              true,
              new TreeMap<>()));
      graph.put(name, i == 9999 ? List.of() : List.of("Deep" + (i + 1)));
    }
    AnalysisBudget budget = new AnalysisBudget(200_000, 120_000, 32, 5);
    TypeHierarchy hierarchy = new TypeHierarchy(classes, budget);
    assertTrue(hierarchy.subtype("Deep9999", "Deep0"));
    assertFalse(hierarchy.subtype("Deep0", "Deep9999"));
    assertTrue(hierarchy.targets("Deep9999", "missing", "()V", false).isEmpty());
    assertEquals(10_000, new StronglyConnectedComponents().find(graph, budget::spend).size());
    assertThrows(
        AnalysisBudget.Exhausted.class,
        () -> new TypeHierarchy(classes, new AnalysisBudget(1, 120_000, 32, 5)));
  }

  @Test
  void declarativeModelsRejectBadDescriptorsMasksAndArguments() {
    assertThrows(
        IllegalArgumentException.class, () -> new RuleModels(Set.of("*"), Map.of(), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuleModels(
                Set.of(),
                Map.of("A.s(Ljava/lang/String;)V", new RuleModels.Sink("BG-SQL-TEXT", 4, 0)),
                Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuleModels(
                Set.of(), Map.of("A.s()V", new RuleModels.Sink("BG-SQL-TEXT", 1, 0)), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RuleModels(
                Set.of(), Map.of(), Map.of("A.s(Ljava/lang/String;)Ljava/lang/String;", 7)));
    var custom =
        new RuleModels(
            Set.of("A.source()Ljava/lang/String;"),
            Map.of("A.sql(Ljava/lang/String;)V", new RuleModels.Sink("BG-SQL-TEXT", 1, 0)),
            Map.of("A.clean(Ljava/lang/String;)Ljava/lang/String;", 1));
    assertTrue(custom.source("A.source()Ljava/lang/String;"));
    assertFalse(custom.source("java/lang/System.getenv(Ljava/lang/String;)Ljava/lang/String;"));
    assertEquals(1, custom.sanitizer("A.clean(Ljava/lang/String;)Ljava/lang/String;"));
    assertNotNull(custom.sink("A.sql(Ljava/lang/String;)V"));
    assertTrue(Taint.unknownValue().join(Taint.source("s")).sanitize(1).unknown());
  }

  @Test
  void bothSwitchFormsHaveExactTargetsAndValidFrames() {
    for (boolean table : new boolean[] {true, false}) {
      MethodNode n = new MethodNode(ACC_STATIC, "select", "(I)I", null, null);
      LabelNode zero = new LabelNode(), other = new LabelNode();
      n.instructions.add(new VarInsnNode(ILOAD, 0));
      if (table) n.instructions.add(new TableSwitchInsnNode(0, 0, other, zero));
      else
        n.instructions.add(new LookupSwitchInsnNode(other, new int[] {42}, new LabelNode[] {zero}));
      n.instructions.add(zero);
      n.instructions.add(new InsnNode(ICONST_0));
      n.instructions.add(new InsnNode(IRETURN));
      n.instructions.add(other);
      n.instructions.add(new InsnNode(ICONST_1));
      n.instructions.add(new InsnNode(IRETURN));
      n.maxStack = 1;
      n.maxLocals = 1;
      MethodIr ir = new MethodIr("Switch", null, n);
      var g = new ControlFlowBuilder().build(ir);
      assertEquals(List.of(2, 4), g.normal().get(1));
      var transfer =
          new TransferFunction(
              ir,
              new HeapSummary(),
              new RuleModels(),
              new CallResolver(new TypeHierarchy(Map.of())),
              Map.of(),
              AnalysisBudget.defaults(),
              new TreeSet<>());
      assertEquals(
          6,
          new WorklistSolver()
              .solve(ir, g, transfer, AnalysisBudget.defaults(), false)
              .reachable());
    }
    MethodNode bad = new MethodNode(ACC_STATIC, "bad", "()V", null, null);
    LabelNode absent = new LabelNode();
    bad.instructions.add(new JumpInsnNode(GOTO, absent));
    bad.instructions.add(absent);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ControlFlowBuilder().build(new MethodIr("Bad", null, bad)));
  }

  @Test
  void loopBackedgeGrowsLocalAndScheduleDoesNotMatter() {
    MethodNode n =
        new MethodNode(ACC_STATIC, "loop", "(Ljava/lang/String;I)Ljava/lang/String;", null, null);
    LabelNode loop = new LabelNode(), done = new LabelNode();
    n.instructions.add(new LdcInsnNode("clean"));
    n.instructions.add(new VarInsnNode(ASTORE, 2));
    n.instructions.add(loop);
    n.instructions.add(new VarInsnNode(ILOAD, 1));
    n.instructions.add(new JumpInsnNode(IFEQ, done));
    n.instructions.add(new VarInsnNode(ALOAD, 0));
    n.instructions.add(new VarInsnNode(ASTORE, 2));
    n.instructions.add(new IincInsnNode(1, -1));
    n.instructions.add(new JumpInsnNode(GOTO, loop));
    n.instructions.add(done);
    n.instructions.add(new VarInsnNode(ALOAD, 2));
    n.instructions.add(new InsnNode(ARETURN));
    n.maxStack = 1;
    n.maxLocals = 3;
    MethodIr ir = new MethodIr("Loop", null, n);
    var graph = new ControlFlowBuilder().build(ir);
    List<WorklistSolver.Result> results = new ArrayList<>();
    for (boolean reverse : new boolean[] {true, false}) {
      var transfer =
          new TransferFunction(
              ir,
              new HeapSummary(),
              new RuleModels(),
              new CallResolver(new TypeHierarchy(Map.of())),
              Map.of(),
              AnalysisBudget.defaults(),
              new TreeSet<>());
      results.add(
          new WorklistSolver().solve(ir, graph, transfer, AnalysisBudget.defaults(), reverse));
    }
    assertEquals(Taint.parameter(ir.id, 0), results.getFirst().summary().returns());
    assertEquals(results.getFirst().summary(), results.getLast().summary());
    assertTrue(
        results
            .getFirst()
            .frames()
            .get(8)
            .locals[2]
            .taint()
            .labels()
            .containsKey("P:" + ir.scope + "#0"));
  }

  @Test
  void scopedSymbolsAndBoundedHeapAliasesPreserveMeaning() {
    Taint caller = Taint.parameter("Caller.run()V", 0),
        callee = Taint.parameter("Callee.set()V", 0);
    assertEquals(caller, callee.substitute(List.of(caller), "Callee.set()V"));
    assertEquals(caller, caller.substitute(List.of(Taint.source("other")), "Callee.set()V"));
    assertEquals(
        Taint.BOTTOM,
        callee
            .sanitize(RuleModels.ALL_CONTEXTS)
            .substitute(List.of(Taint.source("source")), "Callee.set()V"));
    HeapSummary heap = new HeapSummary();
    AbstractValue original = AbstractValue.reference(Taint.BOTTOM, "allocation");
    heap.writeField("Owner.field:[Ljava/lang/String;", original);
    AbstractValue loaded =
        heap.readField("Owner.field:[Ljava/lang/String;", AbstractValue.Kind.REF);
    heap.addElement(loaded, Taint.source("source"));
    assertEquals(Taint.source("source"), heap.elements(original));
    assertTrue(heap.consumeChanged());
    assertFalse(heap.consumeChanged());
    HeapSummary bounded = new HeapSummary(new AnalysisBudget(2, 120000, 32, 5));
    assertThrows(AnalysisBudget.Exhausted.class, () -> bounded.alias("a", List.of("b", "c")));
  }

  @Test
  void hostileFrameMetadataAndOversizedStateProduceExplicitResourceLimit() throws Exception {
    ClassWriter c = new ClassWriter(0);
    c.visit(V25, ACC_PUBLIC, "HugeLocals", null, "java/lang/Object", null);
    MethodVisitor method = c.visitMethod(ACC_STATIC, "run", "()V", null, null);
    method.visitCode();
    for (int j = 0; j < 1000; j++) method.visitInsn(NOP);
    method.visitInsn(RETURN);
    method.visitMaxs(0, 65535);
    method.visitEnd();
    c.visitEnd();
    Path file = tmp.resolve("HugeLocals.class");
    Files.write(file, c.toByteArray());
    var result =
        new Analyzer()
            .scan(List.of(file), List.of(), 25, InputLimits.defaults(), AnalysisBudget.defaults());
    assertTrue(result.analysis().partial());
    assertTrue(result.analysis().diagnostics().stream().anyMatch(d -> d.contains("frame-cell")));
    assertFalse(result.analysis().unfinished().isEmpty());
    TreeMap<String, Integer> labels = new TreeMap<>();
    TreeSet<String> refs = new TreeSet<>();
    for (int j = 0; j < 4097; j++) {
      labels.put("s" + j, 7);
      refs.add("r" + j);
    }
    assertThrows(AnalysisBudget.Exhausted.class, () -> new Taint(labels, false));
    assertThrows(
        AnalysisBudget.Exhausted.class,
        () -> new AbstractValue(AbstractValue.Kind.REF, Taint.BOTTOM, refs, null));
  }

  @Test
  void analysisDeadlineIsDistinctAndDeterministic() {
    java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
    AnalysisBudget budget = new AnalysisBudget(1000, 1, 32, 5, nanos::get);
    budget.spend();
    nanos.set(2_000_000);
    var failure = assertThrows(AnalysisBudget.Exhausted.class, budget::spend);
    assertEquals("analysis deadline exceeded", failure.getMessage());
    assertTrue(budget.work() < budget.maxWork);
  }

  @Test
  void sanitizerSubstitutionAndOrderHaveActualContextMeaning() {
    Taint full = Taint.source("source");
    assertEquals(Map.of("source", 126), full.sanitize(1).labels());
    assertEquals(Map.of("source", 125), full.sanitize(2).labels());
    assertEquals(Map.of("source", 123), full.sanitize(4).labels());
    assertEquals(Map.of("source", 119), full.sanitize(8).labels());
    assertFalse(full.sanitize(4).hasConcrete(4));
    assertFalse(full.hasConcrete(0));
    assertFalse(Taint.source("a").lessOrEqual(Taint.source("b")));
    assertFalse(Taint.unknownValue().lessOrEqual(Taint.BOTTOM));
    assertEquals(
        Map.of("kept", 127),
        new Taint(new TreeMap<>(Map.of("zero", 0, "high", 128, "kept", 255)), false).labels());
    Taint symbolic = new Taint(new TreeMap<>(Map.of("P:0", 4)), false);
    Taint actual = new Taint(new TreeMap<>(Map.of("source", 3)), false);
    assertEquals(Taint.BOTTOM, symbolic.substitute(List.of(actual)));
    assertTrue(Taint.source("P:0").substitute(List.of()).unknown());
    assertTrue(Taint.source("P:1").substitute(List.of(actual)).unknown());
  }

  @Test
  void instanceWideParametersAndEmptyMethodsHaveCorrectFrames() {
    MethodNode n =
        new MethodNode(
            ACC_PUBLIC, "identity", "(JLjava/lang/String;D)Ljava/lang/String;", null, null);
    n.instructions.add(new VarInsnNode(ALOAD, 3));
    n.instructions.add(new InsnNode(ARETURN));
    n.maxLocals = 6;
    n.maxStack = 1;
    MethodIr method = new MethodIr("Instance", null, n);
    var graph = new ControlFlowBuilder().build(method);
    var transfer =
        new TransferFunction(
            method,
            new HeapSummary(),
            new RuleModels(),
            new CallResolver(new TypeHierarchy(Map.of())),
            Map.of(),
            AnalysisBudget.defaults(),
            new TreeSet<>());
    AnalysisBudget budget = AnalysisBudget.defaults();
    var result = new WorklistSolver().solve(method, graph, transfer, budget, false);
    assertEquals(Taint.parameter(method.id, 2), result.summary().returns());
    FrameState entry = result.frames().getFirst();
    assertEquals(Taint.parameter(method.id, 0), entry.locals[0].taint());
    assertEquals(AbstractValue.Kind.LONG, entry.locals[1].kind());
    assertEquals(AbstractValue.TOP, entry.locals[2]);
    assertEquals(AbstractValue.Kind.REF, entry.locals[3].kind());
    assertEquals(AbstractValue.Kind.DOUBLE, entry.locals[4].kind());
    assertEquals(AbstractValue.TOP, entry.locals[5]);
    assertEquals(7, budget.work());
    MethodNode empty = new MethodNode(ACC_ABSTRACT, "abstractMethod", "()V", null, null);
    MethodIr ir = new MethodIr("Instance", null, empty);
    var emptyResult =
        new WorklistSolver()
            .solve(
                ir, new ControlFlowBuilder().build(ir), transfer, AnalysisBudget.defaults(), false);
    assertNotNull(emptyResult);
    assertTrue(emptyResult.frames().isEmpty());
    assertEquals(MethodSummary.empty(), emptyResult.summary());
    assertEquals(0, emptyResult.reachable());
  }

  @Test
  void handlerRangeAndThrowingFamiliesAreExactAndBudgeted() {
    for (int op :
        new int[] {
          LDC,
          ATHROW,
          IDIV,
          LDIV,
          IREM,
          LREM,
          IALOAD,
          SALOAD,
          IASTORE,
          SASTORE,
          GETSTATIC,
          MONITOREXIT,
          MULTIANEWARRAY
        }) {
      MethodNode n = new MethodNode(ACC_STATIC, "shapes", "()V", null, null);
      LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
      n.instructions.add(start);
      n.instructions.add(new InsnNode(op));
      n.instructions.add(end);
      n.instructions.add(new InsnNode(op));
      n.instructions.add(new InsnNode(RETURN));
      n.instructions.add(handler);
      n.instructions.add(new InsnNode(POP));
      n.instructions.add(new InsnNode(RETURN));
      n.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
      java.util.concurrent.atomic.AtomicInteger ticks =
          new java.util.concurrent.atomic.AtomicInteger();
      var graph =
          new ControlFlowBuilder().build(new MethodIr("Throwing", null, n), ticks::incrementAndGet);
      assertEquals(List.of(new ExceptionEdge(0, 3, null)), graph.exceptionEdges(), "opcode " + op);
      assertEquals(11, ticks.get());
    }
  }

  @Test
  void classpathProvenanceAndNoncanonicalMrVersionsAreExplicit() throws Exception {
    Path app = jar("app.jar", Map.of("A.class", simple("A", V25))),
        library = jar("lib.jar", Map.of("B.class", simple("B", V25)));
    var manifest =
        new BoundedJarReader(InputLimits.defaults(), 25).read(List.of(app), List.of(library));
    assertTrue(manifest.classes().get("A.class").application());
    assertFalse(manifest.classes().get("B.class").application());
    Path bad =
        jar(
            "mr-noncanonical.jar",
            Map.of(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes(),
                "META-INF/versions/09/A.class",
                simple("A", V9)));
    assertThrows(InputFailure.class, () -> read(bad));
    assertThrows(
        UnsupportedOperationException.class, () -> new ClasspathCatalog().entries().clear());
  }

  @Test
  void structuralParsingWorkHasItsOwnPartialFrontier() throws Exception {
    Path file = tmp.resolve("A.class");
    Files.write(file, simple("A", V25));
    var limited =
        new Analyzer()
            .scan(
                List.of(file),
                List.of(),
                25,
                InputLimits.defaults(),
                new AnalysisBudget(1, 120_000, 32, 5));
    assertTrue(limited.analysis().partial());
    assertTrue(limited.analysis().unfinished().getFirst().startsWith("parser:"));
    assertTrue(limited.classes().isEmpty());
    ClassWriter writer = new ClassWriter(0);
    writer.visit(V25, ACC_PUBLIC, "ManyFacts", null, "java/lang/Object", null);
    for (int m = 0; m < 34; m++) {
      MethodVisitor method = writer.visitMethod(ACC_STATIC, "m" + m, "()V", null, null);
      method.visitCode();
      for (int j = 0; j < 60_000; j++) method.visitInsn(NOP);
      method.visitInsn(RETURN);
      method.visitMaxs(0, 0);
      method.visitEnd();
    }
    writer.visitEnd();
    Path many = tmp.resolve("ManyFacts.class");
    Files.write(many, writer.toByteArray());
    var bounded =
        new Analyzer()
            .scan(List.of(many), List.of(), 25, InputLimits.defaults(), AnalysisBudget.defaults());
    assertTrue(bounded.analysis().partial());
    assertTrue(
        bounded.analysis().diagnostics().stream().anyMatch(d -> d.contains("class-fact budget")));
    assertTrue(bounded.classes().isEmpty());
  }
}
