from pathlib import Path
import hashlib,json
r=Path('/workspace/Java-portfolio/projects/09-byteguard/corpus');p=r/'src/fixtures';p.mkdir(parents=True,exist_ok=True)
(p/'Safe.java').write_text('''package fixtures;
public final class Safe {
 public static String sqlIdentifier(String value){if(!value.matches("[a-zA-Z_][a-zA-Z_0-9]{0,63}"))throw new IllegalArgumentException();return value;}
 public static String shellToken(String value){if(!value.matches("[a-zA-Z0-9_]{1,64}"))throw new IllegalArgumentException();return value;}
 public static String confinedPath(String value){java.nio.file.Path root=java.nio.file.Path.of("/sandbox");java.nio.file.Path path=root.resolve(value).normalize();if(!path.startsWith(root)||java.nio.file.Path.of(value).isAbsolute())throw new IllegalArgumentException();return path.toString();}
 public static String pretendClean(String value){return value.trim();}
}
''')
(p/'Missing.java').write_text('package fixtures; public final class Missing { public static String identity(String s){return s;} }\n')
patterns=['direct','branch','loop','switch','overload','inheritance','recursion','exception','wide','concat','lambda','collection','field','array','builder','sanitizer-context','binding-command-normalize','missing-dependency','missing-debug','cast']
labels=[]
for family in ['SQL','SHELL','PATH']:
 for index,pattern in enumerate(patterns,1):
  for positive in [True,False]:
   name=f'{family}{index:02d}'+('Positive' if positive else 'Negative')
   sanitizer={'SQL':'sqlIdentifier','SHELL':'shellToken','PATH':'confinedPath'}[family]
   sink={'SQL':'java.sql.Statement statement=null;statement.execute(text);','SHELL':'Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});','PATH':'java.nio.file.Files.readString(java.nio.file.Path.of(text));'}[family]
   safe='fixtures.Safe.'+sanitizer+'(input)'
   # Negatives deliberately retain source computation, changing the actual data dependency.
   expr='input' if positive else safe
   extra='';body=f'String input=source();String text={expr};sink(text);'
   if pattern=='branch': body=f'String input=source();String text;if(System.nanoTime()>0)text={expr};else text="fixed";sink(text);'
   if pattern=='loop': body=f'String input=source();String text={expr};for(int i=0;i<3;i++){{text=text+"x";}}sink(text);'
   if pattern=='switch': body=f'String input=source();String text=switch((int)(System.nanoTime()%3)){{case 0 -> {expr};case 1 -> "fixed";default -> "other";}};sink(text);'
   if pattern=='overload': extra='static String route(String s){return s;}static String route(int n){return "fixed";}';body=f'String input=source();sink(route({expr}));'
   if pattern=='inheritance': extra='interface Pass {String pass(String s);}static class Identity implements Pass {public String pass(String s){return s;}}';body=f'String input=source();Pass pass=new Identity();sink(pass.pass({expr}));'
   if pattern=='recursion': extra='static String recursive(String s,int n){if(n<=0)return s;return recursive(s,n-1);}';body=f'String input=source();sink(recursive({expr},3));'
   if pattern=='exception': body=f'String input=source();String text={expr};try{{if(input.length()>2)throw new IllegalArgumentException();text=text+"suffix";}}catch(IllegalArgumentException e){{text=text+"fallback";}}sink(text);'
   if pattern=='wide': extra='static String wide(long x,double y,String s){long[] a={x};a[0]++;return s+x+y+a[0];}';body=f'String input=source();sink(wide(42L,2.5,{expr}));'
   if pattern=='concat': body=f'String input=source();sink("prefix-"+{expr}+"-suffix");'
   if pattern=='lambda': extra='static String identity(String s){return s;}';body=f'String input=source();java.util.function.Function<String,String> f={name}::identity;sink(f.apply({expr}));'
   if pattern=='collection': body=f'String input=source();java.util.List<String> list=new java.util.ArrayList<>();list.add({expr});sink(list.get(0));'
   if pattern=='field': extra='static String field;';body=f'String input=source();field={expr};sink(field);'
   if pattern=='array': body=f'String input=source();String[] values=new String[1];values[0]={expr};sink(values[0]);'
   if pattern=='builder': body=f'String input=source();StringBuilder builder=new StringBuilder("prefix");builder.append({expr});sink(builder.toString());'
   if pattern=='sanitizer-context':
    false={'SQL':'shellToken','SHELL':'confinedPath','PATH':'sqlIdentifier'}[family]
    body=f'String input=source();sink(Safe.{false if positive else sanitizer}(input));'
   if pattern=='binding-command-normalize':
    if family=='SQL': body='String input=source();java.sql.Connection conn=null;'+('conn.prepareStatement("select "+input);' if positive else 'java.sql.PreparedStatement p=conn.prepareStatement("select ?");p.setString(1,input);p.execute();')
    elif family=='SHELL': body='String input=source();Runtime.getRuntime().exec(new String[]{'+('"/bin/sh","-c",input' if positive else '"echo",input')+'});'
    else: body='String input=source();'+('sink(java.nio.file.Path.of(input).normalize().toString());' if positive else 'sink(Safe.confinedPath(input));')
   if pattern=='missing-dependency': body=f'String input=source();sink(Missing.identity({expr}));'
   if pattern=='missing-debug': body=f'String input=source();sink({expr});'
   if pattern=='cast': body=f'String input=source();Object text={expr};sink((String)text);'
   source=f'package fixtures;\npublic final class {name} {{\nstatic String source(){{return System.getenv("BYTEGUARD_INPUT");}}\nstatic void sink(String text)throws Exception{{{sink}}}\n{extra}\npublic static void run()throws Exception{{{body}}}\n}}\n'
   file=p/(name+'.java');file.write_text(source)
   rationale=('External source reaches exact '+family+' sink through '+pattern+'.' if positive else ('Tainted value is separately bound, not SQL text.' if pattern=='binding-command-normalize' and family=='SQL' else 'Argument-vector execution has no modeled shell -c construction.' if pattern=='binding-command-normalize' and family=='SHELL' else 'Matching '+sanitizer+' confines only the '+family+' context before '+pattern+'.'))
   labels.append({'id':name,'file':'src/fixtures/'+name+'.java','rule':{'SQL':'BG-SQL-TEXT','SHELL':'BG-SHELL-COMMAND','PATH':'BG-PATH-ACCESS'}[family],'positive':positive,'pattern':pattern,'rationale':rationale,'debug':pattern!='missing-debug','sha256':hashlib.sha256(source.encode()).hexdigest()})
(r/'labels/frozen-v1.json').write_text(json.dumps({'schemaVersion':1,'frozenBeforeEngineEvaluation':True,'labels':labels},indent=2)+'\n')
(r/'labels/SHA256SUMS').write_text(''.join(x['sha256']+'  '+x['file']+'\n' for x in labels))
