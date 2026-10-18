exec(open('/workspace/Java-portfolio/.work/A07/index.py').read().split("java('index'")[0])
java('query','QueryAst',r'''import java.util.*;
public sealed interface QueryAst permits QueryAst.Term,QueryAst.BooleanNode,QueryAst.Negation {
 record Term(String field,String value,boolean phrase) implements QueryAst{}
 record BooleanNode(boolean or,QueryAst left,QueryAst right) implements QueryAst{}
 record Negation(QueryAst child) implements QueryAst{}
 static QueryAst parse(String text){return new Parser(text).parse();}
 final class Parser {
 private record Token(String text,boolean phrase){}
 private final List<Token> tokens=new ArrayList<>();private int at;
 Parser(String input){if(input==null||input.isBlank()||input.length()>512)throw new IllegalArgumentException("query length");int i=0;while(i<input.length()){char c=input.charAt(i);if(Character.isWhitespace(c)){i++;continue;}if(c=='('||c==')'||c==':'){tokens.add(new Token(String.valueOf(c),false));i++;}else if(c=='"'){int end=input.indexOf('"',i+1);if(end<0||end==i+1)throw new IllegalArgumentException("phrase");tokens.add(new Token(input.substring(i+1,end),true));i=end+1;}else{int begin=i;while(i<input.length()&&!Character.isWhitespace(input.charAt(i))&&"():\"".indexOf(input.charAt(i))<0)i++;String term=input.substring(begin,i);if(term.matches(".*[\\*?~{}\\[\\]\\\\].*"))throw new IllegalArgumentException("unsupported operator");tokens.add(new Token(term,false));}if(tokens.size()>64)throw new IllegalArgumentException("query clause quota");}}
 QueryAst parse(){QueryAst n=or(0);if(at!=tokens.size())throw new IllegalArgumentException("unexpected token");return n;}
 QueryAst or(int depth){QueryAst n=and(depth);while(accept("OR"))n=new BooleanNode(true,n,and(depth));return n;}
 QueryAst and(int depth){QueryAst n=unary(depth);while(at<tokens.size()&&!peek(")")&&!peek("OR")){accept("AND");n=new BooleanNode(false,n,unary(depth));}return n;}
 QueryAst unary(int depth){if(depth>8)throw new IllegalArgumentException("query depth");if(accept("NOT"))return new Negation(unary(depth+1));if(accept("(")){QueryAst n=or(depth+1);require(")");return n;}if(at>=tokens.size()||peek(")")||peek("AND")||peek("OR")||peek(":"))throw new IllegalArgumentException("missing operand");Token t=tokens.get(at++);String field="all";if(accept(":")){if(t.phrase)throw new IllegalArgumentException("field");field=t.text;if(!Set.of("content","symbol","path","repository","language","kind","coordinates","version").contains(field))throw new IllegalArgumentException("unsupported field");if(at>=tokens.size())throw new IllegalArgumentException("missing field value");t=tokens.get(at++);if(Set.of("(",")",":","AND","OR","NOT").contains(t.text))throw new IllegalArgumentException("field value");}return new Term(field,t.text,t.phrase);}
 boolean peek(String t){return at<tokens.size()&&!tokens.get(at).phrase&&tokens.get(at).text.equals(t);}
 boolean accept(String t){if(peek(t)){at++;return true;}return false;}
 void require(String t){if(!accept(t))throw new IllegalArgumentException("unclosed group");}
 }
}
''')
java('query','QueryPlanner','''import java.util.*;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
public final class QueryPlanner {
 public enum Mode {BASELINE,SOURCE_AWARE,NO_IDENTIFIERS,NO_PATH}
 public static final String VERSION="source-v1";
 public Query plan(QueryAst ast,Mode mode){if(ast instanceof QueryAst.BooleanNode n)return new BooleanQuery.Builder().add(plan(n.left(),mode),n.or()?BooleanClause.Occur.SHOULD:BooleanClause.Occur.MUST).add(plan(n.right(),mode),n.or()?BooleanClause.Occur.SHOULD:BooleanClause.Occur.MUST).build();if(ast instanceof QueryAst.Negation n)return new BooleanQuery.Builder().add(new MatchAllDocsQuery(),BooleanClause.Occur.MUST).add(plan(n.child(),mode),BooleanClause.Occur.MUST_NOT).build();QueryAst.Term t=(QueryAst.Term)ast;String value=t.value().toLowerCase(Locale.ROOT);if(!t.field().equals("all")&&!t.field().equals("content"))return new TermQuery(new Term(t.field(),value));if(t.field().equals("content")||mode==Mode.BASELINE)return analyzed("content",value,t.phrase());BooleanQuery.Builder b=new BooleanQuery.Builder().add(analyzed("content",value,t.phrase()),BooleanClause.Occur.SHOULD);if(mode!=Mode.NO_IDENTIFIERS){b.add(new BoostQuery(analyzed("identifiers",value,t.phrase()),3f),BooleanClause.Occur.SHOULD);b.add(new BoostQuery(new TermQuery(new Term("symbol",value)),8f),BooleanClause.Occur.SHOULD);}if(mode!=Mode.NO_PATH)b.add(new BoostQuery(analyzed("path_tokens",value,t.phrase()),2f),BooleanClause.Occur.SHOULD);return b.build();}
 private Query analyzed(String field,String value,boolean phrase){List<CodeIdentifierAnalyzer.Part> parts=CodeIdentifierAnalyzer.parts(value);if(parts.isEmpty())return new MatchNoDocsQuery();if(phrase){PhraseQuery.Builder b=new PhraseQuery.Builder();int previous=-1;for(var p:parts)if(p.position()!=previous){b.add(new Term(field,p.term()),p.position()-1);previous=p.position();}return b.build();}BooleanQuery.Builder b=new BooleanQuery.Builder();for(var p:parts)b.add(new TermQuery(new Term(field,p.term())),BooleanClause.Occur.SHOULD);return b.build();}
}
''')
java('query','AuthorizationFilter','''import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
public final class AuthorizationFilter {
 public record Access(String principal,String tenant,Set<String> repositories,long version,boolean administrator){public Access{if(principal==null||principal.isBlank()||tenant==null||!tenant.matches("[a-zA-Z0-9_-]{1,64}")||repositories.size()>64)throw new IllegalArgumentException("access context");repositories=Set.copyOf(repositories);}}
 private final Map<String,Access> principals=new ConcurrentHashMap<>();private final AtomicLong sequence=new AtomicLong();
 public Access update(String principal,String tenant,Set<String> repositories,boolean admin){Access a=new Access(principal,tenant,repositories,sequence.incrementAndGet(),admin);principals.put(principal,a);return a;}
 public Access current(String principal){Access a=principals.get(principal);if(a==null)throw new SecurityException("access denied");return a;}
 public boolean current(Access a){return a.equals(principals.get(a.principal()));}
 public Query apply(Query query,Access a){BooleanQuery.Builder repos=new BooleanQuery.Builder();for(String r:new TreeSet<>(a.repositories()))repos.add(new TermQuery(new Term("repository",r)),BooleanClause.Occur.SHOULD);Query permitted=a.repositories().isEmpty()?new MatchNoDocsQuery():repos.build();return new BooleanQuery.Builder().add(query,BooleanClause.Occur.MUST).add(new TermQuery(new Term("tenant",a.tenant())),BooleanClause.Occur.FILTER).add(permitted,BooleanClause.Occur.FILTER).build();}
 public void require(Access a,String tenant,String repository){if(!a.tenant().equals(tenant)||!a.repositories().contains(repository)||!current(a))throw new SecurityException("access denied");}
}
''')
java('query','SearchCursor','''import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
public final class SearchCursor {
 public record Position(String generation,int offset){}
 private final byte[] key;private final Clock clock;
 public SearchCursor(byte[] key,Clock clock){if(key.length<32)throw new IllegalArgumentException("cursor key");this.key=key.clone();this.clock=clock;}
 public String encode(AuthorizationFilter.Access a,String digest,String generation,int offset){String payload=a.tenant()+"\\n"+a.principal()+"\\n"+a.version()+"\\n"+digest+"\\n"+generation+"\\n"+offset+"\\n"+(clock.instant().getEpochSecond()+300);byte[] b=payload.getBytes(StandardCharsets.UTF_8);return Base64.getUrlEncoder().withoutPadding().encodeToString(b)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(b));}
 public Position decode(String token,AuthorizationFilter.Access a,String digest){if(token==null||token.length()>2048)throw new IllegalArgumentException("cursor");try{String[] pieces=token.split("\\.");if(pieces.length!=2)throw new IllegalArgumentException("cursor");byte[] b=Base64.getUrlDecoder().decode(pieces[0]);if(!MessageDigest.isEqual(sign(b),Base64.getUrlDecoder().decode(pieces[1])))throw new IllegalArgumentException("cursor signature");String[] p=new String(b,StandardCharsets.UTF_8).split("\\n");if(p.length!=7||!p[0].equals(a.tenant())||!p[1].equals(a.principal())||Long.parseLong(p[2])!=a.version()||!p[3].equals(digest)||Long.parseLong(p[6])<=clock.instant().getEpochSecond()||!p[4].matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("cursor context");int offset=Integer.parseInt(p[5]);if(offset<0||offset>500)throw new IllegalArgumentException("cursor offset");return new Position(p[4],offset);}catch(RuntimeException e){throw new IllegalArgumentException("invalid cursor");}}
 private byte[] sign(byte[] data){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(data);}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
}
''')
java('query','SnippetBuilder','''import java.util.*;
public final class SnippetBuilder {
 private SnippetBuilder(){}
 public static String snippet(String content,String query){String lower=content.toLowerCase(Locale.ROOT);int at=lower.indexOf(query.toLowerCase(Locale.ROOT));if(at<0)at=0;int start=Math.max(0,at-60),end=Math.min(content.length(),start+240);return content.substring(start,end);}
}
''')
java('query','CandidateRetriever','''import java.io.*;
import java.util.*;
import org.apache.lucene.search.*;
public final class CandidateRetriever {
 public record Candidate(int document,String key,float score){}
 public List<Candidate> retrieve(GenerationLease lease,Query authorized)throws IOException{TopDocs hits=lease.searcher().search(authorized,500,new Sort(SortField.FIELD_SCORE,new SortField("key_sort",SortField.Type.STRING)));List<Candidate> out=new ArrayList<>();for(ScoreDoc h:hits.scoreDocs)out.add(new Candidate(h.doc,lease.reader().storedFields().document(h.doc).get("key"),h.score));return out;}
}
''')
java('query','Ranker','''import java.util.*;
public final class Ranker {
 public List<CandidateRetriever.Candidate> rank(List<CandidateRetriever.Candidate> candidates){return candidates.stream().sorted(Comparator.comparingDouble((CandidateRetriever.Candidate c)->-c.score()).thenComparing(CandidateRetriever.Candidate::key)).toList();}
}
''')
java('query','SearchEngine','''import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
public final class SearchEngine implements AutoCloseable {
 public record Request(String text,int pageSize,String cursor,Map<String,String> filters){public Request{if(pageSize<1||pageSize>100)throw new IllegalArgumentException("page size");filters=filters==null?Map.of():Map.copyOf(filters);if(filters.size()>8||filters.entrySet().stream().anyMatch(e->!Set.of("repository","language","kind","coordinates","version").contains(e.getKey())||e.getValue().length()>256))throw new IllegalArgumentException("filter");}}
 public record Hit(String key,String path,String repository,String sha256,float score,String snippet,String explanation){}
 public record Response(String generationId,String scoringVersion,long totalHits,String relation,List<Hit> results,Map<String,Integer> facets,String cursor){}
 private final Path root;private final ContentAddressedStore cas;private final IngestionJob jobs;private final GenerationPublisher publisher;private final GenerationBuilder builder;private final AuthorizationFilter authorization;private final SearchCursor cursors;
 public SearchEngine(Path root,AuthorizationFilter authorization)throws IOException {this.root=root;Files.createDirectories(root);this.authorization=authorization;cas=new ContentAddressedStore(root.resolve("cas"));jobs=new IngestionJob(root.resolve("jobs"));publisher=new GenerationPublisher(root.resolve("index"));builder=new GenerationBuilder(root.resolve("index"),cas);Path secret=root.resolve("cursor.key");if(!Files.exists(secret)){byte[] key=new byte[32];new SecureRandom().nextBytes(key);DurableFiles.atomicWrite(secret,key);try{Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}cursors=new SearchCursor(Files.readAllBytes(secret),Clock.systemUTC());}
 public ContentAddressedStore contentStore(){return cas;}
 public GenerationPublisher generations(){return publisher;}
 public synchronized IngestionJob.Registered ingest(AuthorizationFilter.Access a,String key,SnapshotManifest m,GenerationBuilder.Fault fault,BooleanSupplier cancelled)throws IOException {authorization.require(a,m.tenantId(),m.repositoryId());IngestionJob.Registered job=jobs.register(key,m);Path done=root.resolve("jobs").resolve(job.id()+".done");if(Files.exists(done))return job;var built=builder.build(m,publisher.sources(),fault,cancelled);authorization.require(a,m.tenantId(),m.repositoryId());publisher.publish(built,fault);DurableFiles.atomicWrite(done,built.id().getBytes(java.nio.charset.StandardCharsets.UTF_8));return job;}
 public IngestionJob.Registered job(AuthorizationFilter.Access a,String id)throws IOException{var j=jobs.get(id);authorization.require(a,j.manifest().tenantId(),j.manifest().repositoryId());return j;}
 public Response search(String principal,Request request)throws IOException{return search(principal,request,QueryPlanner.Mode.SOURCE_AWARE);}
 public Response search(String principal,Request request,QueryPlanner.Mode mode)throws IOException {QueryAst ast=QueryAst.parse(request.text());String digest=Digests.sha256(request.text()+"\\n"+new TreeMap<>(request.filters())+"\\n"+request.pageSize()+"\\n"+mode);for(int retry=0;retry<3;retry++){AuthorizationFilter.Access a=authorization.current(principal);SearchCursor.Position position=request.cursor()==null?null:cursors.decode(request.cursor(),a,digest);try(GenerationLease lease=publisher.acquire(position==null?null:position.generation())){BooleanQuery.Builder qb=new BooleanQuery.Builder().add(new QueryPlanner().plan(ast,mode),BooleanClause.Occur.MUST);for(var f:request.filters().entrySet())qb.add(new TermQuery(new Term(f.getKey(),f.getValue())),BooleanClause.Occur.FILTER);Query authorized=authorization.apply(qb.build(),a);var ranked=new Ranker().rank(new CandidateRetriever().retrieve(lease,authorized));int start=position==null?0:position.offset(),end=Math.min(ranked.size(),start+request.pageSize());if(start>ranked.size())throw new IllegalArgumentException("cursor offset");List<Hit> hits=new ArrayList<>();for(var c:ranked.subList(start,end)){var d=lease.reader().storedFields().document(c.document());hits.add(new Hit(c.key(),d.get("path"),d.get("repository"),d.get("sha256"),c.score(),SnippetBuilder.snippet(d.get("content"),request.text()),lease.searcher().explain(authorized,c.document()).toString()));}long count=lease.searcher().count(authorized);Map<String,Integer> facets=new TreeMap<>();for(String repo:new TreeSet<>(a.repositories())){Query q=new BooleanQuery.Builder().add(authorized,BooleanClause.Occur.MUST).add(new TermQuery(new Term("repository",repo)),BooleanClause.Occur.FILTER).build();int n=lease.searcher().count(q);if(n>0)facets.put(repo,n);}String next=end<ranked.size()?cursors.encode(a,digest,lease.id(),end):null;Response response=new Response(lease.id(),mode==QueryPlanner.Mode.BASELINE?"bm25-content-v1":QueryPlanner.VERSION,count,"EQUAL_TO",List.copyOf(hits),Map.copyOf(facets),next);if(authorization.current(a))return response;}}throw new SecurityException("authorization changed; retry");}
 public List<String> suggest(String principal,String prefix)throws IOException {if(prefix==null||!prefix.matches("[\\p{L}\\p{N}_$]{1,64}"))throw new IllegalArgumentException("prefix");for(int retry=0;retry<3;retry++){var a=authorization.current(principal);try(var lease=publisher.acquire()){Query q=authorization.apply(new PrefixQuery(new Term("symbol",prefix.toLowerCase(Locale.ROOT))),a);List<String> out=new ArrayList<>();for(var c:new CandidateRetriever().retrieve(lease,q)){var d=lease.reader().storedFields().document(c.document());for(String symbol:d.getValues("symbol"))if(symbol.startsWith(prefix.toLowerCase(Locale.ROOT))&&!out.contains(symbol)&&out.size()<20)out.add(symbol);}Collections.sort(out);if(authorization.current(a))return List.copyOf(out);}}throw new SecurityException("authorization changed; retry");}
 public synchronized void reindex(AuthorizationFilter.Access a)throws IOException{if(!a.administrator()||!authorization.current(a))throw new SecurityException("access denied");List<SourceDocument> sources=publisher.sources().stream().filter(d->d.tenant().equals(a.tenant())&&a.repositories().contains(d.repository())).toList();if(sources.isEmpty())throw new IllegalArgumentException("nothing to reindex");SourceDocument first=sources.getFirst();SnapshotManifest m=new SnapshotManifest(a.tenant(),first.repository(),"reindex",false,sources.stream().filter(d->d.repository().equals(first.repository())).map(SourceDocument::entry).toList(),Set.of());ingest(a,"reindex-"+UUID.randomUUID(),m,GenerationBuilder.Fault.none(),()->false);}
 @Override public void close()throws IOException{publisher.close();}
}
''')
