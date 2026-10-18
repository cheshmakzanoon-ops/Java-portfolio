from pathlib import Path
base=Path('projects/09-byteguard/analysis-core/src/main/java/io/github/cheshmakzanoon/portfolio/byteguard')
p=base/'bytecode/ClassFactsReader.java';s=p.read_text().replace('class Incomplete extends AnalysisBudget.Exhausted','class Incomplete extends RuntimeException');p.write_text(s)
p=base/'dataflow/HeapSummary.java';p.write_text('''package io.github.cheshmakzanoon.portfolio.byteguard.dataflow;

import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.AnalysisBudget;
import java.util.*;

/** Weak field-sensitive heap with bounded, flow-insensitive reference alias closure. */
public final class HeapSummary {
  private final SortedMap<String,Taint> fields = new TreeMap<>();
  private final Map<String,SortedSet<String>> aliases = new TreeMap<>();
  private final AnalysisBudget budget;
  private boolean changed;
  public HeapSummary() { this(AnalysisBudget.defaults()); }
  public HeapSummary(AnalysisBudget budget) { this.budget=budget; }
  public Taint read(String field) { return fields.getOrDefault(field,Taint.BOTTOM); }
  public void write(String field,Taint value) {
    Taint old=read(field), joined=old.join(value);
    if (!old.equals(joined)) { fields.put(field,joined); changed=true; }
  }
  public void alias(String reference, Collection<String> others) {
    for (String other:others) {
      budget.spend();
      if (reference.equals(other)) continue;
      if (aliases.computeIfAbsent(reference,k->new TreeSet<>()).add(other)) {
        aliases.computeIfAbsent(other,k->new TreeSet<>()).add(reference); changed=true;
      }
    }
  }
  public SortedSet<String> references(Collection<String> roots) {
    TreeSet<String> visited=new TreeSet<>(); ArrayDeque<String> todo=new ArrayDeque<>(roots);
    while(!todo.isEmpty()) {
      budget.spend(); String next=todo.removeFirst();
      if(!visited.add(next)) continue;
      if(visited.size()>4096) throw new AnalysisBudget.Exhausted("heap alias budget exceeded (4096 per value)");
      for(String adjacent:aliases.getOrDefault(next,Collections.emptySortedSet()))
        if(!visited.contains(adjacent)) todo.addLast(adjacent);
    }
    return visited;
  }
  public AbstractValue readField(String field,AbstractValue.Kind kind) {
    return new AbstractValue(kind,read(field),kind==AbstractValue.Kind.REF?new TreeSet<>(Set.of("field:"+field)):new TreeSet<>(),null);
  }
  public void writeField(String field,AbstractValue value) {
    write(field,elements(value));
    if(value.kind()==AbstractValue.Kind.REF) alias("field:"+field,value.references());
  }
  public Taint elements(AbstractValue object) {
    Taint t=object.taint();
    for(String ref:references(object.references())) t=t.join(read(ref+"[]"));
    return t;
  }
  public void addElement(AbstractValue object,Taint value) {
    for(String ref:object.references()) write(ref+"[]",value);
  }
  public boolean consumeChanged() { boolean c=changed;changed=false;return c; }
  public Map<String,Taint> snapshot() { return Collections.unmodifiableMap(fields); }
}
''')
p=base/'interprocedural/SummaryWorklist.java';s=p.read_text().replace('HeapSummary heap = new HeapSummary();','HeapSummary heap = new HeapSummary(budget);');p.write_text(s)
p=base/'interprocedural/MethodSummary.java';s=p.read_text();s=s.replace('Taint returns, SortedMap<String, Flow> flows, SortedMap<Integer, Taint> elementWrites)', 'Taint returns, SortedMap<String, Flow> flows, SortedMap<Integer, Taint> elementWrites, SortedMap<String,Taint> fieldWrites)');s=s.replace('this(returns, flows, new TreeMap<>());','this(returns, flows, new TreeMap<>(), new TreeMap<>());');s=s.replace('  public record Flow(','''  public MethodSummary(Taint returns, SortedMap<String,Flow> flows, SortedMap<Integer,Taint> elementWrites) {
    this(returns,flows,elementWrites,new TreeMap<>());
  }

  public record Flow(''');s=s.replace('elementWrites = Collections.unmodifiableSortedMap(new TreeMap<>(elementWrites));','elementWrites = Collections.unmodifiableSortedMap(new TreeMap<>(elementWrites));\n    fieldWrites = Collections.unmodifiableSortedMap(new TreeMap<>(fieldWrites));');s=s.replace('return new MethodSummary(returns.join(other.returns), all, effects);','TreeMap<String,Taint> fieldEffects=new TreeMap<>(fieldWrites);\n    other.fieldWrites.forEach((k,v)->fieldEffects.merge(k,v,Taint::join));\n    return new MethodSummary(returns.join(other.returns), all, effects, fieldEffects);');s=s.replace('return new MethodSummary(returns.substitute(arguments), instantiated, effects);','TreeMap<String,Taint> fieldEffects=new TreeMap<>();\n    fieldWrites.forEach((k,v)->fieldEffects.put(k,v.substitute(arguments)));\n    return new MethodSummary(returns.substitute(arguments), instantiated, effects, fieldEffects);');p.write_text(s)
p=base/'dataflow/TransferFunction.java';s=p.read_text().replace('private Taint returns = Taint.BOTTOM;','private Taint returns = Taint.BOTTOM;\n  private final SortedMap<String,Taint> fieldWrites=new TreeMap<>();');s=s.replace('new MethodSummary(returns, flows, elementWrites)','new MethodSummary(returns, flows, elementWrites, fieldWrites)');s=s.replace('case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN ->\n          returns = returns.join(heap.elements(f.pop()));','''case IRETURN, LRETURN, FRETURN, DRETURN, ARETURN -> {
        AbstractValue returnedValue=f.pop();
        returns=returns.join(heap.elements(returnedValue));
        if(op==ARETURN && mutableReference(Type.getReturnType(method.node.desc)))
          heap.alias("return:"+method.id,returnedValue.references());
      }''');s=s.replace('''f.push(value(kind, heap.elements(array).join(index)));''','''f.push(new AbstractValue(kind,heap.elements(array).join(index),kind==AbstractValue.Kind.REF?array.references():new TreeSet<>(),null));''');s=s.replace('''heap.addElement(array, heap.elements(v));''','''heap.addElement(array, heap.elements(v));
        if(op==AASTORE) for(String reference:array.references()) heap.alias(reference,v.references());''');s=s.replace('''f.push(
            value(
                AbstractValue.kind(Type.getType(field.desc)),
                heap.read(field.owner + "." + field.name + field.desc)));''','''f.push(heap.readField(field.owner+"."+field.name+field.desc, AbstractValue.kind(Type.getType(field.desc))));''');s=s.replace('''heap.write(field.owner + "." + field.name + field.desc, heap.elements(v));''','''String fieldKey=field.owner+"."+field.name+field.desc;
        heap.writeField(fieldKey,v);
        fieldWrites.merge(fieldKey,heap.elements(v),Taint::join);''');s=s.replace('''for (String target : targets) {
          MethodSummary summary =''','''for (String target : targets) {
          int localSlot=0;
          if(n.getOpcode()!=INVOKESTATIC) {
            if(mutableReference(Type.getObjectType(n.owner))) heap.alias("this:"+target,receiver.references());
            localSlot=1;
          }
          Type[] parameterTypes=Type.getArgumentTypes(n.desc);
          for(int argument=0;argument<parameterTypes.length;argument++) {
            if(mutableReference(parameterTypes[argument])) heap.alias("parameter:"+target+":"+localSlot,args.get(argument).references());
            localSlot+=parameterTypes[argument].getSize();
          }
          if(mutableReference(Type.getReturnType(n.desc))) resultRefs.add("return:"+target);
          MethodSummary summary =''');s=s.replace('''importFlows(summary, null, site);
          List<AbstractValue> values''','''importFlows(summary, null, site);
          summary.fieldWrites().forEach((key,taint)->{heap.write(key,taint);fieldWrites.merge(key,taint,Taint::join);});
          List<AbstractValue> values''');insert='''  private static boolean mutableReference(Type type) {
    if(type.getSort()==Type.ARRAY) return true;
    if(type.getSort()!=Type.OBJECT) return false;
    String name=type.getInternalName();
    return !name.equals("java/lang/String") && !name.equals("java/lang/Class")
      && !name.startsWith("java/lang/Integer") && !name.startsWith("java/lang/Long")
      && !name.startsWith("java/lang/Double") && !name.startsWith("java/lang/Float")
      && !name.startsWith("java/lang/Boolean") && !name.startsWith("java/lang/Character")
      && !name.startsWith("java/lang/Byte") && !name.startsWith("java/lang/Short")
      && !name.startsWith("java/sql/") && !name.equals("java/nio/file/Path");
  }

''';s=s.replace('  private void recordEffect(',insert+'  private void recordEffect(');p.write_text(s)
