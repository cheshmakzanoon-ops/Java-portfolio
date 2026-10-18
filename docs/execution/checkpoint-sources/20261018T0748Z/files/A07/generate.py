from pathlib import Path
root=Path('/workspace/Java-portfolio/projects/07-indexforge')
ns='io.github.cheshmakzanoon.portfolio.indexforge'
def write(p,s):
 p=root/p;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
def java(module,name,s,test=False): write(Path(module)/('src/test/java' if test else 'src/main/java')/Path(ns.replace('.','/'))/(name+'.java'),'package '+ns+';\n'+s)
def pom(module,deps='',build=''):
 write(Path(module)/'pom.xml',f'''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>io.github.cheshmakzanoon.portfolio</groupId><artifactId>indexforge</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent><artifactId>indexforge-{module}</artifactId><dependencies>{deps}<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency></dependencies>{build}</project>''')
def dep(m):return f'<dependency><groupId>io.github.cheshmakzanoon.portfolio</groupId><artifactId>indexforge-{m}</artifactId><version>${{project.version}}</version></dependency>'
write(Path('pom.xml'),'''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>io.github.cheshmakzanoon.portfolio</groupId><artifactId>java-portfolio</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../../pom.xml</relativePath></parent><artifactId>indexforge</artifactId><packaging>pom</packaging><modules><module>ingest</module><module>index</module><module>query</module><module>service</module><module>evaluation</module><module>cli</module></modules><profiles><profile><id>mutation</id><build><plugins><plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><executions><execution><id>default</id><phase>none</phase></execution></executions></plugin></plugins></build></profile></profiles></project>''')
pom('ingest')
pom('index',dep('ingest')+''.join('<dependency><groupId>org.apache.lucene</groupId><artifactId>'+m+'</artifactId></dependency>' for m in ['lucene-core','lucene-analysis-common']))
pom('query',dep('index'))
pom('service',dep('query')+''.join('<dependency><groupId>org.springframework.boot</groupId><artifactId>'+m+'</artifactId></dependency>' for m in ['spring-boot-starter-web','spring-boot-starter-oauth2-resource-server'])+'<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>','''<build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId><executions><execution><goals><goal>repackage</goal></goals></execution></executions></plugin></plugins></build>''')
pom('evaluation',dep('query'))
pom('cli',dep('query')+dep('evaluation'),'''<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><executions><execution><phase>package</phase><goals><goal>shade</goal></goals><configuration><createDependencyReducedPom>false</createDependencyReducedPom><transformers><transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"><mainClass>io.github.cheshmakzanoon.portfolio.indexforge.IndexForgeCli</mainClass></transformer></transformers></configuration></execution></executions></plugin></plugins></build>''')
java('ingest','Digests','''import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
public final class Digests {
 private Digests() {}
 public static String sha256(byte[] data) {try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 public static String sha256(String text){return sha256(text.getBytes(StandardCharsets.UTF_8));}
}
''')
java('ingest','SnapshotManifest','''import java.io.*;
import java.nio.file.*;
import java.util.*;
public record SnapshotManifest(String tenantId,String repositoryId,String revision,boolean complete,List<Entry> entries,Set<String> deletions) {
 public static final int SCHEMA=1;
 public record Entry(String path,String sha256,String language,String kind,List<String> symbols,String coordinates,String version) {
 public Entry {path=normalize(path); if(!sha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid digest"); symbols=List.copyOf(symbols); Objects.requireNonNull(language);Objects.requireNonNull(kind);Objects.requireNonNull(coordinates);Objects.requireNonNull(version);}
 }
 public SnapshotManifest {if(!tenantId.matches("[a-zA-Z0-9_-]{1,64}")||!repositoryId.matches("[a-zA-Z0-9_-]{1,64}")||revision.length()>128)throw new IllegalArgumentException("invalid identity"); entries=List.copyOf(entries); TreeSet<String> ds=new TreeSet<>();deletions.forEach(p->ds.add(normalize(p)));deletions=Collections.unmodifiableSet(ds);Set<String> seen=new HashSet<>();for(Entry e:entries)if(!seen.add(e.path())||deletions.contains(e.path()))throw new IllegalArgumentException("duplicate or deleted entry");if(entries.size()>100_000)throw new IllegalArgumentException("manifest quota");}
 public static String normalize(String path){if(path==null||path.isBlank()||path.length()>1024||path.indexOf('\\\\')>=0||path.indexOf('\\0')>=0||path.startsWith("/")||path.matches("^[A-Za-z]:.*"))throw new IllegalArgumentException("unsafe relative path");Path p=Path.of(path);if(p.isAbsolute())throw new IllegalArgumentException("absolute path");for(Path part:p)if(part.toString().equals("..")||part.toString().equals("."))throw new IllegalArgumentException("path escape");String n=p.normalize().toString().replace('\\\\','/');if(!n.equals(path))throw new IllegalArgumentException("noncanonical path");return n;}
 public byte[] bytes(){try{ByteArrayOutputStream b=new ByteArrayOutputStream();try(DataOutputStream o=new DataOutputStream(b)){o.writeInt(SCHEMA);o.writeUTF(tenantId);o.writeUTF(repositoryId);o.writeUTF(revision);o.writeBoolean(complete);var sorted=entries.stream().sorted(Comparator.comparing(Entry::path)).toList();o.writeInt(sorted.size());for(Entry e:sorted){o.writeUTF(e.path);o.writeUTF(e.sha256);o.writeUTF(e.language);o.writeUTF(e.kind);o.writeInt(e.symbols.size());for(String s:e.symbols)o.writeUTF(s);o.writeUTF(e.coordinates);o.writeUTF(e.version);}o.writeInt(deletions.size());for(String d:new TreeSet<>(deletions))o.writeUTF(d);}return b.toByteArray();}catch(IOException e){throw new UncheckedIOException(e);}}
 public String digest(){return Digests.sha256(bytes());}
 public static SnapshotManifest read(byte[] bytes)throws IOException{if(bytes.length>64*1024*1024)throw new IOException("manifest too large");try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=SCHEMA)throw new IOException("schema mismatch");String t=in.readUTF(),r=in.readUTF(),v=in.readUTF();boolean full=in.readBoolean();int n=bounded(in.readInt(),100_000);List<Entry> es=new ArrayList<>();for(int i=0;i<n;i++){String p=in.readUTF(),h=in.readUTF(),l=in.readUTF(),k=in.readUTF();int sc=bounded(in.readInt(),256);List<String> sy=new ArrayList<>();for(int j=0;j<sc;j++)sy.add(in.readUTF());es.add(new Entry(p,h,l,k,sy,in.readUTF(),in.readUTF()));}int dc=bounded(in.readInt(),100_000);Set<String> ds=new HashSet<>();for(int i=0;i<dc;i++)if(!ds.add(in.readUTF()))throw new IOException("duplicate deletion");if(in.read()!=-1)throw new IOException("trailing metadata");return new SnapshotManifest(t,r,v,full,es,ds);}catch(IllegalArgumentException|EOFException e){throw new IOException("corrupt manifest",e);}}
 private static int bounded(int value,int max)throws IOException{if(value<0||value>max)throw new IOException("metadata quota");return value;}
}
''')
java('ingest','DurableFiles','''import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
public final class DurableFiles {
 private DurableFiles(){}
 public static void atomicWrite(Path path,byte[] bytes)throws IOException {Files.createDirectories(path.getParent());Path tmp=path.resolveSibling(path.getFileName()+".tmp-"+UUID.randomUUID());try{try(FileChannel c=FileChannel.open(tmp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(path.getParent());}finally{Files.deleteIfExists(tmp);}}
 public static void syncDirectory(Path p)throws IOException{try(FileChannel c=FileChannel.open(p,StandardOpenOption.READ)){c.force(true);}}
}
''')
java('ingest','ContentAddressedStore','''import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
public final class ContentAddressedStore {
 private final Path root;
 public ContentAddressedStore(Path root)throws IOException{this.root=root;Files.createDirectories(root);}
 public String put(byte[] content)throws IOException {String h=Digests.sha256(content);Path p=root.resolve(h);if(Files.exists(p)){read(h);return h;}DurableFiles.atomicWrite(p,content);return h;}
 public byte[] read(String digest)throws IOException {if(!digest.matches("[a-f0-9]{64}"))throw new IOException("invalid digest");Path p=root.resolve(digest);if(Files.size(p)>1_048_576)throw new IOException("content too large");byte[] b=Files.readAllBytes(p);if(!Digests.sha256(b).equals(digest))throw new IOException("content digest mismatch");return b;}
 public String text(String digest)throws IOException {try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(read(digest))).toString();}catch(CharacterCodingException e){throw new IOException("invalid UTF-8",e);}}
}
''')
java('ingest','SafeSourceReader','''import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
public final class SafeSourceReader {
 public record Limits(long fileBytes,long totalBytes,int entries,int expansionRatio){public Limits{if(fileBytes<1||fileBytes>1_048_576||totalBytes<1||entries<1||expansionRatio<1)throw new IllegalArgumentException("limits");}public static Limits defaults(){return new Limits(1_048_576,256L*1024*1024,100_000,100);}}
 private final Path root;private final Limits limits;private final ContentAddressedStore store;
 public SafeSourceReader(Path configuredRoot,Limits limits,ContentAddressedStore store)throws IOException{root=configuredRoot.toRealPath();if(!Files.isDirectory(root))throw new IOException("root is not directory");this.limits=limits;this.store=store;}
 public SnapshotManifest directory(String tenant,String repo,String revision,Path selected,boolean complete,Set<String> deleted,BooleanSupplier cancelled)throws IOException {Path base=resolve(selected);List<SnapshotManifest.Entry> entries=new ArrayList<>();long total=0;try(var paths=Files.walk(base)){for(Path p:paths.sorted().toList()){checkCancelled(cancelled);if(Files.isSymbolicLink(p))throw new IOException("symlinks prohibited");if(Files.isDirectory(p))continue;if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("not a regular file");resolve(p);byte[] b=bounded(Files.newInputStream(p),limits.fileBytes());total=Math.addExact(total,b.length);if(total>limits.totalBytes()||entries.size()>=limits.entries())throw new IOException("snapshot quota");entries.add(entry(base.relativize(p).toString().replace('\\\\','/'),b));}}return new SnapshotManifest(tenant,repo,revision,complete,entries,deleted);}
 public SnapshotManifest archive(String tenant,String repo,String revision,Path selected,BooleanSupplier cancelled)throws IOException {Path archive=resolve(selected);if(Files.isSymbolicLink(selected))throw new IOException("symlinks prohibited");long compressed=Files.size(archive);if(compressed>limits.totalBytes())throw new IOException("archive quota");List<SnapshotManifest.Entry> entries=new ArrayList<>();Set<String> names=new HashSet<>();long total=0;try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(archive),StandardCharsets.UTF_8)){ZipEntry z;while((z=zip.getNextEntry())!=null){checkCancelled(cancelled);String name;try{name=SnapshotManifest.normalize(z.getName().replaceAll("/$",""));}catch(IllegalArgumentException e){throw new IOException("unsafe archive path",e);}if(!names.add(name)||names.size()>limits.entries())throw new IOException("archive entry quota or duplicate");if(z.isDirectory())continue;byte[] b=bounded(zip,limits.fileBytes());total+=b.length;if(total>limits.totalBytes()||total>Math.max(1,compressed)*limits.expansionRatio())throw new IOException("archive expansion quota");entries.add(entry(name,b));}}return new SnapshotManifest(tenant,repo,revision,false,entries,Set.of());}
 private Path resolve(Path path)throws IOException{Path p=path.isAbsolute()?path:root.resolve(path);if(!p.normalize().startsWith(root))throw new IOException("path escape");Path current=root;for(Path part:root.relativize(p.normalize())){current=current.resolve(part);if(Files.isSymbolicLink(current))throw new IOException("symlinks prohibited");}Path real=p.toRealPath();if(!real.startsWith(root))throw new IOException("path escape");return real;}
 private SnapshotManifest.Entry entry(String path,byte[] bytes)throws IOException {String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw new IOException("invalid UTF-8",e);}List<String> symbols=new ArrayList<>();var matcher=java.util.regex.Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*").matcher(text);while(matcher.find()&&symbols.size()<256){String s=matcher.group();if(s.length()<=256&&!symbols.contains(s))symbols.add(s);}return new SnapshotManifest.Entry(path,store.put(bytes),path.endsWith(".java")?"java":"text",path.endsWith(".java")?"source":"documentation",symbols,"","");}
 private static byte[] bounded(InputStream in,long max)throws IOException {try{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;long size=0;while((n=in.read(buffer))!=-1){size+=n;if(size>max)throw new IOException("file quota");b.write(buffer,0,n);}return b.toByteArray();}finally{if(!(in instanceof ZipInputStream))in.close();}}
 private static void checkCancelled(BooleanSupplier c)throws IOException{if(c.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("cancelled");}
}
''')
java('ingest','CheckpointStore','''import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.OptionalInt;
public final class CheckpointStore {
 private final Path root; public CheckpointStore(Path root)throws IOException{this.root=root;Files.createDirectories(root);}
 public void save(String manifest,int completed)throws IOException{if(completed<0)throw new IllegalArgumentException("checkpoint");String payload=manifest+"\\n"+completed;DurableFiles.atomicWrite(root.resolve(manifest), (payload+"\\n"+Digests.sha256(payload)).getBytes(StandardCharsets.UTF_8));}
 public OptionalInt read(String manifest)throws IOException{Path p=root.resolve(manifest);if(!Files.exists(p))return OptionalInt.empty();if(Files.size(p)>1024)throw new IOException("checkpoint quota");String[] parts=Files.readString(p).split("\\n");if(parts.length!=3||!parts[0].equals(manifest)||!Digests.sha256(parts[0]+"\\n"+parts[1]).equals(parts[2]))throw new IOException("corrupt checkpoint");try{return OptionalInt.of(Integer.parseInt(parts[1]));}catch(NumberFormatException e){throw new IOException("corrupt checkpoint",e);}}
}
''')
java('ingest','IngestionJob','''import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
public final class IngestionJob {
 public record Registered(String id,String manifestHash,SnapshotManifest manifest){}
 private final Path root;
 public IngestionJob(Path root)throws IOException{this.root=root;Files.createDirectories(root);}
 public synchronized Registered register(String key,SnapshotManifest manifest)throws IOException {if(key==null||key.isBlank()||key.length()>128)throw new IllegalArgumentException("idempotency key");String id=Digests.sha256(manifest.tenantId()+"\\n"+manifest.repositoryId()+"\\n"+key);Path p=root.resolve(id);String hash=manifest.digest();if(Files.exists(p)){SnapshotManifest old=SnapshotManifest.read(Files.readAllBytes(p));if(!old.digest().equals(hash))throw new Conflict();return new Registered(id,hash,old);}DurableFiles.atomicWrite(p,manifest.bytes());return new Registered(id,hash,manifest);}
 public Registered get(String id)throws IOException{if(!id.matches("[a-f0-9]{64}"))throw new IOException("job not found");SnapshotManifest m=SnapshotManifest.read(Files.readAllBytes(root.resolve(id)));return new Registered(id,m.digest(),m);}
 public static final class Conflict extends IllegalArgumentException {public Conflict(){super("idempotency conflict");}}
}
''')
