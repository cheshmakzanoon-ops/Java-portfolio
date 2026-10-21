package io.github.cheshmakzanoon.portfolio.stacks;

import static io.github.cheshmakzanoon.portfolio.stacks.SourceSyntax.*;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.util.*;
import javax.lang.model.element.Modifier;
import javax.tools.*;

/** Run only in the isolated worker. Syntax observations are never javac symbol attribution. */
public final class SourceJavaParser {
  private SourceJavaParser() {}

  public static SourceFacts.Unit parse(String source) throws IOException {
    var preflight = SourcePreflight.check(source);
    if (preflight != SourceFacts.Status.OBSERVED) return SourceFacts.Unit.unknown(preflight);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null)
      throw new IOException("JDK25 compiler module required for structural ingestion");
    boolean[] invalid = {false};
    int[] diagnostics = {0};
    try (var standard =
            compiler.getStandardFileManager(
                null, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8);
        var files = new SourceCompilerBoundary.DeniedFiles(standard)) {
      DiagnosticListener<JavaFileObject> listener =
          diagnostic -> {
            if (++diagnostics[0] > 128) throw new Quota();
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) invalid[0] = true;
          };
      var task =
          (JavacTask)
              compiler.getTask(
                  null,
                  files,
                  listener,
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
      var units = task.parse().iterator();
      if (!units.hasNext()) return SourceFacts.Unit.unknown(SourceFacts.Status.SYNTAX);
      CompilationUnitTree unit = units.next();
      if (units.hasNext() || invalid[0]) return SourceFacts.Unit.unknown(SourceFacts.Status.SYNTAX);
      int nodes = 0;
      var pending = new ArrayDeque<Tree>();
      pending.push(unit);
      while (!pending.isEmpty()) {
        if (++nodes > 16384) throw new Quota();
        for (Tree child : children(pending.pop())) pending.push(child);
      }
      var positions = Trees.instance(task).getSourcePositions();
      String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
      List<String> imports = new ArrayList<>();
      for (var i : unit.getImports())
        imports.add((i.isStatic() ? "static " : "") + i.getQualifiedIdentifier());
      List<SourceFacts.Type> types = new ArrayList<>();
      List<SourceFacts.Member> members = new ArrayList<>();
      // Include declarations in local/anonymous bodies in the inventory, even when their
      // captured/inherited bindings are unsupported. Unknown membership must never disappear.
      var classes = new ArrayDeque<NamedType>();
      var inventory = new ArrayDeque<TypeVisit>();
      inventory.push(new TypeVisit(unit, pkg, false));
      while (!inventory.isEmpty()) {
        var visit = inventory.pop();
        Tree t = visit.tree();
        String prefix = visit.owner();
        boolean local = visit.local();
        if (t instanceof ClassTree c) {
          int at = (int) positions.getStartPosition(unit, c);
          String part =
              c.getSimpleName().isEmpty()
                  ? "$anonymous$" + at
                  : local ? "$local$" + c.getSimpleName() + "$" + at : c.getSimpleName().toString();
          String owner = prefix.isEmpty() ? part : prefix + "." + part;
          classes.add(new NamedType(c, owner, local || c.getSimpleName().isEmpty()));
          prefix = owner;
          local = false;
        } else if (t instanceof MethodTree || t instanceof LambdaExpressionTree) local = true;
        for (Tree child : children(t)) inventory.push(new TypeVisit(child, prefix, local));
        if (classes.size() > 128) throw new Quota();
      }
      while (!classes.isEmpty()) {
        NamedType named = classes.remove();
        ClassTree c = named.tree();
        String owner = named.owner();
        var flags = c.getModifiers().getFlags();
        boolean record = c.getKind() == Tree.Kind.RECORD;
        List<SourceFacts.Binding> fields = new ArrayList<>();
        Map<String, String> components = new TreeMap<>();
        Set<String> fieldNames = new HashSet<>();
        for (Tree child : c.getMembers())
          if (child instanceof VariableTree v) fieldNames.add(v.getName().toString());
        for (Tree child : c.getMembers()) {
          if (child instanceof VariableTree variable) {
            fields.add(binding(variable));
            if (variable.getInitializer() != null)
              members.add(
                  executable(
                      unit,
                      positions,
                      owner,
                      "<field:" + variable.getName() + ">",
                      "",
                      List.of(),
                      variable.getInitializer(),
                      (int) positions.getStartPosition(unit, variable),
                      true,
                      variable.getModifiers().getFlags().contains(Modifier.STATIC),
                      fieldNames));
            // javac parse trees expose record header components as private final fields.
            // Explicit instance fields in a record are semantically invalid, so never infer
            // record accessors unless the declaration precedes the record body opening.
            long start = positions.getStartPosition(unit, variable);
            int body = bodyOpening(source, (int) positions.getStartPosition(unit, c));
            if (record
                && start >= 0
                && start < body
                && !variable.getModifiers().getFlags().contains(Modifier.STATIC))
              components.put(variable.getName().toString(), type(variable.getType()));
          } else if (child instanceof MethodTree method
              && positions.getStartPosition(unit, method) >= 0)
            members.add(member(unit, positions, owner, method, fieldNames));
          else if (child instanceof BlockTree block)
            members.add(
                executable(
                    unit,
                    positions,
                    owner,
                    "<initializer>",
                    "",
                    List.of(),
                    block,
                    (int) positions.getStartPosition(unit, block),
                    true,
                    block.isStatic(),
                    fieldNames));
          if (fields.size() > 64 || members.size() > 128) throw new Quota();
        }
        types.add(
            new SourceFacts.Type(
                owner,
                record
                    || flags.contains(Modifier.FINAL)
                    || c.getKind() == Tree.Kind.ENUM && !hasConstantSpecificBody(c),
                named.local()
                    || !c.getTypeParameters().isEmpty()
                    || c.getExtendsClause() != null
                    || !c.getImplementsClause().isEmpty(),
                fields,
                components));
        if (types.size() > 128) throw new Quota();
      }
      var result = new SourceFacts.Unit(SourceFacts.Status.OBSERVED, pkg, imports, types, members);
      try {
        SourceWire.encode(result);
      } catch (IOException oversized) {
        return SourceFacts.Unit.unknown(SourceFacts.Status.TREE_QUOTA);
      }
      return result;
    } catch (Quota | IllegalArgumentException unsupported) {
      return SourceFacts.Unit.unknown(SourceFacts.Status.TREE_QUOTA);
    }
  }

  private record NamedType(ClassTree tree, String owner, boolean local) {}

  private static boolean hasConstantSpecificBody(ClassTree declaration) {
    // An enum with a constant-specific class body is implicitly sealed, not final. Its
    // instance methods can dispatch to the constant's override rather than the enum body.
    return declaration.getMembers().stream()
        .filter(VariableTree.class::isInstance)
        .map(VariableTree.class::cast)
        .anyMatch(
            variable ->
                variable.getInitializer() instanceof NewClassTree creation
                    && creation.getClassBody() != null);
  }

  private record TypeVisit(Tree tree, String owner, boolean local) {}

  private record Visit(Tree tree, int block, boolean controlUnknown, boolean directStatement) {}

  private static SourceFacts.Member member(
      CompilationUnitTree unit,
      SourcePositions positions,
      String owner,
      MethodTree method,
      Set<String> fieldNames) {
    List<SourceFacts.Binding> parameters = new ArrayList<>();
    for (var parameter : method.getParameters()) parameters.add(binding(parameter));
    var result =
        executable(
            unit,
            positions,
            owner,
            method.getName().toString(),
            type(method.getReturnType()),
            parameters,
            method.getBody(),
            (int) positions.getStartPosition(unit, method),
            method.getModifiers().getFlags().contains(Modifier.PRIVATE),
            method.getModifiers().getFlags().contains(Modifier.STATIC),
            fieldNames);
    boolean unsupported =
        !method.getTypeParameters().isEmpty()
            || method.getParameters().stream().anyMatch(p -> p.toString().contains("..."));
    return !unsupported
        ? result
        : new SourceFacts.Member(
            result.owner(),
            result.name(),
            result.returnType(),
            result.parameters(),
            result.locals(),
            result.identifiers(),
            result.calls(),
            result.guards(),
            result.returned(),
            result.position(),
            result.privateMember(),
            result.staticMember(),
            true,
            result.controlUnknown(),
            result.callsComplete());
  }

  private static SourceFacts.Member executable(
      CompilationUnitTree unit,
      SourcePositions positions,
      String owner,
      String memberName,
      String returnType,
      List<SourceFacts.Binding> parameters,
      Tree body,
      int position,
      boolean privateMember,
      boolean staticMember,
      Set<String> fieldNames) {
    Map<String, SourceFacts.Binding> locals = new LinkedHashMap<>();
    Set<String> identifiers = new TreeSet<>();
    List<SourceFacts.Invocation> calls = new ArrayList<>();
    List<SourceFacts.Guard> guards = new ArrayList<>();
    Set<String> writes = new HashSet<>();
    List<SourceFacts.Returned> returned = new ArrayList<>();
    var pending = new ArrayDeque<Visit>();
    if (body != null)
      pending.push(new Visit(body, (int) positions.getStartPosition(unit, body), false, false));
    int nodes = 0;
    boolean unknown = false, uncertainControl = false, callsComplete = true;
    Tree directFinal =
        body instanceof BlockTree top && !top.getStatements().isEmpty()
            ? top.getStatements().getLast()
            : null;
    while (!pending.isEmpty()) {
      Visit visit = pending.pop();
      Tree t = visit.tree();
      if (++nodes > 512) throw new Quota();
      // Nested/local classes have separate executable scopes and may capture unknown bindings.
      if (t instanceof ClassTree) continue;
      if (t instanceof LambdaExpressionTree) {
        unknown = true;
        uncertainControl = true;
        callsComplete = false;
        continue;
      }
      int block =
          t instanceof BlockTree ? (int) positions.getStartPosition(unit, t) : visit.block();
      boolean controlUnknown =
          visit.controlUnknown()
              || t instanceof TryTree
              || t instanceof SwitchTree
              || t instanceof SynchronizedTree
              || t instanceof BreakTree
              || t instanceof ContinueTree;
      if (controlUnknown) uncertainControl = true;
      if (t instanceof VariableTree v) {
        String name = v.getName().toString();
        var base = binding(v);
        var b =
            new SourceFacts.Binding(
                name,
                v.getType() == null ? "var" : base.type(),
                base.initializer(),
                false,
                (int) positions.getStartPosition(unit, t),
                block);
        if (locals.containsKey(name)
            || fieldNames.contains(name)
            || parameters.stream().anyMatch(p -> p.name().equals(name))) {
          b =
              new SourceFacts.Binding(
                  name, b.type(), b.initializer(), true, b.position(), b.block());
          unknown = true;
        }
        locals.put(name, b);
      }
      if (t instanceof AssignmentTree a && a.getVariable() instanceof IdentifierTree i) {
        String name = i.getName().toString();
        writes.add(name);
        var old = locals.get(name);
        if (old != null)
          locals.put(
              name,
              new SourceFacts.Binding(
                  name, old.type(), old.initializer(), true, old.position(), old.block()));
      }
      if (t instanceof AssignmentTree a) {
        boolean localWrite =
            a.getVariable() instanceof IdentifierTree i
                && (locals.containsKey(i.getName().toString())
                    || parameters.stream().anyMatch(p -> p.name().contentEquals(i.getName())));
        // No field/array escape or object-state provenance is modeled by this local flow lane.
        if (!localWrite) uncertainControl = true;
      }
      if (t instanceof CompoundAssignmentTree a && a.getVariable() instanceof IdentifierTree i) {
        String name = i.getName().toString();
        var old = locals.get(name);
        if (old != null) {
          boolean supported =
              Set.of("int", "long").contains(old.type())
                  && a.getKind() == Tree.Kind.PLUS_ASSIGNMENT
                  && old.initializer().kind().equals("literal")
                  && old.initializer().text().startsWith("zero:")
                  && a.getExpression() instanceof IdentifierTree;
          locals.put(
              name,
              new SourceFacts.Binding(
                  name,
                  old.type(),
                  supported
                      ? new SourceFacts.Expression(
                          "sum",
                          "accumulator",
                          List.of(old.initializer(), expression(a.getExpression(), 0)))
                      : old.initializer(),
                  !supported,
                  old.position(),
                  old.block()));
        } else writes.add(name);
      }
      if (t instanceof UnaryTree unary
          && Set.of(
                  Tree.Kind.PREFIX_INCREMENT,
                  Tree.Kind.PREFIX_DECREMENT,
                  Tree.Kind.POSTFIX_INCREMENT,
                  Tree.Kind.POSTFIX_DECREMENT)
              .contains(t.getKind())
          && unary.getExpression() instanceof IdentifierTree i) writes.add(i.getName().toString());
      if (t instanceof ReturnTree r && r.getExpression() != null)
        returned.add(
            new SourceFacts.Returned(
                expression(r.getExpression(), 0),
                (int) positions.getStartPosition(unit, t),
                block,
                t == directFinal));
      if (t instanceof MethodInvocationTree invocation)
        calls.add(
            new SourceFacts.Invocation(
                expression(invocation, 0), (int) positions.getStartPosition(unit, t), block));
      if (t instanceof NewClassTree construction) {
        List<SourceFacts.Expression> args = new ArrayList<>();
        args.add(
            construction.getClassBody() == null
                ? new SourceFacts.Expression("new", type(construction.getIdentifier()), List.of())
                : SourceFacts.Expression.unknown());
        for (Tree arg : construction.getArguments()) args.add(expression(arg, 1));
        calls.add(
            new SourceFacts.Invocation(
                new SourceFacts.Expression("call", "<init>", args),
                (int) positions.getStartPosition(unit, t),
                block));
      }
      if (t instanceof MemberReferenceTree reference) {
        // Unknown arity reference remains in the potential-call inventory; not a false zero.
        calls.add(
            new SourceFacts.Invocation(
                new SourceFacts.Expression(
                    "call",
                    reference.getName().toString(),
                    List.of(
                        expression(reference.getQualifierExpression(), 0),
                        new SourceFacts.Expression("unknown", "reference", List.of()))),
                (int) positions.getStartPosition(unit, t),
                block));
      }
      if (t instanceof IfTree branch
          && visit.directStatement()
          && !controlUnknown
          && branch.getElseStatement() == null) {
        String terminal = terminal(branch.getThenStatement());
        if (terminal != null)
          guards.add(
              new SourceFacts.Guard(
                  expression(branch.getCondition(), 0),
                  (int) positions.getEndPosition(unit, branch),
                  block,
                  terminal));
      }
      if (t instanceof IdentifierTree i) identifiers.add(i.getName().toString());
      if (t instanceof MemberSelectTree select) identifiers.add(select.getIdentifier().toString());
      if (locals.size() > 64
          || calls.size() > 128
          || guards.size() > 128
          || returned.size() > 128
          || identifiers.size() > 128) throw new Quota();
      var childList = children(t);
      for (int i = childList.size() - 1; i >= 0; i--) {
        Tree child = childList.get(i);
        if (t instanceof VariableTree v && (child == v.getType() || child == v.getModifiers()))
          continue;
        if (t instanceof NewClassTree n
            && (child == n.getIdentifier() || child == n.getClassBody())) continue;
        pending.push(
            new Visit(
                child,
                block,
                controlUnknown,
                t instanceof BlockTree statements && statements.getStatements().contains(child)));
      }
    }
    // Executable declared-variable references and actual callees are anchors. There is no
    // uppercase-name/owner/style heuristic and no class/import text in this coordinate.
    Set<String> variableNames = new HashSet<>(locals.keySet());
    variableNames.addAll(fieldNames);
    parameters.forEach(p -> variableNames.add(p.name()));
    identifiers.retainAll(variableNames);
    for (var call : calls)
      if (!call.expression().text().equals("<init>")) identifiers.add(call.expression().text());
    if (uncertainControl) guards.clear();
    List<SourceFacts.Binding> checkedParameters = new ArrayList<>();
    for (var p : parameters)
      checkedParameters.add(
          writes.contains(p.name())
              ? new SourceFacts.Binding(p.name(), p.type(), p.initializer(), true)
              : p);
    for (String written : writes)
      if (locals.containsKey(written)) {
        var old = locals.get(written);
        locals.put(
            written,
            new SourceFacts.Binding(
                old.name(), old.type(), old.initializer(), true, old.position(), old.block()));
      }
    return new SourceFacts.Member(
        owner,
        memberName,
        returnType,
        checkedParameters,
        new ArrayList<>(locals.values()),
        identifiers,
        calls,
        guards,
        returned,
        position,
        privateMember,
        staticMember,
        unknown,
        uncertainControl,
        callsComplete);
  }

  private static SourceFacts.Binding binding(VariableTree variable) {
    return new SourceFacts.Binding(
        variable.getName().toString(),
        type(variable.getType()),
        expression(variable.getInitializer(), 0),
        false);
  }

  private static String terminal(StatementTree statement) {
    if (statement instanceof BlockTree block) {
      if (block.getStatements().size() != 1) return null;
      statement = block.getStatements().getFirst();
    }
    return statement instanceof ReturnTree
        ? "return"
        : statement instanceof ThrowTree ? "throw" : null;
  }

  private static int bodyOpening(String source, int start) {
    // This is only an accessor eligibility restriction; uncertain headers produce no accessor.
    int opening = source.indexOf('{', Math.max(0, start));
    return opening < 0 ? 0 : opening;
  }

  private static final class Quota extends RuntimeException {}
}
