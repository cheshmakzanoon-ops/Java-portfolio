package io.github.cheshmakzanoon.portfolio.faultline.dataflow;

import static io.github.cheshmakzanoon.portfolio.faultline.dataflow.InvocationEffects.mutableReference;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.*;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.faultline.rules.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Original JVM computational-value transfer. Category-two values occupy one stack cell/two local
 * slots.
 */
public final class TransferFunction implements Opcodes {
  private final MethodIr method;
  private final HeapSummary heap;
  private final RuleModels models;
  private final CallResolver resolver;
  private final Map<String, MethodSummary> summaries;
  private final AnalysisBudget budget;
  private final Map<CallContext, MethodSummary> contexts;
  private final Set<String> diagnostics;
  private final SortedMap<String, MethodSummary.Flow> flows = new TreeMap<>();
  private Taint returns = Taint.BOTTOM;
  private final SortedMap<String, Taint> fieldWrites = new TreeMap<>();
  private final SortedMap<Integer, Taint> elementWrites = new TreeMap<>();

  public TransferFunction(
      MethodIr method,
      HeapSummary heap,
      RuleModels models,
      CallResolver resolver,
      Map<String, MethodSummary> summaries,
      AnalysisBudget budget,
      Set<String> diagnostics) {
    this(method, heap, models, resolver, summaries, budget, diagnostics, new TreeMap<>());
  }

  public TransferFunction(
      MethodIr method,
      HeapSummary heap,
      RuleModels models,
      CallResolver resolver,
      Map<String, MethodSummary> summaries,
      AnalysisBudget budget,
      Set<String> diagnostics,
      Map<CallContext, MethodSummary> contexts) {
    this.contexts = contexts;
    this.method = method;
    this.heap = heap;
    this.models = models;
    this.resolver = resolver;
    this.summaries = summaries;
    this.budget = budget;
    this.diagnostics = diagnostics;
  }

  public MethodSummary summary() {
    return new MethodSummary(returns, flows, elementWrites, fieldWrites);
  }

  private AbstractValue value(AbstractValue.Kind kind, Taint taint) {
    return AbstractValue.of(kind, taint);
  }

  private AbstractValue pop(FrameState frame, AbstractValue.Kind kind) {
    AbstractValue operand = frame.pop();
    requireKind(operand, kind);
    return operand;
  }

  private void unary(FrameState frame, AbstractValue.Kind from, AbstractValue.Kind to) {
    frame.push(value(to, pop(frame, from).taint()));
  }

  private void binary(
      FrameState frame,
      AbstractValue.Kind left,
      AbstractValue.Kind right,
      AbstractValue.Kind result) {
    Taint taint = pop(frame, right).taint().join(pop(frame, left).taint());
    frame.push(value(result, taint));
  }

  private AbstractValue.Kind fieldKind(FieldInsnNode field) {
    Type type = Type.getType(field.desc);
    if (type.getSort() == Type.VOID || type.getSort() == Type.METHOD)
      throw new IllegalArgumentException("invalid field descriptor");
    return AbstractValue.kind(type);
  }

  public void apply(int ordinal, FrameState f) {
    if (f.slots() > method.node.maxStack)
      throw new IllegalArgumentException("incoming maxStack exceeded: " + method.at(ordinal));
    AbstractInsnNode n = method.instructions.get(ordinal);
    int op = n.getOpcode();
    InstructionId site = method.at(ordinal);
    switch (op) {
      case NOP -> {}
      case ACONST_NULL -> f.push(AbstractValue.constant("null"));
      case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 ->
          f.push(AbstractValue.constant(op - ICONST_0));
      case LCONST_0, LCONST_1 -> f.push(AbstractValue.constant((long) (op - LCONST_0)));
      case FCONST_0, FCONST_1, FCONST_2 -> f.push(AbstractValue.constant((float) (op - FCONST_0)));
      case DCONST_0, DCONST_1 -> f.push(AbstractValue.constant((double) (op - DCONST_0)));
      case BIPUSH, SIPUSH -> f.push(AbstractValue.constant(((IntInsnNode) n).operand));
      case LDC -> {
        Object constant = ((LdcInsnNode) n).cst;
        if (constant instanceof ConstantDynamic dynamic) {
          diagnostics.add(
              "UNSUPPORTED_BOOTSTRAP "
                  + site
                  + " constant-dynamic "
                  + dynamic.getBootstrapMethod().getOwner());
          f.push(
              value(
                  AbstractValue.kind(Type.getType(dynamic.getDescriptor())), Taint.unknownValue()));
        } else f.push(AbstractValue.constant(constant));
      }
      case ILOAD, LLOAD, FLOAD, DLOAD, ALOAD -> {
        int slot = ((VarInsnNode) n).var;
        if (slot < 0 || slot >= f.locals.length)
          throw new IllegalArgumentException("local read out of bounds");
        AbstractValue v = f.locals[slot];
        if (v.kind() == AbstractValue.Kind.TOP)
          throw new IllegalArgumentException("load undefined local at " + site);
        requireKind(
            v,
            switch (op) {
              case ILOAD -> AbstractValue.Kind.INT;
              case LLOAD -> AbstractValue.Kind.LONG;
              case FLOAD -> AbstractValue.Kind.FLOAT;
              case DLOAD -> AbstractValue.Kind.DOUBLE;
              default -> AbstractValue.Kind.REF;
            });
        f.push(v);
      }
      case ISTORE, LSTORE, FSTORE, DSTORE, ASTORE -> {
        AbstractValue v = f.pop();
        requireKind(
            v,
            switch (op) {
              case ISTORE -> AbstractValue.Kind.INT;
              case LSTORE -> AbstractValue.Kind.LONG;
              case FSTORE -> AbstractValue.Kind.FLOAT;
              case DSTORE -> AbstractValue.Kind.DOUBLE;
              default -> AbstractValue.Kind.REF;
            });
        f.store(((VarInsnNode) n).var, v);
      }
      case IINC -> {
        int slot = ((IincInsnNode) n).var;
        if (slot < 0 || slot >= f.locals.length)
          throw new IllegalArgumentException("iinc local out of bounds");
        if (f.locals[slot].kind() != AbstractValue.Kind.INT)
          throw new IllegalArgumentException("iinc on non-int");
      }
      case POP -> {
        if (f.pop().size() != 1) throw new IllegalArgumentException("pop category two");
      }
      case POP2 -> {
        if (f.pop().size() == 1 && f.pop().size() != 1)
          throw new IllegalArgumentException("invalid pop2");
      }
      case DUP, DUP_X1, DUP_X2, DUP2, DUP2_X1, DUP2_X2, SWAP -> duplicate(op, f);
      case IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR ->
          binary(f, AbstractValue.Kind.INT, AbstractValue.Kind.INT, AbstractValue.Kind.INT);
      case LADD, LSUB, LMUL, LDIV, LREM, LAND, LOR, LXOR ->
          binary(f, AbstractValue.Kind.LONG, AbstractValue.Kind.LONG, AbstractValue.Kind.LONG);
      case LSHL, LSHR, LUSHR ->
          binary(f, AbstractValue.Kind.LONG, AbstractValue.Kind.INT, AbstractValue.Kind.LONG);
      case FADD, FSUB, FMUL, FDIV, FREM ->
          binary(f, AbstractValue.Kind.FLOAT, AbstractValue.Kind.FLOAT, AbstractValue.Kind.FLOAT);
      case DADD, DSUB, DMUL, DDIV, DREM ->
          binary(
              f, AbstractValue.Kind.DOUBLE, AbstractValue.Kind.DOUBLE, AbstractValue.Kind.DOUBLE);
      case LCMP ->
          binary(f, AbstractValue.Kind.LONG, AbstractValue.Kind.LONG, AbstractValue.Kind.INT);
      case FCMPL, FCMPG ->
          binary(f, AbstractValue.Kind.FLOAT, AbstractValue.Kind.FLOAT, AbstractValue.Kind.INT);
      case DCMPL, DCMPG ->
          binary(f, AbstractValue.Kind.DOUBLE, AbstractValue.Kind.DOUBLE, AbstractValue.Kind.INT);
      case INEG, I2B, I2C, I2S -> unary(f, AbstractValue.Kind.INT, AbstractValue.Kind.INT);
      case LNEG -> unary(f, AbstractValue.Kind.LONG, AbstractValue.Kind.LONG);
      case FNEG -> unary(f, AbstractValue.Kind.FLOAT, AbstractValue.Kind.FLOAT);
      case DNEG -> unary(f, AbstractValue.Kind.DOUBLE, AbstractValue.Kind.DOUBLE);
      case L2I -> unary(f, AbstractValue.Kind.LONG, AbstractValue.Kind.INT);
      case F2I -> unary(f, AbstractValue.Kind.FLOAT, AbstractValue.Kind.INT);
      case D2I -> unary(f, AbstractValue.Kind.DOUBLE, AbstractValue.Kind.INT);
      case I2L -> unary(f, AbstractValue.Kind.INT, AbstractValue.Kind.LONG);
      case F2L -> unary(f, AbstractValue.Kind.FLOAT, AbstractValue.Kind.LONG);
      case D2L -> unary(f, AbstractValue.Kind.DOUBLE, AbstractValue.Kind.LONG);
      case I2F -> unary(f, AbstractValue.Kind.INT, AbstractValue.Kind.FLOAT);
      case L2F -> unary(f, AbstractValue.Kind.LONG, AbstractValue.Kind.FLOAT);
      case D2F -> unary(f, AbstractValue.Kind.DOUBLE, AbstractValue.Kind.FLOAT);
      case I2D -> unary(f, AbstractValue.Kind.INT, AbstractValue.Kind.DOUBLE);
      case L2D -> unary(f, AbstractValue.Kind.LONG, AbstractValue.Kind.DOUBLE);
      case F2D -> unary(f, AbstractValue.Kind.FLOAT, AbstractValue.Kind.DOUBLE);
      case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, TABLESWITCH, LOOKUPSWITCH ->
          pop(f, AbstractValue.Kind.INT);
      case IFNULL, IFNONNULL -> pop(f, AbstractValue.Kind.REF);
      case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> {
        pop(f, AbstractValue.Kind.INT);
        pop(f, AbstractValue.Kind.INT);
      }
      case IF_ACMPEQ, IF_ACMPNE -> {
        pop(f, AbstractValue.Kind.REF);
        pop(f, AbstractValue.Kind.REF);
      }
      case GOTO -> {}
      case JSR, RET -> throw new IllegalArgumentException("legacy jsr/ret unsupported: " + site);
      case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN -> {
        AbstractValue.Kind kind =
            switch (op) {
              case IRETURN -> AbstractValue.Kind.INT;
              case LRETURN -> AbstractValue.Kind.LONG;
              case FRETURN -> AbstractValue.Kind.FLOAT;
              case DRETURN -> AbstractValue.Kind.DOUBLE;
              default -> AbstractValue.Kind.REF;
            };
        if (AbstractValue.kind(Type.getReturnType(method.node.desc)) != kind)
          throw new IllegalArgumentException(
              "return opcode disagrees with method descriptor: " + site);
        AbstractValue returnedValue = pop(f, kind);
        returns = returns.join(heap.elements(returnedValue));
        if (op == ARETURN && mutableReference(Type.getReturnType(method.node.desc)))
          heap.alias("return:" + method.scope, returnedValue.references());
      }
      case RETURN -> {
        if (Type.getReturnType(method.node.desc).getSort() != Type.VOID)
          throw new IllegalArgumentException("void return in non-void method: " + site);
      }
      case ATHROW -> pop(f, AbstractValue.Kind.REF);
      case NEW -> f.push(AbstractValue.reference(Taint.BOTTOM, "new:" + site));
      case NEWARRAY, ANEWARRAY -> {
        if (op == NEWARRAY) {
          int type = ((IntInsnNode) n).operand;
          if (type < T_BOOLEAN || type > T_LONG)
            throw new IllegalArgumentException("invalid primitive array type");
        }
        Taint t = pop(f, AbstractValue.Kind.INT).taint();
        f.push(AbstractValue.reference(t, "array:" + site));
      }
      case MULTIANEWARRAY -> {
        MultiANewArrayInsnNode array = (MultiANewArrayInsnNode) n;
        Type type = Type.getType(array.desc);
        if (type.getSort() != Type.ARRAY || array.dims < 1 || array.dims > type.getDimensions())
          throw new IllegalArgumentException("invalid multi-dimensional array dimensions");
        Taint t = Taint.BOTTOM;
        for (int i = 0; i < array.dims; i++) t = t.join(pop(f, AbstractValue.Kind.INT).taint());
        f.push(AbstractValue.reference(t, "array:" + site));
      }
      case IALOAD, BALOAD, CALOAD, SALOAD, LALOAD, FALOAD, DALOAD, AALOAD -> {
        Taint index = pop(f, AbstractValue.Kind.INT).taint();
        AbstractValue array = pop(f, AbstractValue.Kind.REF);
        AbstractValue.Kind kind =
            switch (op) {
              case LALOAD -> AbstractValue.Kind.LONG;
              case FALOAD -> AbstractValue.Kind.FLOAT;
              case DALOAD -> AbstractValue.Kind.DOUBLE;
              case AALOAD -> AbstractValue.Kind.REF;
              default -> AbstractValue.Kind.INT;
            };
        f.push(
            new AbstractValue(
                kind,
                heap.elements(array).join(index),
                kind == AbstractValue.Kind.REF ? array.references() : new TreeSet<>(),
                null));
      }
      case IASTORE, LASTORE, FASTORE, DASTORE, AASTORE, BASTORE, CASTORE, SASTORE -> {
        AbstractValue.Kind kind =
            switch (op) {
              case LASTORE -> AbstractValue.Kind.LONG;
              case FASTORE -> AbstractValue.Kind.FLOAT;
              case DASTORE -> AbstractValue.Kind.DOUBLE;
              case AASTORE -> AbstractValue.Kind.REF;
              default -> AbstractValue.Kind.INT;
            };
        AbstractValue v = pop(f, kind);
        pop(f, AbstractValue.Kind.INT);
        AbstractValue array = pop(f, AbstractValue.Kind.REF);
        heap.addElement(array, heap.elements(v));
        if (op == AASTORE)
          for (String reference : array.references()) heap.alias(reference, v.references());
        recordEffect(array, heap.elements(v));
        if (v.constant() != null)
          for (String ref : array.references())
            heap.write(ref + "#const:" + v.constant(), Taint.source("CONST"));
      }
      case ARRAYLENGTH -> {
        // Array creation records length dependence on the reference itself. Element
        // contents are separate heap facts and cannot change the allocation length.
        f.push(value(AbstractValue.Kind.INT, pop(f, AbstractValue.Kind.REF).taint()));
      }
      case CHECKCAST -> f.push(pop(f, AbstractValue.Kind.REF));
      case INSTANCEOF -> {
        pop(f, AbstractValue.Kind.REF);
        f.push(value(AbstractValue.Kind.INT, Taint.BOTTOM));
      }
      case MONITORENTER, MONITOREXIT -> pop(f, AbstractValue.Kind.REF);
      case GETFIELD, GETSTATIC -> {
        FieldInsnNode field = (FieldInsnNode) n;
        AbstractValue receiver =
            op == GETFIELD
                ? pop(f, AbstractValue.Kind.REF)
                : AbstractValue.of(AbstractValue.Kind.REF, Taint.BOTTOM);
        AbstractValue loaded =
            heap.readField(field.owner + "." + field.name + field.desc, fieldKind(field));
        f.push(
            new AbstractValue(
                loaded.kind(),
                loaded.taint().join(heap.elements(receiver)),
                loaded.references(),
                loaded.constant()));
      }
      case PUTFIELD, PUTSTATIC -> {
        FieldInsnNode field = (FieldInsnNode) n;
        AbstractValue v = pop(f, fieldKind(field));
        if (op == PUTFIELD) pop(f, AbstractValue.Kind.REF);
        String fieldKey = field.owner + "." + field.name + field.desc;
        heap.writeField(fieldKey, v);
        fieldWrites.merge(fieldKey, heap.elements(v), Taint::join);
      }
      case INVOKESTATIC, INVOKESPECIAL, INVOKEVIRTUAL, INVOKEINTERFACE ->
          call((MethodInsnNode) n, f, site);
      case INVOKEDYNAMIC -> dynamic((InvokeDynamicInsnNode) n, f, site);
      default -> throw new IllegalArgumentException("unsupported opcode " + op + " at " + site);
    }
    if (f.slots() > method.node.maxStack)
      throw new IllegalArgumentException("maxStack exceeded: " + site);
  }

  private void duplicate(int opcode, FrameState f) {
    AbstractValue a = f.pop(), b, c, d;
    switch (opcode) {
      case DUP -> {
        cat1(a);
        f.push(a);
        f.push(a);
      }
      case SWAP -> {
        b = f.pop();
        cat1(a);
        cat1(b);
        f.push(a);
        f.push(b);
      }
      case DUP_X1 -> {
        b = f.pop();
        cat1(a);
        cat1(b);
        f.push(a);
        f.push(b);
        f.push(a);
      }
      case DUP_X2 -> {
        cat1(a);
        b = f.pop();
        if (b.size() == 2) {
          f.push(a);
          f.push(b);
          f.push(a);
        } else {
          c = f.pop();
          cat1(c);
          f.push(a);
          f.push(c);
          f.push(b);
          f.push(a);
        }
      }
      case DUP2 -> {
        if (a.size() == 2) {
          f.push(a);
          f.push(a);
        } else {
          b = f.pop();
          cat1(b);
          f.push(b);
          f.push(a);
          f.push(b);
          f.push(a);
        }
      }
      case DUP2_X1 -> {
        if (a.size() == 2) {
          b = f.pop();
          cat1(b);
          f.push(a);
          f.push(b);
          f.push(a);
        } else {
          b = f.pop();
          c = f.pop();
          cat1(b);
          cat1(c);
          f.push(b);
          f.push(a);
          f.push(c);
          f.push(b);
          f.push(a);
        }
      }
      case DUP2_X2 -> {
        if (a.size() == 2) {
          b = f.pop();
          if (b.size() == 2) {
            f.push(a);
            f.push(b);
            f.push(a);
          } else {
            c = f.pop();
            cat1(c);
            f.push(a);
            f.push(c);
            f.push(b);
            f.push(a);
          }
        } else {
          b = f.pop();
          cat1(b);
          c = f.pop();
          if (c.size() == 2) {
            f.push(b);
            f.push(a);
            f.push(c);
            f.push(b);
            f.push(a);
          } else {
            d = f.pop();
            cat1(d);
            f.push(b);
            f.push(a);
            f.push(d);
            f.push(c);
            f.push(b);
            f.push(a);
          }
        }
      }
      default -> throw new IllegalArgumentException("bad duplication");
    }
  }

  private void requireKind(AbstractValue value, AbstractValue.Kind expected) {
    if (value.kind() != expected)
      throw new IllegalArgumentException(
          "invalid operand kind: expected " + expected + " got " + value.kind());
  }

  private void cat1(AbstractValue value) {
    if (value.size() != 1) throw new IllegalArgumentException("invalid duplication category");
  }

  private void dynamic(InvokeDynamicInsnNode n, FrameState f, InstructionId site) {
    Type[] types = Type.getArgumentTypes(n.desc);
    Taint t = Taint.BOTTOM;
    for (int i = types.length - 1; i >= 0; i--)
      t = t.join(heap.elements(pop(f, AbstractValue.kind(types[i]))));
    String owner = n.bsm.getOwner();
    if (owner.equals("java/lang/invoke/StringConcatFactory")
        && Set.of("makeConcat", "makeConcatWithConstants").contains(n.bsm.getName())) {
      Type result = Type.getReturnType(n.desc);
      if (result.getSort() != Type.VOID) f.push(value(AbstractValue.kind(result), t));
    } else if (owner.equals("java/lang/invoke/LambdaMetafactory")
        && Set.of("metafactory", "altMetafactory").contains(n.bsm.getName())) {
      String target = "lambda:" + site;
      for (Object arg : n.bsmArgs)
        if (arg instanceof Handle h)
          target = "lambda:" + h.getTag() + ":" + h.getOwner() + "." + h.getName() + h.getDesc();
      Type result = Type.getReturnType(n.desc);
      if (result.getSort() != Type.VOID)
        f.push(
            AbstractValue.kind(result) == AbstractValue.Kind.REF
                ? AbstractValue.reference(t, target)
                : value(AbstractValue.kind(result), t));
    } else {
      diagnostics.add("UNSUPPORTED_BOOTSTRAP " + site + " " + owner);
      if (Type.getReturnType(n.desc).getSort() != Type.VOID)
        f.push(value(AbstractValue.kind(Type.getReturnType(n.desc)), t.join(Taint.unknownValue())));
    }
  }

  private void call(MethodInsnNode n, FrameState f, InstructionId site) {
    Type[] types = Type.getArgumentTypes(n.desc);
    ArrayList<AbstractValue> args = new ArrayList<>();
    for (int i = types.length - 1; i >= 0; i--) {
      AbstractValue value = f.pop();
      requireKind(value, AbstractValue.kind(types[i]));
      args.addFirst(value);
    }
    AbstractValue receiver =
        n.getOpcode() == INVOKESTATIC
            ? AbstractValue.of(AbstractValue.Kind.REF, Taint.BOTTOM)
            : pop(f, AbstractValue.Kind.REF);
    String id = n.owner + "." + n.name + n.desc;
    Taint joined = heap.elements(receiver);
    for (AbstractValue arg : args) joined = joined.join(heap.elements(arg));
    Taint returned = Taint.BOTTOM;
    SortedSet<String> resultRefs = new TreeSet<>();
    List<AbstractValue> operands = new ArrayList<>(args);
    operands.add(receiver);
    boolean opaque = false;
    RuleModels.Sink sink = models.sink(id);
    if (sink != null && args.size() > sink.argument())
      flow(
          sink.id(),
          sink.mask(),
          site,
          id,
          heap.elements(args.get(sink.argument())),
          List.of(method.id),
          false);
    if (n.owner.equals("java/lang/Runtime")
        && n.name.equals("exec")
        && n.desc.startsWith("([Ljava/lang/String;")) {
      AbstractValue commands = args.getFirst();
      if (shell(commands))
        flow("BG-SHELL-COMMAND", 2, site, id, heap.elements(commands), List.of(method.id), false);
    }
    if (n.owner.equals("java/lang/ProcessBuilder") && n.name.equals("<init>")) {
      heap.addElement(receiver, joined);
      if (!args.isEmpty() && shell(args.getFirst()))
        for (String ref : receiver.references()) heap.write(ref + "#shell", Taint.source("CONST"));
    }
    if (n.owner.equals("java/lang/ProcessBuilder")
        && n.name.equals("start")
        && receiver.references().stream()
            .anyMatch(r -> !heap.read(r + "#shell").equals(Taint.BOTTOM)))
      flow("BG-SHELL-COMMAND", 2, site, id, heap.elements(receiver), List.of(method.id), false);
    if (models.source(id)) returned = Taint.source(new SourceIdentity(site, id).label());
    else if (models.sanitizer(id) != 0) returned = joined.sanitize(models.sanitizer(id));
    else if (n.owner.equals("java/lang/StringBuilder")
        || n.owner.equals("java/lang/StringBuffer")) {
      if (n.name.equals("append") || n.name.equals("<init>")) heap.addElement(receiver, joined);
      recordEffect(receiver, joined);
      returned = heap.elements(receiver).join(joined);
      resultRefs.addAll(receiver.references());
    } else if (n.owner.equals("java/lang/String")
        || n.owner.equals("java/lang/CharSequence")
        || n.owner.equals("java/nio/file/Path")
        || n.owner.equals("java/nio/file/Paths")
        || n.owner.equals("java/net/URI")
        || n.owner.equals("java/io/File")
        || n.owner.matches("java/lang/(Integer|Long|Double|Float|Boolean|Character|Short|Byte)")) {
      returned = joined;
    } else if (n.owner.startsWith("java/util/")
        && (n.owner.contains("List")
            || n.owner.contains("Collection")
            || n.owner.contains("Map")
            || n.owner.contains("Set")
            || n.owner.contains("Iterator")
            || n.owner.contains("Optional"))) {
      if (Set.of("add", "put", "set", "offer", "push", "<init>").contains(n.name))
        heap.addElement(receiver, joined);
      recordEffect(receiver, joined);
      returned = heap.elements(receiver).join(joined);
      resultRefs.addAll(receiver.references());
    } else if (n.owner.startsWith("java/util/function/") && !receiver.references().isEmpty()) {
      returned = joined;
      boolean identified = false;
      for (String ref : receiver.references())
        if (ref.startsWith("lambda:")) {
          identified = true;
          int separator = ref.indexOf(':', 7);
          if (separator < 0) {
            diagnostics.add("UNSUPPORTED_LAMBDA " + site);
            continue;
          }
          int tag = Integer.parseInt(ref.substring(7, separator));
          String lambdaTarget = ref.substring(separator + 1);
          MethodSummary summary = summaries.get(lambdaTarget);
          if (tag != H_INVOKESTATIC) {
            diagnostics.add("UNSUPPORTED_LAMBDA_DISPATCH " + site + " " + lambdaTarget);
            returned = returned.join(Taint.unknownValue());
          }
          if (summary == null) {
            diagnostics.add("UNSUPPORTED_LAMBDA_TARGET " + site + " " + lambdaTarget);
            returned = returned.join(Taint.unknownValue());
          }
          if (summary != null) {
            List<Taint> actual = new ArrayList<>();
            int arity =
                Type.getArgumentTypes(lambdaTarget.substring(lambdaTarget.indexOf('('))).length
                    + (tag == H_INVOKESTATIC ? 0 : 1);
            for (int captured = 0; captured < Math.max(0, arity - args.size()); captured++)
              actual.add(heap.elements(receiver));
            for (AbstractValue a : args) actual.add(heap.elements(a));
            if (!summary.elementWrites().isEmpty()
                || !summary.fieldWrites().isEmpty()
                || mutableReference(
                    Type.getReturnType(lambdaTarget.substring(lambdaTarget.indexOf('('))))) {
              diagnostics.add("UNSUPPORTED_LAMBDA_HEAP_EFFECT " + site);
              returned = returned.join(Taint.unknownValue());
            }
            MethodSummary instantiated = summary.instantiate(actual, lambdaTarget);
            returned = returned.join(instantiated.returns());
            importFlows(instantiated, null, site);
          }
        }
      if (!identified) {
        diagnostics.add("UNSUPPORTED_LAMBDA_TARGET " + site + " unknown functional receiver");
        returned = returned.join(Taint.unknownValue());
      }
    } else {
      List<String> targets = resolver.resolve(n.owner, n.name, n.desc, n.getOpcode());
      if (targets.size() > budget.dispatchTargets) {
        diagnostics.add(
            "DISPATCH_LIMIT "
                + site
                + " targets="
                + targets.size()
                + " limit="
                + budget.dispatchTargets);
        returned = joined.join(Taint.unknownValue());
        opaque = true;
      } else if (!targets.isEmpty()) {
        List<Taint> actual = new ArrayList<>();
        if (n.getOpcode() != INVOKESTATIC) actual.add(heap.elements(receiver));
        for (AbstractValue a : args) actual.add(heap.elements(a));
        for (String target : targets) {
          MethodSummary targetSummary = summaries.get(target);
          if (targetSummary == null) {
            diagnostics.add("UNKNOWN_CALL " + site + " " + target);
            returned = returned.join(InvocationEffects.opaque(heap, operands, this::recordEffect));
            opaque = true;
            continue;
          }
          String targetScope = MethodScope.of(target);
          int localSlot = 0;
          if (n.getOpcode() != INVOKESTATIC) {
            if (mutableReference(Type.getObjectType(n.owner)))
              heap.alias("this:" + targetScope, receiver.references());
            localSlot = 1;
          }
          Type[] parameterTypes = Type.getArgumentTypes(n.desc);
          for (int argument = 0; argument < parameterTypes.length; argument++) {
            if (mutableReference(parameterTypes[argument]))
              heap.alias(
                  "parameter:" + targetScope + ":" + localSlot, args.get(argument).references());
            localSlot += parameterTypes[argument].getSize();
          }
          if (mutableReference(Type.getReturnType(n.desc))) resultRefs.add("return:" + targetScope);
          MethodSummary summary = targetSummary.instantiate(actual, target);
          CallContext context = new CallContext(target, site);
          summary = contexts.merge(context, summary, MethodSummary::join);
          returned = returned.join(summary.returns());
          importFlows(summary, null, site);
          summary
              .fieldWrites()
              .forEach(
                  (key, taint) -> {
                    heap.write(key, taint);
                    fieldWrites.merge(key, taint, Taint::join);
                  });
          List<AbstractValue> values = new ArrayList<>();
          if (n.getOpcode() != INVOKESTATIC) values.add(receiver);
          values.addAll(args);
          for (var effect : summary.elementWrites().entrySet())
            if (effect.getKey() < values.size()) {
              AbstractValue object = values.get(effect.getKey());
              Taint effectTaint = effect.getValue();
              heap.addElement(object, effectTaint);
              recordEffect(object, effectTaint);
            }
        }
      } else if (knownInert(id, n)) {
        returned = joined;
      } else {
        diagnostics.add("UNKNOWN_CALL " + site + " " + id);
        returned = InvocationEffects.opaque(heap, operands, this::recordEffect);
        opaque = true;
      }
    }
    Type ret = Type.getReturnType(n.desc);
    if (ret.getSort() != Type.VOID)
      f.push(
          InvocationEffects.returned(
              heap, ret, returned, resultRefs, operands, opaque, method.scope, site));
  }

  private void recordEffect(AbstractValue object, Taint taint) {
    for (String reference : heap.references(object.references())) {
      int parameter = -1;
      if (reference.equals("this:" + method.scope)) parameter = 0;
      else if (reference.startsWith("parameter:" + method.scope + ":")) {
        int slot = Integer.parseInt(reference.substring(reference.lastIndexOf(':') + 1));
        int cursor = (method.node.access & ACC_STATIC) == 0 ? 1 : 0;
        parameter = (method.node.access & ACC_STATIC) == 0 ? 1 : 0;
        for (Type type : Type.getArgumentTypes(method.node.desc)) {
          if (cursor == slot) break;
          cursor += type.getSize();
          parameter++;
        }
      }
      if (parameter >= 0) elementWrites.merge(parameter, taint, Taint::join);
    }
  }

  private boolean shell(AbstractValue value) {
    boolean command = false, flag = false;
    for (String ref : value.references()) {
      command |=
          !heap.read(ref + "#const:/bin/sh").equals(Taint.BOTTOM)
              || !heap.read(ref + "#const:sh").equals(Taint.BOTTOM)
              || !heap.read(ref + "#const:/bin/bash").equals(Taint.BOTTOM);
      flag |= !heap.read(ref + "#const:-c").equals(Taint.BOTTOM);
    }
    return command && flag;
  }

  private boolean knownInert(String id, MethodInsnNode n) {
    return n.name.equals("<init>") && n.owner.startsWith("java/")
        || n.owner.equals("java/lang/Runtime") && n.name.equals("getRuntime")
        || n.owner.equals("java/lang/Math")
        || n.owner.equals("java/lang/Object")
        || n.owner.equals("java/sql/PreparedStatement") && n.name.startsWith("set")
        || n.owner.equals("java/sql/Statement")
        || n.owner.equals("java/sql/Connection")
        || n.owner.equals("java/nio/file/Files")
        || n.owner.equals("java/lang/ProcessBuilder")
        || n.owner.equals("java/lang/Runtime");
  }

  private void importFlows(MethodSummary summary, List<Taint> actual, InstructionId site) {
    for (MethodSummary.Flow old : summary.flows().values()) {
      ArrayList<String> path = new ArrayList<>();
      path.add(site.toString());
      path.addAll(old.callPath());
      boolean truncated = old.truncated() || path.size() > budget.provenanceDepth;
      if (path.size() > budget.provenanceDepth)
        path = new ArrayList<>(path.subList(0, budget.provenanceDepth));
      flow(
          old.rule(),
          old.mask(),
          old.sink(),
          old.descriptor(),
          actual == null ? old.taint() : old.taint().substitute(actual),
          path,
          truncated);
    }
  }

  private void flow(
      String rule,
      int mask,
      InstructionId sink,
      String descriptor,
      Taint taint,
      List<String> path,
      boolean truncated) {
    if (taint.labels().values().stream().noneMatch(v -> (v & mask) != 0) && !taint.unknown())
      return;
    String key = rule + "|" + sink;
    MethodSummary.Flow next =
        new MethodSummary.Flow(rule, mask, sink, descriptor, taint, path, truncated);
    MethodSummary.Flow old = flows.get(key);
    if (old == null) flows.put(key, next);
    else
      flows.put(
          key,
          new MethodSummary.Flow(
              rule,
              mask,
              sink,
              descriptor,
              old.taint().join(taint),
              old.callPath(),
              old.truncated() || truncated));
  }
}
