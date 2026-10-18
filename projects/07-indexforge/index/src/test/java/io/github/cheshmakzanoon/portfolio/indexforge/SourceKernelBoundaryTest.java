package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceKernelBoundaryTest {
  @TempDir Path root;

  @Test
  void lexerHonorsCommentsCharacterEscapesTextBlocksAndExactTokenBoundaries() {
    for (String observed :
        List.of(
            "",
            " ".repeat(65536),
            "// ignored {[\r\n {}",
            "/* ignored {[ */ ()[]{}",
            "\"escaped\\\"{[(\"",
            "'\\''",
            "\"\"\"\nignored {[ \\\" quoted\n\"\"\"",
            "a".repeat(256),
            ";".repeat(8192)))
      assertEquals(SourceFacts.Status.OBSERVED, SourcePreflight.check(observed));
    for (String syntax : List.of("/*", "\"", "'", "\"line\n", "'line\r", "}", "([)]", "{"))
      assertEquals(SourceFacts.Status.SYNTAX, SourcePreflight.check(syntax));
    assertEquals(SourceFacts.Status.SOURCE_QUOTA, SourcePreflight.check(null));
    assertEquals(SourceFacts.Status.SOURCE_QUOTA, SourcePreflight.check("é".repeat(32769)));
    assertEquals(SourceFacts.Status.SOURCE_QUOTA, SourcePreflight.check(";".repeat(8193)));
    assertEquals(SourceFacts.Status.SOURCE_QUOTA, SourcePreflight.check("a".repeat(257)));
    assertEquals(
        SourceFacts.Status.OBSERVED, SourcePreflight.check("(".repeat(32) + ")".repeat(32)));
    assertEquals(
        SourceFacts.Status.SOURCE_QUOTA, SourcePreflight.check("(".repeat(33) + ")".repeat(33)));
    assertEquals(SourceFacts.Status.UNSUPPORTED, SourcePreflight.check("\\u0061"));
  }

  @Test
  void declarationAndLocalOperationQuotasEraseTheWholeInventoryInsteadOfKeepingAPrefix()
      throws Exception {
    var formatted = new StringBuilder("class Many {");
    for (int i = 0; i < 129; i++) formatted.append("int f").append(i).append("(){return 0;}");
    formatted.append('}');
    var many = SourceJavaParser.parse(formatted.toString());
    assertEquals(SourceFacts.Status.TREE_QUOTA, many.status());
    assertTrue(many.members().isEmpty());
    assertTrue(many.types().isEmpty());
    var locals = new StringBuilder("class Many {void run(){");
    for (int i = 0; i < 65; i++) locals.append("int v").append(i).append("=0;");
    locals.append("}}");
    assertEquals(SourceFacts.Status.TREE_QUOTA, SourceJavaParser.parse(locals.toString()).status());
    var fields = new StringBuilder("class Many {");
    for (int i = 0; i < 65; i++) fields.append("int v").append(i).append(';');
    fields.append('}');
    assertEquals(SourceFacts.Status.TREE_QUOTA, SourceJavaParser.parse(fields.toString()).status());
    var classes = new StringBuilder();
    for (int i = 0; i < 129; i++) classes.append("class C").append(i).append("{}");
    assertEquals(
        SourceFacts.Status.TREE_QUOTA, SourceJavaParser.parse(classes.toString()).status());
    var operations =
        SourceJavaParser.parse("class Many {void run(){" + "work();".repeat(129) + "}}");
    assertEquals(SourceFacts.Status.TREE_QUOTA, operations.status());
  }

  @Test
  void literalPayloadsRemainTypesAndReassignmentShadowingAndUnsupportedControlStayUnknown()
      throws Exception {
    var unit =
        SourceJavaParser.parse(
            """
      final class S {
        int field;
        void literals(){ use(null,"payload",'a',true,0,1,0L,1L,1.0f,1.0d); }
        void writes(int n){ int local=0;local=3;local++;n--; }
        void sums(int n){int local=0;local+=n;long other=0L;other-=n;}
        void state(int n){this.field=n;}
        void array(int[] a){a[0]=1;}
        void controls(int n){synchronized(this){if(n<0)return;}try{use(n);}finally{use(n);} }
        <T> void generic(T value){use(value);}
        void varargs(int... values){use(values);}
        void missingBody();
      }
      """);
    assertTrue(unit.declarationInventoryKnown());
    var byName = new HashMap<String, SourceFacts.Member>();
    for (var member : unit.members()) byName.put(member.name(), member);
    var arguments =
        byName.get("literals").calls().getFirst().expression().children().subList(1, 11);
    assertEquals(
        List.of(
            "null",
            "String",
            "char",
            "boolean",
            "zero:int",
            "int",
            "zero:long",
            "long",
            "float",
            "double"),
        arguments.stream().map(SourceFacts.Expression::text).toList());
    assertTrue(byName.get("writes").parameters().getFirst().ambiguous());
    assertTrue(byName.get("writes").locals().getFirst().ambiguous());
    assertEquals("sum", byName.get("sums").locals().getFirst().initializer().kind());
    assertTrue(byName.get("sums").locals().get(1).ambiguous());
    assertTrue(byName.get("state").controlUnknown());
    assertTrue(byName.get("array").controlUnknown());
    assertTrue(byName.get("controls").controlUnknown());
    assertTrue(byName.get("controls").guards().isEmpty());
    assertTrue(byName.get("generic").bindingUnknown());
    assertTrue(byName.get("varargs").bindingUnknown());
    assertTrue(byName.get("missingBody").calls().isEmpty());
    assertEquals(unit, SourceWire.decode(SourceWire.encode(unit)));
  }

  @Test
  void malformedChecksummedWireCannotBypassSchemaLengthOrCompressionValidation() throws Exception {
    var unit = SourceJavaParser.parse("final class Tiny {int value(){return 0;}}");
    byte[] valid = SourceWire.encode(unit);
    int decoded = SourceWire.decodedBytes(valid);
    assertTrue(decoded > 0);
    for (int size : List.of(0, 1, 31, 35))
      assertThrows(IOException.class, () -> SourceWire.decode(new byte[size]));
    byte[] tooLarge = Arrays.copyOf(valid, valid.length);
    ByteBuffer.wrap(tooLarge).putInt(SourceFacts.RAW_SUMMARY_BYTES + 1);
    resign(tooLarge);
    assertThrows(IOException.class, () -> SourceWire.decode(tooLarge));
    byte[] wrongLength = valid.clone();
    ByteBuffer.wrap(wrongLength).putInt(decoded + 1);
    resign(wrongLength);
    assertThrows(IOException.class, () -> SourceWire.decode(wrongLength));
    byte[] compressedTail = new byte[valid.length + 1];
    System.arraycopy(valid, 0, compressedTail, 0, valid.length - 32);
    compressedTail[valid.length - 32] = 42;
    resign(compressedTail);
    assertThrows(IOException.class, () -> SourceWire.decode(compressedTail));
    byte[] corruptCompressed = valid.clone();
    corruptCompressed[4] = (byte) 255;
    resign(corruptCompressed);
    assertThrows(IOException.class, () -> SourceWire.decode(corruptCompressed));
    var raw = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(raw)) {
      out.writeUTF("wrong-schema");
    }
    assertThrows(IOException.class, () -> SourceWire.decode(frame(raw.toByteArray())));
    raw.reset();
    try (var out = new DataOutputStream(raw)) {
      out.writeUTF(SourceFacts.SCHEMA);
      out.writeByte(255);
    }
    assertThrows(IOException.class, () -> SourceWire.decode(frame(raw.toByteArray())));
  }

  private static byte[] frame(byte[] raw) throws IOException {
    var output = new ByteArrayOutputStream();
    output.write(ByteBuffer.allocate(4).putInt(raw.length).array());
    var deflater = new Deflater();
    try {
      deflater.setInput(raw);
      deflater.finish();
      byte[] buffer = new byte[4096];
      while (!deflater.finished()) {
        int n = deflater.deflate(buffer);
        output.write(buffer, 0, n);
      }
    } finally {
      deflater.end();
    }
    output.write(new byte[32]);
    byte[] result = output.toByteArray();
    resign(result);
    return result;
  }

  private static void resign(byte[] bytes) {
    var digest = HexFormat.of().parseHex(Digests.sha256(Arrays.copyOf(bytes, bytes.length - 32)));
    System.arraycopy(digest, 0, bytes, bytes.length - 32, 32);
  }

  @Test
  void repositoryReadEnforcesDecodedAndRowsBudgetsBeforeMaterializingAndRejectsSymlinks()
      throws Exception {
    String body = "final class T {void read(){helper();}void helper(){}}";
    var facts = SourceJavaParser.parse(body);
    String key = "tenant/repository/T.java";
    var snapshot =
        new SourceRepositoryFacts.Snapshot(
            "tenant", "repository", true, List.of(new SourceSymbolGraph.Document(key, facts)));
    SourceRepositoryFacts.write(root, snapshot);
    int bytes = SourceWire.decodedBytes(SourceWire.encode(facts));
    int rows = SourceRepositoryFacts.rows(facts);
    assertEquals(snapshot, SourceRepositoryFacts.read(root, "tenant", "repository", bytes, rows));
    assertThrows(
        SourceRepositoryFacts.QueryQuota.class,
        () -> SourceRepositoryFacts.read(root, "tenant", "repository", bytes - 1, rows));
    assertThrows(
        SourceRepositoryFacts.QueryQuota.class,
        () -> SourceRepositoryFacts.read(root, "tenant", "repository", bytes, rows - 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> SourceRepositoryFacts.read(root, "tenant", "repository", -1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SourceRepositoryFacts.read(
                root, "tenant", "repository", SourceRepositoryFacts.DECODED_LIMIT + 1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> SourceRepositoryFacts.read(root, "tenant", "repository", bytes, -1));
    Path original = root.resolve(SourceRepositoryFacts.fileName("tenant", "repository"));
    Path backup = root.resolve("content.bin");
    Files.move(original, backup);
    Files.createSymbolicLink(original, backup);
    assertThrows(IOException.class, () -> SourceRepositoryFacts.read(root, "tenant", "repository"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SourceRepositoryFacts.fileName("tenant/escape", "repository"));
  }

  @Test
  void expressionShapeAndMetadataQuotasRejectAmbiguousOrPartialRecords() {
    var name = SourceFacts.Expression.name("value");
    for (String unsupported : List.of("forged", "", "NAME"))
      assertThrows(
          IllegalArgumentException.class,
          () -> new SourceFacts.Expression(unsupported, "value", List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new SourceFacts.Expression("name", "", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SourceFacts.Expression("name", "value", List.of(name)));
    assertThrows(
        IllegalArgumentException.class, () -> new SourceFacts.Expression("not", "", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SourceFacts.Expression("compare", "eq", List.of(name)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SourceFacts.Binding("name", "int", name, false, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> new SourceFacts.Invocation(name, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new SourceFacts.Returned(name, -1, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new SourceFacts.Guard(name, 0, 0, "continue"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceFacts.Unit(
                SourceFacts.Status.SYNTAX,
                "",
                List.of(),
                List.of(),
                List.of(
                    new SourceFacts.Member(
                        "A", "f", "void", List.of(), List.of(), Set.of(), List.of(), List.of(),
                        List.of(), 0, false, false, false, false, true))));
  }

  @Test
  void fileManagerDeniesEveryExternalReadWriteLoaderAndModuleLookupAndSourceNeverExecutes()
      throws Exception {
    var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler);
    try (var standard = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        var denied = new SourceJavaParser.DeniedFiles(standard)) {
      var location = javax.tools.StandardLocation.CLASS_PATH;
      assertFalse(denied.hasLocation(location));
      assertNull(denied.getClassLoader(location));
      assertFalse(
          denied
              .list(location, "any", Set.of(javax.tools.JavaFileObject.Kind.CLASS), true)
              .iterator()
              .hasNext());
      assertThrows(
          IOException.class,
          () ->
              denied.getJavaFileForInput(
                  location, "Forbidden", javax.tools.JavaFileObject.Kind.CLASS));
      assertThrows(
          IOException.class,
          () ->
              denied.getJavaFileForOutput(
                  location, "Forbidden", javax.tools.JavaFileObject.Kind.CLASS, null));
      assertThrows(IOException.class, () -> denied.getFileForInput(location, "any", "Forbidden"));
      assertThrows(
          IOException.class, () -> denied.getFileForOutput(location, "any", "Forbidden", null));
      assertNull(denied.getLocationForModule(location, "any.module"));
      assertNull(
          denied.getLocationForModule(
              location, new SourceJavaParser.MemorySource("class NotExecuted{}")));
      assertFalse(denied.listLocationsForModules(location).iterator().hasNext());
    }
    String property = "indexforge.parse.decoy." + UUID.randomUUID();
    assertNull(System.getProperty(property));
    var source =
        SourceJavaParser.parse(
            "@UnknownProcessor final class NotExecuted {static {System.setProperty(\""
                + property
                + "\",\"executed\");}}");
    assertTrue(source.declarationInventoryKnown());
    assertNull(System.getProperty(property));
    Set<String> identifiers = new HashSet<>();
    for (int i = 0; i < 128; i++) identifiers.add("distinctCamelComponent" + i);
    assertThrows(SourceAnchors.Quota.class, () -> SourceAnchors.terms(identifiers));
    var member =
        new SourceFacts.Member(
            "Owner",
            "method",
            "void",
            List.of(),
            List.of(),
            identifiers,
            List.of(),
            List.of(),
            List.of(),
            0,
            false,
            false,
            false,
            false,
            true);
    var unit =
        new SourceFacts.Unit(
            SourceFacts.Status.OBSERVED, "", List.of(), List.of(), List.of(member));
    assertEquals(SourceFacts.Status.TREE_QUOTA, SourceAnchors.checked(unit).status());
    assertEquals(
        SourceFacts.Status.SYNTAX,
        SourceAnchors.checked(SourceFacts.Unit.unknown(SourceFacts.Status.SYNTAX)).status());
  }
}
