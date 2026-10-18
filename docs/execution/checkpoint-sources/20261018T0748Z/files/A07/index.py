from pathlib import Path
root=Path('/workspace/Java-portfolio/projects/07-indexforge');ns='io.github.cheshmakzanoon.portfolio.indexforge'
def write(p,s):
 p=root/p;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
def java(m,n,s,test=False):write(Path(m)/('src/test/java' if test else 'src/main/java')/Path(ns.replace('.','/'))/(n+'.java'),'package '+ns+';\n'+s)
java('index','CodeIdentifierAnalyzer',r'''import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.tokenattributes.*;
public final class CodeIdentifierAnalyzer extends Analyzer {
 public record Part(String term,int start,int end,int position){}
 public static List<Part> parts(String input){List<Part> out=new ArrayList<>();Matcher words=Pattern.compile("[\\p{L}\\p{N}_$]+").matcher(input);int position=0;while(words.find()){String original=words.group();out.add(new Part(original.toLowerCase(Locale.ROOT),words.start(),words.end(),++position));Matcher pieces=Pattern.compile("[\\p{Lu}]+(?=[\\p{Lu}][\\p{Ll}]|[^\\p{L}]|$)|[\\p{Lu}]?[\\p{Ll}]+|[\\p{N}]+|[\\p{L}]+").matcher(original);while(pieces.find()){String p=pieces.group().toLowerCase(Locale.ROOT);if(!p.equals(original.toLowerCase(Locale.ROOT)))out.add(new Part(p,words.start()+pieces.start(),words.start()+pieces.end(),position));}}return out;}
 @Override protected TokenStreamComponents createComponents(String field){Tokenizer t=new Tokenizer(){final CharTermAttribute term=addAttribute(CharTermAttribute.class);final OffsetAttribute offset=addAttribute(OffsetAttribute.class);final PositionIncrementAttribute increment=addAttribute(PositionIncrementAttribute.class);List<Part> tokens=List.of();int at,previous,length;
 @Override public void reset()throws IOException{super.reset();StringBuilder s=new StringBuilder();char[] b=new char[4096];int n;while((n=input.read(b))!=-1)s.append(b,0,n);length=s.length();tokens=parts(s.toString());at=previous=0;}
 @Override public boolean incrementToken(){if(at>=tokens.size())return false;clearAttributes();Part p=tokens.get(at++);term.append(p.term());offset.setOffset(correctOffset(p.start()),correctOffset(p.end()));increment.setPositionIncrement(p.position()-previous);previous=p.position();return true;}
 @Override public void end()throws IOException{super.end();offset.setOffset(correctOffset(length),correctOffset(length));}};return new TokenStreamComponents(t);}
}
''')
java('index','SourceDocument','''import java.util.*;
public record SourceDocument(String tenant,String repository,String revision,SnapshotManifest.Entry entry,String content){
 public String key(){return tenant+"/"+repository+"/"+entry.path();}
}
''')
java('index','DocumentMapper','''import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexableField;
import java.util.*;
public final class DocumentMapper {
 private DocumentMapper(){}
 public static Document map(SourceDocument s){Document d=new Document();exact(d,"key",s.key());exact(d,"tenant",s.tenant());exact(d,"repository",s.repository());exact(d,"path",s.entry().path());exact(d,"sha256",s.entry().sha256());exact(d,"revision",s.revision());exact(d,"language",s.entry().language());exact(d,"kind",s.entry().kind());exact(d,"coordinates",s.entry().coordinates());exact(d,"version",s.entry().version());d.add(new TextField("content",s.content(),Field.Store.YES));d.add(new TextField("identifiers",String.join(" ",s.entry().symbols()),Field.Store.NO));d.add(new TextField("path_tokens",s.entry().path(),Field.Store.NO));for(String symbol:s.entry().symbols())exact(d,"symbol",symbol.toLowerCase(Locale.ROOT));d.add(new SortedDocValuesField("key_sort",new org.apache.lucene.util.BytesRef(s.key())));d.add(new StoredField("schema",SnapshotManifest.SCHEMA));return d;}
 private static void exact(Document d,String field,String value){d.add(new StringField(field,value,Field.Store.YES));}
 public static SourceDocument source(Document d){List<String> symbols=Arrays.asList(d.getValues("symbol"));return new SourceDocument(d.get("tenant"),d.get("repository"),d.get("revision"),new SnapshotManifest.Entry(d.get("path"),d.get("sha256"),d.get("language"),d.get("kind"),symbols,d.get("coordinates"),d.get("version")),d.get("content"));}
}
''')
java('index','GenerationLease','''import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
public final class GenerationLease implements AutoCloseable {
 private final String id;private final DirectoryReader reader;private final Runnable release;private final AtomicBoolean closed=new AtomicBoolean();
 GenerationLease(String id,DirectoryReader reader,Runnable release){this.id=id;this.reader=reader;this.release=release;}
 public String id(){return id;}
 public IndexSearcher searcher(){if(closed.get())throw new IllegalStateException("closed lease");return new IndexSearcher(reader);}
 public DirectoryReader reader(){if(closed.get())throw new IllegalStateException("closed lease");return reader;}
 @Override public void close(){if(closed.compareAndSet(false,true))release.run();}
}
''')
java('index','GenerationBuilder','''import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
public final class GenerationBuilder {
 public enum Boundary {BUILD_START, DOCUMENT, BEFORE_COMMIT, AFTER_COMMIT, AFTER_WARM, AFTER_RENAME, BEFORE_POINTER, AFTER_POINTER, RETIRE}
 @FunctionalInterface public interface Fault {void at(Boundary boundary)throws IOException;static Fault none(){return boundary->{};}}
 public record Built(String id,Path path,String manifestHash,int count){}
 private final Path root;private final ContentAddressedStore cas;private final CheckpointStore checkpoints;
 public GenerationBuilder(Path root,ContentAddressedStore cas)throws IOException{this.root=root;this.cas=cas;this.checkpoints=new CheckpointStore(root.resolve("checkpoints"));Files.createDirectories(root.resolve("staging"));}
 public Built build(SnapshotManifest manifest,List<SourceDocument> previous,Fault fault,BooleanSupplier cancelled)throws IOException {String id=UUID.randomUUID().toString();Path dir=root.resolve("staging").resolve(id);Files.createDirectory(dir);fault.at(Boundary.BUILD_START);Map<String,SourceDocument> docs=new TreeMap<>();for(SourceDocument old:previous)docs.put(old.key(),old);String prefix=manifest.tenantId()+"/"+manifest.repositoryId()+"/";Set<String> uploaded=new HashSet<>();for(var e:manifest.entries())uploaded.add(prefix+e.path());if(manifest.complete())for(String key:docs.keySet())if(key.startsWith(prefix)&&!uploaded.contains(key)&&!manifest.deletions().contains(key.substring(prefix.length())))throw new IOException("complete snapshot requires explicit deletions");for(String deleted:manifest.deletions())docs.remove(prefix+deleted);for(var e:manifest.entries())docs.put(prefix+e.path(),new SourceDocument(manifest.tenantId(),manifest.repositoryId(),manifest.revision(),e,cas.text(e.sha256())));if(docs.size()>100_000)throw new IOException("tenant document quota");String hash=manifest.digest();try(Directory fs=FSDirectory.open(dir);Analyzer analyzer=new CodeIdentifierAnalyzer();IndexWriter writer=new IndexWriter(fs,new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE))){int n=0;for(SourceDocument s:docs.values()){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("cancelled");writer.addDocument(DocumentMapper.map(s));fault.at(Boundary.DOCUMENT);if(++n%1000==0)checkpoints.save(hash,n);}writer.setLiveCommitData(Map.of("manifest",hash,"schema",Integer.toString(SnapshotManifest.SCHEMA),"count",Integer.toString(docs.size()),"generation",id).entrySet());fault.at(Boundary.BEFORE_COMMIT);writer.commit();fault.at(Boundary.AFTER_COMMIT);}DurableFiles.atomicWrite(dir.resolve("manifest.bin"),manifest.bytes());try(Directory fs=FSDirectory.open(dir);DirectoryReader r=DirectoryReader.open(fs)){if(r.numDocs()!=docs.size())throw new IOException("count mismatch");new org.apache.lucene.search.IndexSearcher(r).count(new org.apache.lucene.search.MatchAllDocsQuery());}fault.at(Boundary.AFTER_WARM);DurableFiles.atomicWrite(dir.resolve("generation.sha256"),checksum(dir).getBytes(StandardCharsets.UTF_8));return new Built(id,dir,hash,docs.size());}
 public static String checksum(Path dir)throws IOException {StringBuilder b=new StringBuilder();try(var files=Files.list(dir)){for(Path p:files.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals("generation.sha256")&&!p.getFileName().toString().equals("write.lock")).sorted().toList())b.append(p.getFileName()).append(':').append(Digests.sha256(Files.readAllBytes(p))).append('\\n');}return Digests.sha256(b.toString());}
}
''')
java('index','GenerationPublisher','''import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
public final class GenerationPublisher implements AutoCloseable {
 private static final class Handle {final String id;final Directory directory;final DirectoryReader reader;int leases;boolean retired;Handle(String id,Directory d,DirectoryReader r){this.id=id;directory=d;reader=r;}}
 private final Path root;private final Map<String,Handle> handles=new HashMap<>();private String current;private boolean closed;
 public GenerationPublisher(Path root)throws IOException{this.root=root;Files.createDirectories(root.resolve("generations"));Files.createDirectories(root.resolve("quarantine"));Files.createDirectories(root.resolve("staging"));recover();}
 private void recover()throws IOException {for(String pointer:List.of("CURRENT","PREVIOUS")){Path p=root.resolve(pointer);if(!Files.exists(p))continue;try{String id=readPointer(p);Handle h=open(id);handles.put(id,h);current=id;if(pointer.equals("PREVIOUS"))DurableFiles.atomicWrite(root.resolve("CURRENT"),pointer(id));break;}catch(IOException|IllegalArgumentException ignored){Files.move(p,root.resolve("quarantine").resolve(pointer+"-"+UUID.randomUUID()),StandardCopyOption.ATOMIC_MOVE);}}
 try(var staged=Files.list(root.resolve("staging"))){for(Path p:staged.toList())Files.move(p,root.resolve("quarantine").resolve(p.getFileName()+"-incomplete"),StandardCopyOption.ATOMIC_MOVE);}}
 private byte[] pointer(String id)throws IOException{String body=id+"\\n"+Files.readString(root.resolve("generations").resolve(id).resolve("generation.sha256"));return (body+"\\n"+Digests.sha256(body)).getBytes(StandardCharsets.UTF_8);}
 private String readPointer(Path path)throws IOException{if(Files.size(path)>512)throw new IOException("pointer quota");String[] p=Files.readString(path).split("\\n");if(p.length!=3||!p[0].matches("[a-f0-9-]{36}")||!Digests.sha256(p[0]+"\\n"+p[1]).equals(p[2]))throw new IOException("corrupt pointer");Path dir=root.resolve("generations").resolve(p[0]);if(!p[1].equals(Files.readString(dir.resolve("generation.sha256"))))throw new IOException("checksum mismatch");return p[0];}
 private Handle open(String id)throws IOException{Path dir=root.resolve("generations").resolve(id);if(!GenerationBuilder.checksum(dir).equals(Files.readString(dir.resolve("generation.sha256"))))throw new IOException("generation checksum mismatch");SnapshotManifest manifest=SnapshotManifest.read(Files.readAllBytes(dir.resolve("manifest.bin")));Directory fs=FSDirectory.open(dir);try{DirectoryReader reader=DirectoryReader.open(fs);Map<String,String> metadata=reader.getIndexCommit().getUserData();if(!manifest.digest().equals(metadata.get("manifest"))||!id.equals(metadata.get("generation"))||!Integer.toString(reader.numDocs()).equals(metadata.get("count"))||!Integer.toString(SnapshotManifest.SCHEMA).equals(metadata.get("schema"))){reader.close();throw new IOException("commit metadata mismatch");}var stored=reader.storedFields();for(int i=0;i<reader.maxDoc();i++){var d=stored.document(i);if(!Digests.sha256(d.get("content")).equals(d.get("sha256"))){reader.close();throw new IOException("document digest mismatch");}}return new Handle(id,fs,reader);}catch(IOException|RuntimeException e){fs.close();throw e;}}
 public synchronized void publish(GenerationBuilder.Built built,GenerationBuilder.Fault fault)throws IOException{ensureOpen();Path destination=root.resolve("generations").resolve(built.id());Files.move(built.path(),destination,StandardCopyOption.ATOMIC_MOVE);DurableFiles.syncDirectory(destination.getParent());fault.at(GenerationBuilder.Boundary.AFTER_RENAME);Handle next=open(built.id());String old=current;boolean installed=false;try{if(old!=null)DurableFiles.atomicWrite(root.resolve("PREVIOUS"),pointer(old));fault.at(GenerationBuilder.Boundary.BEFORE_POINTER);DurableFiles.atomicWrite(root.resolve("CURRENT"),pointer(built.id()));handles.put(built.id(),next);current=built.id();installed=true;fault.at(GenerationBuilder.Boundary.AFTER_POINTER);if(old!=null){Handle h=handles.get(old);h.retired=true;fault.at(GenerationBuilder.Boundary.RETIRE);retire(h);}}finally{if(!installed){next.reader.close();next.directory.close();}}}
 public synchronized GenerationLease acquire(){return acquire(null);}
 public synchronized GenerationLease acquire(String requested){ensureOpen();String id=requested==null?current:requested;Handle h=handles.get(id);if(h==null||h.retired)throw new RetiredGeneration();h.leases++;return new GenerationLease(id,h.reader,()->release(h));}
 private synchronized void release(Handle h){h.leases--;try{retire(h);}catch(IOException e){throw new UncheckedIOException(e);}}
 private void retire(Handle h)throws IOException {if(h.retired&&h.leases==0){h.reader.close();h.directory.close();handles.remove(h.id);/* Retain previous committed files as restart fallback. Reader retirement is immediate. */}}
 public synchronized List<SourceDocument> sources(){if(current==null)return List.of();try(GenerationLease lease=acquire()){List<SourceDocument> result=new ArrayList<>();var stored=lease.reader().storedFields();for(int i=0;i<lease.reader().maxDoc();i++)result.add(DocumentMapper.source(stored.document(i)));return result;}catch(IOException e){throw new UncheckedIOException(e);}}
 public synchronized String currentId(){return current;}
 private void ensureOpen(){if(closed)throw new IllegalStateException("publisher closed");}
 @Override public synchronized void close()throws IOException{for(Handle h:handles.values()){if(h.leases!=0)throw new IOException("active leases");h.reader.close();h.directory.close();}handles.clear();closed=true;}
 public static final class RetiredGeneration extends IllegalStateException{public RetiredGeneration(){super("generation unavailable");}}
}
''')
