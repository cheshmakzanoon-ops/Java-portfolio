package io.github.cheshmakzanoon.portfolio.indexforge;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.lang.model.element.Modifier;
import javax.tools.*;

/** Parse-only JDK-source catalog extraction in the bounded worker; no attribution or execution. */
public final class SourceCatalogParser {
  private SourceCatalogParser() {}

  public static SourceCatalogFacts.Unit parse(String source) throws IOException {
    if (source.length() > 2_097_152 || source.getBytes(StandardCharsets.UTF_8).length > 2_097_152)
      throw new IOException("API source entry quota");
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IOException("JDK compiler module required");
    boolean[] error = {false};
    int[] diagnostics = {0};
    try (var standard = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        var files = new SourceCompilerBoundary.DeniedFiles(standard)) {
      var task =
          (JavacTask)
              compiler.getTask(
                  null,
                  files,
                  d -> {
                    if (++diagnostics[0] > 128)
                      throw new IllegalArgumentException("API diagnostic quota");
                    if (d.getKind() == Diagnostic.Kind.ERROR) error[0] = true;
                  },
                  List.of(
                      "-proc:none",
                      "-classpath",
                      "",
                      "-sourcepath",
                      "",
                      "-processorpath",
                      "",
                      "--system",
                      "none"),
                  null,
                  List.of(new SourceCompilerBoundary.MemorySource(source)));
      var parsed = task.parse().iterator();
      if (!parsed.hasNext()) throw new IOException("missing API unit");
      var unit = parsed.next();
      if (parsed.hasNext() || error[0]) throw new IOException("invalid JDK API source");
      int nodes = 0;
      var all = new ArrayDeque<Tree>();
      all.push(unit);
      while (!all.isEmpty()) {
        if (++nodes > 131_072) throw new IOException("API tree quota");
        for (Tree child : children(all.pop())) all.push(child);
      }
      String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
      List<String> imports =
          unit.getImports().stream()
              .filter(i -> !i.isStatic())
              .map(i -> i.getQualifiedIdentifier().toString())
              .toList();
      DocTrees docs = DocTrees.instance(task);
      var pending = new ArrayDeque<Named>();
      for (Tree tree : unit.getTypeDecls())
        if (tree instanceof ClassTree c && visible(c.getModifiers().getFlags()))
          pending.add(new Named(c, pkg));
      List<SourceCatalogFacts.Type> types = new ArrayList<>();
      while (!pending.isEmpty()) {
        var named = pending.remove();
        var type = named.tree();
        String owner = named.prefix() + "." + type.getSimpleName();
        List<SourceCatalogFacts.Member> members = new ArrayList<>();
        boolean interfaceType =
            type.getKind() == Tree.Kind.INTERFACE || type.getKind() == Tree.Kind.ANNOTATION_TYPE;
        for (Tree member : type.getMembers()) {
          if (member instanceof ClassTree nested
              && (visible(nested.getModifiers().getFlags())
                  || interfaceType && !nested.getModifiers().getFlags().contains(Modifier.PRIVATE)))
            pending.add(new Named(nested, owner));
          if (member instanceof MethodTree m
              && (visible(m.getModifiers().getFlags())
                  || interfaceType && !m.getModifiers().getFlags().contains(Modifier.PRIVATE))) {
            members.add(
                new SourceCatalogFacts.Member(
                    m.getName().toString(),
                    m.getParameters().stream().map(p -> p.getType().toString()).toList(),
                    m.getReturnType() == null ? owner : m.getReturnType().toString(),
                    description(docs.getDocCommentTree(TreePath.getPath(unit, m))),
                    false));
          } else if (member instanceof VariableTree v
              && (visible(v.getModifiers().getFlags()) || interfaceType)) {
            members.add(
                new SourceCatalogFacts.Member(
                    v.getName().toString(),
                    List.of(),
                    v.getType() == null ? owner : v.getType().toString(),
                    description(docs.getDocCommentTree(TreePath.getPath(unit, v))),
                    true));
          }
          if (members.size() > 4096) throw new IOException("API member quota");
        }
        types.add(
            new SourceCatalogFacts.Type(
                owner, description(docs.getDocCommentTree(TreePath.getPath(unit, type))), members));
        if (types.size() > 128) throw new IOException("API type quota");
      }
      return new SourceCatalogFacts.Unit(pkg, imports, types);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("API parser quota", invalid);
    }
  }

  private record Named(ClassTree tree, String prefix) {}

  private static boolean visible(Set<Modifier> flags) {
    return flags.contains(Modifier.PUBLIC) || flags.contains(Modifier.PROTECTED);
  }

  private static String description(com.sun.source.doctree.DocCommentTree doc) {
    if (doc == null) return "";
    var out = new StringBuilder();
    append(out, doc.getFullBody(), true);
    for (var tag : doc.getBlockTags()) {
      if (tag instanceof com.sun.source.doctree.ParamTree p) append(out, p.getDescription(), false);
      if (tag instanceof com.sun.source.doctree.ReturnTree r)
        append(out, r.getDescription(), false);
    }
    return out.toString();
  }

  private static void append(
      StringBuilder out,
      List<? extends com.sun.source.doctree.DocTree> parts,
      boolean firstParagraph) {
    for (var part : parts) {
      if (out.length() > 8192) throw new IllegalArgumentException("API description quota");
      if (part instanceof com.sun.source.doctree.StartElementTree element) {
        String name = element.getName().toString().toLowerCase(Locale.ROOT);
        if (Set.of("pre", "table", "ul", "ol").contains(name)
            || firstParagraph && name.equals("p") && !out.toString().isBlank()) break;
      } else if (part instanceof com.sun.source.doctree.TextTree text)
        out.append(' ').append(text.getBody());
      else if (part instanceof com.sun.source.doctree.LinkTree link)
        append(out, link.getLabel(), false);
      // Literal/code/snippet/unknown-inline nodes and link target identifiers are excluded.
    }
    if (out.length() > 8192) throw new IllegalArgumentException("API description quota");
  }

  private static List<Tree> children(Tree tree) {
    var out = new ArrayList<Tree>();
    tree.accept(new Children(), out);
    return out;
  }

  private static final class Children extends TreeScanner<Void, List<Tree>> {
    @Override
    public Void scan(Tree tree, List<Tree> out) {
      if (tree != null) out.add(tree);
      return null;
    }
  }
}
