from pathlib import Path
b=Path('projects/09-byteguard/analysis-core/src/main/java/io/github/cheshmakzanoon/portfolio/byteguard')
p=b/'interprocedural/AnalysisBudget.java';s=p.read_text().replace('  private long frameCells;','  private long frameCells;\n  private long facts;').replace('  public void reserveFrame(int cells)', '  public void reserveFact(){spend();if(++facts>2_000_000)throw new Exhausted("retained class-fact budget exceeded (2000000)");}\n  public void reserveFrame(int cells)');p.write_text(s)
header='''package io.github.cheshmakzanoon.portfolio.byteguard.bytecode;
import org.objectweb.asm.*;import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.AnalysisBudget;
/** Charges retained visitor facts before ASM tree allocation, including parser-stage work. */
final class BoundedFactsVisitor extends ClassVisitor {
 private final AnalysisBudget budget;
 BoundedFactsVisitor(ClassVisitor delegate,AnalysisBudget budget){super(Opcodes.ASM9,delegate);this.budget=budget;}
 @Override public void visit(int version,int access,String name,String signature,String superName,String[] interfaces){budget.reserveFact();super.visit(version,access,name,signature,superName,interfaces);}
 @Override public FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value){budget.reserveFact();return super.visitField(access,name,descriptor,signature,value);}
 @Override public AnnotationVisitor visitAnnotation(String descriptor,boolean visible){return null;}
 @Override public AnnotationVisitor visitTypeAnnotation(int typeRef,TypePath path,String descriptor,boolean visible){return null;}
 @Override public void visitAttribute(Attribute attribute){}
 @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){budget.reserveFact();MethodVisitor delegate=super.visitMethod(access,name,descriptor,signature,exceptions);return new MethodVisitor(Opcodes.ASM9,delegate){
 @Override public AnnotationVisitor visitAnnotation(String descriptor,boolean visible){return null;}
 @Override public AnnotationVisitor visitParameterAnnotation(int parameter,String descriptor,boolean visible){return null;}
 @Override public AnnotationVisitor visitAnnotationDefault(){return null;}
 @Override public AnnotationVisitor visitTypeAnnotation(int typeRef,TypePath path,String descriptor,boolean visible){return null;}
 @Override public AnnotationVisitor visitInsnAnnotation(int typeRef,TypePath path,String descriptor,boolean visible){return null;}
 @Override public AnnotationVisitor visitTryCatchAnnotation(int typeRef,TypePath path,String descriptor,boolean visible){return null;}
 @Override public AnnotationVisitor visitLocalVariableAnnotation(int typeRef,TypePath path,Label[] start,Label[] end,int[] index,String descriptor,boolean visible){return null;}
 @Override public void visitAttribute(Attribute attribute){}
'''
methods=[('Insn','int opcode','opcode'),('IntInsn','int opcode,int operand','opcode,operand'),('VarInsn','int opcode,int varIndex','opcode,varIndex'),('TypeInsn','int opcode,String type','opcode,type'),('FieldInsn','int opcode,String owner,String name,String descriptor','opcode,owner,name,descriptor'),('MethodInsn','int opcode,String owner,String name,String descriptor,boolean isInterface','opcode,owner,name,descriptor,isInterface'),('InvokeDynamicInsn','String name,String descriptor,Handle bootstrap,Object... arguments','name,descriptor,bootstrap,arguments'),('JumpInsn','int opcode,Label label','opcode,label'),('Label','Label label','label'),('LdcInsn','Object value','value'),('IincInsn','int variable,int increment','variable,increment'),('TableSwitchInsn','int min,int max,Label dflt,Label... labels','min,max,dflt,labels'),('LookupSwitchInsn','Label dflt,int[] keys,Label[] labels','dflt,keys,labels'),('MultiANewArrayInsn','String descriptor,int dimensions','descriptor,dimensions'),('TryCatchBlock','Label start,Label end,Label handler,String type','start,end,handler,type'),('LocalVariable','String name,String descriptor,String signature,Label start,Label end,int index','name,descriptor,signature,start,end,index'),('LineNumber','int line,Label start','line,start')]
for name,args,values in methods:header+=' @Override public void visit'+name+'('+args+'){budget.reserveFact();super.visit'+name+'('+values+');}\n'
header+=' };}\n}\n';(b/'bytecode/BoundedFactsVisitor.java').write_text(header)
p=b/'bytecode/ClassFactsReader.java';s=p.read_text().replace('import org.objectweb.asm.*;','import org.objectweb.asm.*;import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.*;');s=s.replace('  public SortedMap<String, ClassFacts> read(InputManifest manifest) throws InputFailure {','''  public static final class Incomplete extends AnalysisBudget.Exhausted {public final SortedMap<String,ClassFacts> completed;public final List<String> unfinished;Incomplete(String message,SortedMap<String,ClassFacts> completed,List<String> unfinished){super(message);this.completed=new TreeMap<>(completed);this.unfinished=List.copyOf(unfinished);}}
  public SortedMap<String,ClassFacts> read(InputManifest manifest)throws InputFailure{return read(manifest,AnalysisBudget.defaults());}
  public SortedMap<String, ClassFacts> read(InputManifest manifest,AnalysisBudget budget) throws InputFailure {''')
s=s.replace('new ClassReader(entry.bytes()).accept(node, ClassReader.SKIP_FRAMES);','new ClassReader(entry.bytes()).accept(new BoundedFactsVisitor(node,budget), ClassReader.SKIP_FRAMES);')
s=s.replace('      } catch (RuntimeException e) {','''      }catch(AnalysisBudget.Exhausted exhausted){List<String> frontier=new ArrayList<>();boolean unfinished=false;for(String name:manifest.classes().keySet()){if(name.equals(entry.logicalName()))unfinished=true;if(unfinished)frontier.add("parser:"+name);}throw new Incomplete(exhausted.getMessage(),classes,frontier);
      }catch(StackOverflowError tooDeep){throw new InputFailure("class structure nesting exceeds parser capacity: "+entry.logicalName());
      } catch (RuntimeException e) {''');p.write_text(s)
p=b/'interprocedural/Analyzer.java';s=p.read_text().replace('    var classes = new ClassFactsReader().read(manifest);','''    SortedMap<String,ClassFactsReader.ClassFacts> classes;
    try{classes=new ClassFactsReader().read(manifest,budget);}catch(ClassFactsReader.Incomplete incomplete){return new Result(manifest,incomplete.completed,new SummaryWorklist.Result(new TreeMap<>(),new TreeSet<>(Set.of("RESOURCE_LIMIT "+incomplete.getMessage())),true,incomplete.unfinished,budget.work(),Map.of(),List.of(),new TreeMap<>()));}''');p.write_text(s)
