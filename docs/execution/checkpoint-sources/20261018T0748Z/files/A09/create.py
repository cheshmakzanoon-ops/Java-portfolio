from pathlib import Path
root=Path('/workspace/Java-portfolio/projects/09-byteguard')
pkg='io.github.cheshmakzanoon.portfolio.byteguard'
def write(p,s):
 q=root/p;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(s)
def java(path,s): write('analysis-core/src/main/java/'+pkg.replace('.','/')+'/'+path+'.java','package '+pkg+('.'+path.rsplit('/',1)[0].replace('/','.') if '/' in path else '')+';\n'+s)
parent='<parent><groupId>io.github.cheshmakzanoon.portfolio</groupId><artifactId>byteguard</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>'
write('pom.xml','''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>io.github.cheshmakzanoon.portfolio</groupId><artifactId>java-portfolio</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../../pom.xml</relativePath></parent><artifactId>byteguard</artifactId><packaging>pom</packaging><modules><module>analysis-core</module><module>report</module><module>cli</module><module>evaluation</module></modules><profiles><profile><id>mutation</id><build><plugins><plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><executions><execution><id>default</id><phase>none</phase></execution></executions></plugin></plugins></build></profile></profiles></project>''')
def dep(a,g='io.github.cheshmakzanoon.portfolio',scope=None,version=None): return '<dependency><groupId>'+g+'</groupId><artifactId>'+a+'</artifactId>'+('<version>'+version+'</version>' if version else ('<version>${project.version}</version>' if g=='io.github.cheshmakzanoon.portfolio' else ''))+('<scope>'+scope+'</scope>' if scope else '')+'</dependency>'
test=dep('junit-jupiter','org.junit.jupiter','test')+dep('jqwik','net.jqwik','test')
for d,deps in [('analysis-core',dep('asm','org.ow2.asm')+dep('asm-tree','org.ow2.asm')+test),('report',dep('byteguard-analysis-core')+dep('jackson-databind','com.fasterxml.jackson.core')+test),('cli',dep('byteguard-report')+test),('evaluation',dep('byteguard-cli')+test)]:
 extra=''
 if d=='cli': extra='''<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><executions><execution><phase>package</phase><goals><goal>shade</goal></goals><configuration><createDependencyReducedPom>false</createDependencyReducedPom><transformers><transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"><mainClass>io.github.cheshmakzanoon.portfolio.byteguard.cli.ByteGuard</mainClass></transformer></transformers></configuration></execution></executions></plugin></plugins></build>'''
 write(d+'/pom.xml','<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'+parent+'<artifactId>byteguard-'+d+'</artifactId><dependencies>'+deps+'</dependencies>'+extra+'</project>')
java('input/InputManifest','''import java.util.*;
public record InputManifest(SortedMap<String, Entry> classes, List<String> diagnostics, long expandedBytes) {
 public record Entry(String logicalName, byte[] bytes, String sha256, boolean application) {}
 public InputManifest { classes=Collections.unmodifiableSortedMap(new TreeMap<>(classes));diagnostics=List.copyOf(diagnostics); }
}''')
java('input/InputLimits','''public record InputLimits(int entries,long expandedBytes,int classBytes,int expansionRatio,long deadlineMillis) {
 public static InputLimits defaults(){return new InputLimits(25_000,200L*1024*1024,8*1024*1024,100,120_000);}
 public InputLimits {if(entries<1||expandedBytes<1||classBytes<1||expansionRatio<1||deadlineMillis<1)throw new IllegalArgumentException("positive input limits required");}
}''')
java('input/InputFailure','''public final class InputFailure extends java.io.IOException { public InputFailure(String message){super(message);} }''')
java('input/BoundedJarReader','''import java.io.*;import java.nio.file.*;import java.security.*;import java.util.*;import java.util.jar.*;import java.util.zip.*;
/** Streams owned bytes. No extraction, target classloading or network resolution. */
public final class BoundedJarReader {
 private final InputLimits limits; private final int release;private int entries;private long expanded;private final long started=System.nanoTime();
 public BoundedJarReader(InputLimits limits,int release){this.limits=limits;this.release=release;if(release<8||release>25)throw new IllegalArgumentException("target release must be 8..25");}
 public InputManifest read(List<Path> inputs,List<Path> classpath)throws IOException {
  TreeMap<String,InputManifest.Entry> result=new TreeMap<>();List<String> diagnostics=new ArrayList<>();
  for(Path p:inputs)readPath(p,true,result,diagnostics);for(Path p:classpath)readPath(p,false,result,diagnostics);
  return new InputManifest(result,diagnostics,expanded);
 }
 private void check()throws InputFailure {if((System.nanoTime()-started)/1_000_000>limits.deadlineMillis())throw new InputFailure("input deadline exceeded");}
 private void readPath(Path p,boolean application,Map<String,InputManifest.Entry> out,List<String> diagnostics)throws IOException {
  check();if(Files.isSymbolicLink(p))throw new InputFailure("symbolic input rejected: "+p.getFileName());if(!Files.exists(p))throw new InputFailure("missing explicit input: "+p.getFileName());
  if(Files.isDirectory(p)){try(var paths=Files.walk(p)){for(Path f:paths.sorted().toList()){check();if(Files.isSymbolicLink(f))throw new InputFailure("symbolic entry rejected");if(Files.isRegularFile(f)){if(f.toString().endsWith(".class")){count();try(InputStream in=Files.newInputStream(f)){put(p.relativize(f).toString().replace('\\\\','/'),bounded(in,limits.classBytes()),application,out);}}else if(f.toString().endsWith(".jar"))throw new InputFailure("nested archive not supported in class directory");}}}return;}
  if(p.toString().endsWith(".class")){count();try(InputStream in=Files.newInputStream(p)){byte[] bytes=bounded(in,limits.classBytes());put(new org.objectweb.asm.ClassReader(bytes).getClassName()+".class",bytes,application,out);}catch(IllegalArgumentException|ArrayIndexOutOfBoundsException e){throw new InputFailure("malformed classfile");}return;}
  if(!p.toString().endsWith(".jar")&&!p.toString().endsWith(".zip"))throw new InputFailure("expected class, directory, JAR or ZIP");
  Map<String,byte[]> raw=new TreeMap<>();Set<String> names=new HashSet<>();long archiveStart=expanded;
  try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(p))){ZipEntry e;while((e=zip.getNextEntry())!=null){check();count();String n=e.getName();validateName(n);if(!names.add(n))throw new InputFailure("duplicate archive entry: "+n);if(e.isDirectory())continue;if(n.endsWith(".jar")||n.endsWith(".zip"))throw new InputFailure("nested archives forbidden: "+n);int max=n.endsWith(".class")?limits.classBytes():limits.classBytes();byte[] bytes=bounded(zip,max);long compressed=e.getCompressedSize();if(compressed>0&&bytes.length>compressed*(long)limits.expansionRatio())throw new InputFailure("expansion ratio exceeded: "+n);if(n.endsWith(".class")||n.equalsIgnoreCase("META-INF/MANIFEST.MF"))raw.put(n,bytes);}}
  catch(ZipException e){throw new InputFailure("corrupt archive: "+e.getMessage());}
  if(expanded-archiveStart>Math.max(1,Files.size(p))*(long)limits.expansionRatio())throw new InputFailure("archive expansion ratio exceeded");
  boolean multi=false;byte[] manifest=raw.get("META-INF/MANIFEST.MF");if(manifest!=null)multi="true".equalsIgnoreCase(new Manifest(new ByteArrayInputStream(manifest)).getMainAttributes().getValue("Multi-Release"));
  Map<String,Integer> versions=new HashMap<>();Map<String,byte[]> selected=new TreeMap<>();
  for(var e:raw.entrySet()){String n=e.getKey();if(!n.endsWith(".class"))continue;int v=0;if(n.startsWith("META-INF/versions/")){if(!multi)continue;String tail=n.substring(18);int slash=tail.indexOf('/');if(slash<1)throw new InputFailure("invalid multi-release entry");try{v=Integer.parseInt(tail.substring(0,slash));}catch(NumberFormatException ex){throw new InputFailure("invalid multi-release version");}if(v<9||v>release)continue;n=tail.substring(slash+1);}if(v>=versions.getOrDefault(n,-1)){versions.put(n,v);selected.put(n,e.getValue());}}
  for(var e:selected.entrySet())put(e.getKey(),e.getValue(),application,out);
 }
 private void count()throws InputFailure{if(++entries>limits.entries())throw new InputFailure("entry count exceeded");}
 private byte[] bounded(InputStream in,int max)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){check();expanded+=n;if(expanded>limits.expandedBytes()||out.size()+n>max)throw new InputFailure("expanded byte limit exceeded");out.write(b,0,n);}return out.toByteArray();}
 private void put(String n,byte[] bytes,boolean app,Map<String,InputManifest.Entry> out)throws InputFailure {
  if(bytes.length<8||readInt(bytes,0)!=0xcafebabe)throw new InputFailure("malformed classfile: "+n);int major=((bytes[6]&255)<<8)|(bytes[7]&255);if(major<45||major>release+44)throw new InputFailure("unsupported class version "+major+": "+n);
  try{String actual=new org.objectweb.asm.ClassReader(bytes).getClassName()+".class";if(!n.equals(actual))throw new InputFailure("class logical name mismatch: "+n+" != "+actual);}catch(IllegalArgumentException|IndexOutOfBoundsException e){throw new InputFailure("malformed classfile: "+n);}
  if(out.containsKey(n))throw new InputFailure("duplicate logical class: "+n);out.put(n,new InputManifest.Entry(n,bytes,sha(bytes),app));
 }
 private static int readInt(byte[] b,int o){return(b[o]&255)<<24|(b[o+1]&255)<<16|(b[o+2]&255)<<8|(b[o+3]&255);}
 public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static void validateName(String n)throws InputFailure {if(n.isEmpty()||n.startsWith("/")||n.contains("\\\\")||n.indexOf(0)>=0||n.matches("^[A-Za-z]:.*")||Arrays.asList(n.split("/",-1)).contains("..")||Arrays.asList(n.split("/",-1)).contains("."))throw new InputFailure("unsafe archive path: "+n);}
}''')
java('bytecode/InstructionId','''public record InstructionId(String method,int ordinal) implements Comparable<InstructionId>{public String toString(){return method+"@"+ordinal;} public int compareTo(InstructionId other){int c=method.compareTo(other.method);return c==0?Integer.compare(ordinal,other.ordinal):c;}}''')
java('bytecode/MethodIr','''import org.objectweb.asm.tree.*;import java.util.*;
public final class MethodIr {
 public final String owner,id,sourceFile;public final MethodNode node;public final List<AbstractInsnNode> instructions;public final Map<AbstractInsnNode,Integer> ordinals;private final int[] lines;
 public MethodIr(String owner,String sourceFile,MethodNode node){this.owner=owner;this.sourceFile=sourceFile;this.node=node;id=owner+"."+node.name+node.desc;List<AbstractInsnNode> ins=new ArrayList<>();IdentityHashMap<AbstractInsnNode,Integer> map=new IdentityHashMap<>();List<Integer> ls=new ArrayList<>();int line=-1;for(AbstractInsnNode n:node.instructions){if(n instanceof LineNumberNode l)line=l.line;if(n.getOpcode()>=0){map.put(n,ins.size());ins.add(n);ls.add(line);}}instructions=List.copyOf(ins);ordinals=Map.copyOf(map);lines=ls.stream().mapToInt(Integer::intValue).toArray();}
 public int target(AbstractInsnNode label){AbstractInsnNode n=label;while(n!=null&&n.getOpcode()<0)n=n.getNext();if(n==null)return instructions.size();return ordinals.get(n);}
 public InstructionId at(int ordinal){return new InstructionId(id,ordinal);}public int line(int ordinal){return ordinal>=0&&ordinal<lines.length?lines[ordinal]:-1;}
}''')
java('bytecode/ClassFactsReader','''import io.github.cheshmakzanoon.portfolio.byteguard.input.*;import java.util.*;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
public final class ClassFactsReader {
 public record ClassFacts(String name,String superName,List<String> interfaces,int access,boolean application,SortedMap<String,MethodIr> methods){}
 public SortedMap<String,ClassFacts> read(InputManifest manifest)throws InputFailure{TreeMap<String,ClassFacts> classes=new TreeMap<>();for(var entry:manifest.classes().values()){try{ClassNode node=new ClassNode();new ClassReader(entry.bytes()).accept(node,ClassReader.SKIP_FRAMES);TreeMap<String,MethodIr> methods=new TreeMap<>();for(MethodNode m:node.methods){MethodIr ir=new MethodIr(node.name,node.sourceFile,m);methods.put(ir.id,ir);}classes.put(node.name,new ClassFacts(node.name,node.superName,List.copyOf(node.interfaces),node.access,entry.application(),methods));}catch(RuntimeException e){throw new InputFailure("malformed class structure: "+entry.logicalName()+": "+e.getClass().getSimpleName());}}return classes;}
}''')
java('bytecode/TypeHierarchy','''import java.util.*;
public final class TypeHierarchy {
 private final Map<String,ClassFactsReader.ClassFacts> classes;public TypeHierarchy(Map<String,ClassFactsReader.ClassFacts> classes){this.classes=Map.copyOf(classes);}
 public boolean subtype(String type,String parent){return subtype(type,parent,new HashSet<>());}private boolean subtype(String type,String parent,Set<String> seen){if(type.equals(parent)||parent.equals("java/lang/Object"))return true;if(!seen.add(type))return false;var c=classes.get(type);if(c==null)return false;if(c.superName()!=null&&subtype(c.superName(),parent,seen))return true;for(String i:c.interfaces())if(subtype(i,parent,seen))return true;return false;}
 public List<String> targets(String owner,String name,String desc,boolean dynamic){TreeSet<String> ids=new TreeSet<>();for(var c:classes.values())if((dynamic&&subtype(c.name(),owner))||c.name().equals(owner)){String id=c.name()+"."+name+desc;if(c.methods().containsKey(id))ids.add(id);}if(ids.isEmpty()){var c=classes.get(owner);Set<String> seen=new HashSet<>();while(c!=null&&seen.add(c.name())){String id=c.name()+"."+name+desc;if(c.methods().containsKey(id)){ids.add(id);break;}c=classes.get(c.superName());}}return List.copyOf(ids);}
}''')
java('cfg/BasicBlock','''import java.util.*; public record BasicBlock(int first,int last,List<Integer> successors) {public BasicBlock{successors=List.copyOf(successors);}}''')
java('cfg/ExceptionEdge','''public record ExceptionEdge(int from,int handler,String caughtType) {}''')
java('cfg/ControlFlowBuilder','''import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;import java.util.*;
public final class ControlFlowBuilder {
 public record Graph(List<List<Integer>> normal,List<List<Integer>> exceptional,List<ExceptionEdge> exceptionEdges,List<BasicBlock> blocks){public String dot(){StringBuilder s=new StringBuilder("digraph cfg {\\n");for(int i=0;i<normal.size();i++){s.append("n").append(i).append(";\\n");for(int t:normal.get(i))s.append("n").append(i).append(" -> n").append(t).append(";\\n");for(int t:exceptional.get(i))s.append("n").append(i).append(" -> n").append(t).append(" [style=dashed];\\n");}return s.append("}\\n").toString();}}
 public Graph build(MethodIr m){int count=m.instructions.size();List<List<Integer>> normal=new ArrayList<>(),exceptional=new ArrayList<>();List<ExceptionEdge> edges=new ArrayList<>();for(int i=0;i<count;i++){TreeSet<Integer> next=new TreeSet<>();AbstractInsnNode n=m.instructions.get(i);int op=n.getOpcode();if(n instanceof JumpInsnNode j){next.add(m.target(j.label));if(op!=Opcodes.GOTO&&op!=Opcodes.JSR&&i+1<count)next.add(i+1);}else if(n instanceof TableSwitchInsnNode t){next.add(m.target(t.dflt));for(LabelNode l:t.labels)next.add(m.target(l));}else if(n instanceof LookupSwitchInsnNode l){next.add(m.target(l.dflt));for(LabelNode t:l.labels)next.add(m.target(t));}else if(!(op>=Opcodes.IRETURN&&op<=Opcodes.RETURN)&&op!=Opcodes.ATHROW&&op!=Opcodes.RET&&i+1<count)next.add(i+1);for(int t:next)if(t<0||t>=count)throw new IllegalArgumentException("invalid branch target "+m.id);normal.add(List.copyOf(next));TreeSet<Integer> handlers=new TreeSet<>();for(TryCatchBlockNode b:m.node.tryCatchBlocks){if(i>=m.target(b.start)&&i<m.target(b.end)&&mayThrow(op)){int h=m.target(b.handler);if(h>=count)throw new IllegalArgumentException("invalid handler");handlers.add(h);edges.add(new ExceptionEdge(i,h,b.type));}}exceptional.add(List.copyOf(handlers));}
 TreeSet<Integer> leaders=new TreeSet<>();if(count>0)leaders.add(0);for(int i=0;i<count;i++){if(normal.get(i).size()!=1||(!normal.get(i).isEmpty()&&normal.get(i).getFirst()!=i+1)){leaders.addAll(normal.get(i));if(i+1<count)leaders.add(i+1);}leaders.addAll(exceptional.get(i));}List<Integer> starts=new ArrayList<>(leaders);List<BasicBlock> blocks=new ArrayList<>();for(int i=0;i<starts.size();i++){int end=i+1<starts.size()?starts.get(i+1)-1:count-1;blocks.add(new BasicBlock(starts.get(i),end,normal.get(end)));}return new Graph(List.copyOf(normal),List.copyOf(exceptional),List.copyOf(edges),List.copyOf(blocks));}
 private boolean mayThrow(int op){return op==Opcodes.ATHROW||op==Opcodes.IDIV||op==Opcodes.LDIV||op==Opcodes.IREM||op==Opcodes.LREM||(op>=Opcodes.IALOAD&&op<=Opcodes.SALOAD)||(op>=Opcodes.IASTORE&&op<=Opcodes.SASTORE)||(op>=Opcodes.GETSTATIC&&op<=Opcodes.MONITOREXIT)||op==Opcodes.MULTIANEWARRAY;}
}''')
java('cfg/StronglyConnectedComponents','''import java.util.*;
public final class StronglyConnectedComponents {
 public <T extends Comparable<T>> List<List<T>> find(Map<T,? extends Collection<T>> graph){Map<T,Integer> index=new HashMap<>(),low=new HashMap<>();Deque<T> stack=new ArrayDeque<>();Set<T> on=new HashSet<>();List<List<T>> result=new ArrayList<>();for(T v:new TreeSet<>(graph.keySet()))if(!index.containsKey(v))visit(v,graph,index,low,stack,on,result);return List.copyOf(result);}
 private <T extends Comparable<T>> void visit(T v,Map<T,? extends Collection<T>> graph,Map<T,Integer> index,Map<T,Integer> low,Deque<T> stack,Set<T> on,List<List<T>> result){int n=index.size();index.put(v,n);low.put(v,n);stack.push(v);on.add(v);Collection<T> targets=graph.get(v);if(targets==null)targets=List.of();for(T w:new TreeSet<>(targets)){if(!index.containsKey(w)){visit(w,graph,index,low,stack,on,result);low.put(v,Math.min(low.get(v),low.get(w)));}else if(on.contains(w))low.put(v,Math.min(low.get(v),index.get(w)));}if(low.get(v).equals(index.get(v))){List<T> component=new ArrayList<>();T w;do{w=stack.pop();on.remove(w);component.add(w);}while(!w.equals(v));Collections.sort(component);result.add(List.copyOf(component));}}
}''')
java('dataflow/Taint','''import java.util.*;
/** Finite powerset lattice; masks SQL=1,SHELL=2,PATH=4. Unknown is independent of bottom. */
public record Taint(SortedMap<String,Integer> labels,boolean unknown) {
 public static final Taint BOTTOM=new Taint(new TreeMap<>(),false);public Taint{TreeMap<String,Integer> copy=new TreeMap<>();labels.forEach((k,v)->{if((v&7)!=0)copy.put(k,v&7);});labels=Collections.unmodifiableSortedMap(copy);}
 public static Taint source(String id){return new Taint(new TreeMap<>(Map.of(id,7)),false);}public static Taint unknownValue(){return new Taint(new TreeMap<>(),true);}
 public Taint join(Taint other){TreeMap<String,Integer> joined=new TreeMap<>(labels);other.labels.forEach((k,v)->joined.merge(k,v,(a,b)->a|b));return new Taint(joined,unknown||other.unknown);}
 public Taint sanitize(int context){TreeMap<String,Integer> clean=new TreeMap<>();labels.forEach((k,v)->clean.put(k,v&~context));return new Taint(clean,unknown);}
 public Taint substitute(List<Taint> arguments){Taint result=new Taint(new TreeMap<>(),unknown);for(var e:labels.entrySet()){if(e.getKey().startsWith("P:")){int i=Integer.parseInt(e.getKey().substring(2));if(i>=arguments.size()){result=result.join(unknownValue());continue;}TreeMap<String,Integer> mapped=new TreeMap<>();arguments.get(i).labels.forEach((k,v)->mapped.put(k,v&e.getValue()));result=result.join(new Taint(mapped,arguments.get(i).unknown));}else result=result.join(new Taint(new TreeMap<>(Map.of(e.getKey(),e.getValue())),false));}return result;}
 public boolean lessOrEqual(Taint other){return join(other).equals(other);}public boolean hasConcrete(int mask){return labels.entrySet().stream().anyMatch(e->!e.getKey().startsWith("P:")&&(e.getValue()&mask)!=0);}
}''')
java('dataflow/AbstractValue','''import java.util.*;import org.objectweb.asm.Type;
public record AbstractValue(Kind kind,Taint taint,SortedSet<String> references,String constant){
 public enum Kind{TOP,INT,FLOAT,LONG,DOUBLE,REF}public static final AbstractValue TOP=of(Kind.TOP,Taint.BOTTOM);
 public AbstractValue{references=Collections.unmodifiableSortedSet(new TreeSet<>(references));}
 public static AbstractValue of(Kind k,Taint t){return new AbstractValue(k,t,new TreeSet<>(),null);}public static AbstractValue reference(Taint t,String id){return new AbstractValue(Kind.REF,t,new TreeSet<>(Set.of(id)),null);}public static AbstractValue constant(Object value){return new AbstractValue(value instanceof Long?Kind.LONG:value instanceof Double?Kind.DOUBLE:value instanceof Float?Kind.FLOAT:value instanceof Integer?Kind.INT:Kind.REF,Taint.BOTTOM,new TreeSet<>(),String.valueOf(value));}
 public int size(){return kind==Kind.LONG||kind==Kind.DOUBLE?2:1;}public AbstractValue withTaint(Taint t){return new AbstractValue(kind,t,references,constant);}
 public AbstractValue join(AbstractValue other){if(equals(other))return this;if(kind==Kind.TOP||other.kind==Kind.TOP)return TOP;if(kind!=other.kind)throw new IllegalArgumentException("incompatible verifier merge: "+kind+" / "+other.kind);TreeSet<String> refs=new TreeSet<>(references);refs.addAll(other.references);return new AbstractValue(kind,taint.join(other.taint),refs,Objects.equals(constant,other.constant)?constant:null);}
 public static Kind kind(Type type){return switch(type.getSort()){case Type.VOID->Kind.TOP;case Type.BOOLEAN,Type.BYTE,Type.CHAR,Type.SHORT,Type.INT->Kind.INT;case Type.FLOAT->Kind.FLOAT;case Type.LONG->Kind.LONG;case Type.DOUBLE->Kind.DOUBLE;default->Kind.REF;};}
}''')
java('dataflow/FrameState','''import java.util.*;
public final class FrameState {
 public final AbstractValue[] locals;public final ArrayList<AbstractValue> stack;public FrameState(int n){locals=new AbstractValue[n];Arrays.fill(locals,AbstractValue.TOP);stack=new ArrayList<>();}private FrameState(AbstractValue[] l,ArrayList<AbstractValue> s){locals=l;stack=s;}
 public FrameState copy(){return new FrameState(locals.clone(),new ArrayList<>(stack));}public void push(AbstractValue v){if(v.kind()==AbstractValue.Kind.TOP)throw new IllegalArgumentException("TOP on stack");stack.add(v);}public AbstractValue pop(){if(stack.isEmpty())throw new IllegalArgumentException("stack underflow");return stack.removeLast();}public void store(int slot,AbstractValue value){if(slot<0||slot+value.size()>locals.length)throw new IllegalArgumentException("local out of bounds");if(slot>0&&locals[slot-1].size()==2)locals[slot-1]=AbstractValue.TOP;locals[slot]=value;if(value.size()==2)locals[slot+1]=AbstractValue.TOP;}
 public boolean merge(FrameState other){if(stack.size()!=other.stack.size()||locals.length!=other.locals.length)throw new IllegalArgumentException("incompatible verifier frame shape");boolean changed=false;for(int i=0;i<locals.length;i++){AbstractValue v=locals[i].join(other.locals[i]);if(!v.equals(locals[i])){locals[i]=v;changed=true;}}for(int i=0;i<stack.size();i++){AbstractValue v=stack.get(i).join(other.stack.get(i));if(!v.equals(stack.get(i))){stack.set(i,v);changed=true;}}return changed;}
 public Taint stackTaint(){Taint t=Taint.BOTTOM;for(AbstractValue v:stack)t=t.join(v.taint());return t;}public int slots(){return stack.stream().mapToInt(AbstractValue::size).sum();}
}''')
java('dataflow/HeapSummary','''import java.util.*;
/** Field-sensitive/object-insensitive; references distinguish allocation sites for collection elements. */
public final class HeapSummary {private final SortedMap<String,Taint> fields=new TreeMap<>();private boolean changed;
 public Taint read(String field){return fields.getOrDefault(field,Taint.BOTTOM);}public void write(String field,Taint value){Taint old=read(field),joined=old.join(value);if(!old.equals(joined)){fields.put(field,joined);changed=true;}}public Taint elements(AbstractValue object){Taint t=object.taint();for(String ref:object.references())t=t.join(read(ref+"[]"));return t;}public void addElement(AbstractValue object,Taint value){for(String ref:object.references())write(ref+"[]",value);}public boolean consumeChanged(){boolean c=changed;changed=false;return c;}public Map<String,Taint> snapshot(){return Collections.unmodifiableMap(fields);}}
''')
java('interprocedural/AnalysisBudget','''public final class AnalysisBudget {
 public static final class Exhausted extends RuntimeException{public Exhausted(String why){super(why);}}
 public final long maxWork,deadlineMillis;public final int dispatchTargets,provenanceDepth;private long work;private final long started=System.nanoTime();
 public AnalysisBudget(long maxWork,long deadlineMillis,int dispatchTargets,int provenanceDepth){if(maxWork<1||deadlineMillis<1||dispatchTargets<1||provenanceDepth<0)throw new IllegalArgumentException("invalid analysis budget");this.maxWork=maxWork;this.deadlineMillis=deadlineMillis;this.dispatchTargets=dispatchTargets;this.provenanceDepth=provenanceDepth;}
 public static AnalysisBudget defaults(){return new AnalysisBudget(50_000_000,120_000,32,5);}public void spend(){if(++work>maxWork)throw new Exhausted("global work budget exceeded");if((System.nanoTime()-started)/1_000_000>deadlineMillis)throw new Exhausted("analysis deadline exceeded");}public long work(){return work;}
}''')
java('interprocedural/MethodSummary','''import io.github.cheshmakzanoon.portfolio.byteguard.dataflow.*;import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;import java.util.*;
public record MethodSummary(Taint returns,SortedMap<String,Flow> flows){
 public record Flow(String rule,int mask,InstructionId sink,String descriptor,Taint taint,List<String> callPath,boolean truncated){public Flow{callPath=List.copyOf(callPath);}}
 public static MethodSummary empty(){return new MethodSummary(Taint.BOTTOM,new TreeMap<>());}public MethodSummary{flows=Collections.unmodifiableSortedMap(new TreeMap<>(flows));}
 public MethodSummary join(MethodSummary other){TreeMap<String,Flow> all=new TreeMap<>(flows);other.flows.forEach((k,v)->all.merge(k,v,(a,b)->new Flow(a.rule,a.mask,a.sink,a.descriptor,a.taint.join(b.taint),choose(a.callPath,b.callPath),a.truncated||b.truncated)));return new MethodSummary(returns.join(other.returns),all);}
 private static List<String> choose(List<String>a,List<String>b){return String.join("|",a).compareTo(String.join("|",b))<=0?a:b;}
}''')
java('rules/RuleModels','''import java.util.*;
/** Exact descriptors. The model is versioned; names alone never establish sanitization. */
public final class RuleModels {
 public static final String VERSION="1.0.0";public record Sink(String id,int mask,int argument){}
 public boolean source(String id){return Set.of("java/lang/System.getenv(Ljava/lang/String;)Ljava/lang/String;","java/lang/System.getProperty(Ljava/lang/String;)Ljava/lang/String;","java/io/BufferedReader.readLine()Ljava/lang/String;","java/util/Scanner.nextLine()Ljava/lang/String;","java/io/Console.readLine()Ljava/lang/String;").contains(id);}
 public Sink sink(String id){return switch(id){case "java/sql/Statement.execute(Ljava/lang/String;)Z","java/sql/Statement.executeQuery(Ljava/lang/String;)Ljava/sql/ResultSet;","java/sql/Statement.executeUpdate(Ljava/lang/String;)I","java/sql/Connection.prepareStatement(Ljava/lang/String;)Ljava/sql/PreparedStatement;"->new Sink("BG-SQL-TEXT",1,0);case "java/lang/Runtime.exec(Ljava/lang/String;)Ljava/lang/Process;"->new Sink("BG-SHELL-COMMAND",2,0);case "java/nio/file/Files.readString(Ljava/nio/file/Path;)Ljava/lang/String;","java/nio/file/Files.readAllBytes(Ljava/nio/file/Path;)[B","java/io/FileInputStream.<init>(Ljava/lang/String;)V"->new Sink("BG-PATH-ACCESS",4,0);default->null;};}
 public int sanitizer(String id){return switch(id){case "fixtures/Safe.sqlIdentifier(Ljava/lang/String;)Ljava/lang/String;"->1;case "fixtures/Safe.shellToken(Ljava/lang/String;)Ljava/lang/String;"->2;case "fixtures/Safe.confinedPath(Ljava/lang/String;)Ljava/lang/String;"->4;default->0;};}
 public Set<String> rules(){return Set.of("BG-SQL-TEXT","BG-SHELL-COMMAND","BG-PATH-ACCESS");}
}''')
