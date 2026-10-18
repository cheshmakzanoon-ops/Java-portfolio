exec(open('/workspace/Java-portfolio/.work/A07/index.py').read().split("java('index'")[0])
java('service','LocalOidcIssuer',r'''import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
public final class LocalOidcIssuer implements AutoCloseable {
 private final KeyPair pair;private final HttpServer server;private final String issuer;
 public LocalOidcIssuer(int port)throws IOException {try{KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);pair=generator.generateKeyPair();}catch(GeneralSecurityException e){throw new IOException(e);}server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),port),8);issuer="http://127.0.0.1:"+server.getAddress().getPort();server.createContext("/.well-known/openid-configuration",exchange->{byte[] bytes=("{\"issuer\":\""+issuer+"\",\"jwks_uri\":\""+issuer+"/jwks\",\"id_token_signing_alg_values_supported\":[\"RS256\"]}").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.createContext("/jwks",exchange->{RSAPublicKey key=(RSAPublicKey)pair.getPublic();String body="{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"disposable-local\",\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\""+unsigned(key.getModulus())+"\",\"e\":\""+unsigned(key.getPublicExponent())+"\"}]}";byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();}
 private static String unsigned(BigInteger n){byte[] b=n.toByteArray();if(b[0]==0)b=Arrays.copyOfRange(b,1,b.length);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 public String issuer(){return issuer;}
 public String token(String subject,String tenant,Instant expiration,String audience){if(!subject.matches("[a-zA-Z0-9_-]+")||!tenant.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("identity");return sign("{\"iss\":\""+issuer+"\",\"sub\":\""+subject+"\",\"aud\":[\""+audience+"\"],\"iat\":"+Instant.now().getEpochSecond()+",\"exp\":"+expiration.getEpochSecond()+",\"tenant\":\""+tenant+"\",\"scope\":\"search ingest\"}");}
 public String sign(String claims){String h=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\",\"kid\":\"disposable-local\"}".getBytes(StandardCharsets.UTF_8));String payload=h+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8));try{Signature s=Signature.getInstance("SHA256withRSA");s.initSign(pair.getPrivate());s.update(payload.getBytes(StandardCharsets.US_ASCII));return payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
 @Override public void close(){server.stop(0);}
}
''')
java('service','IndexForgeApplication',r'''import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@SpringBootApplication
public class IndexForgeApplication {
 public static void main(String[] args){SpringApplication application=new SpringApplication(IndexForgeApplication.class);application.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","18700","spring.profiles.default","dev","server.tomcat.threads.max","16","server.tomcat.accept-count","32","server.max-http-request-header-size","16KB"));application.run(args);}
 @Bean AuthorizationFilter authorization(Environment env){AuthorizationFilter auth=new AuthorizationFilter();String users=env.getProperty("indexforge.users","alice:demo:public:true;bob:other:public:false");for(String spec:users.split(";")){String[] p=spec.split(":",-1);if(p.length!=4)throw new IllegalArgumentException("users format subject:tenant:repo,repo:admin");auth.update(p[0],p[1],Set.of(p[2].split(",")),Boolean.parseBoolean(p[3]));}return auth;}
 @Bean(destroyMethod="close") SearchEngine searchEngine(Environment env,AuthorizationFilter auth)throws IOException{return new SearchEngine(Path.of(env.getProperty("indexforge.data",".runtime/indexforge")),auth);}
 @Bean @Profile("dev") LocalOidcIssuer localIssuer(Environment env)throws IOException{String binding=env.getProperty("server.address","127.0.0.1");if(!InetAddress.getByName(binding).isLoopbackAddress())throw new IllegalArgumentException("disposable development issuer requires loopback binding");LocalOidcIssuer issuer=new LocalOidcIssuer(Integer.parseInt(env.getProperty("indexforge.issuer-port","18701")));Path data=Path.of(env.getProperty("indexforge.data",".runtime/indexforge"));Files.createDirectories(data);DurableFiles.atomicWrite(data.resolve("dev-token"),issuer.token("alice","demo",Instant.now().plusSeconds(3600),"indexforge").getBytes(java.nio.charset.StandardCharsets.UTF_8));try{Files.setPosixFilePermissions(data.resolve("dev-token"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}return issuer;}
}
''')
java('service','SecurityConfiguration',r'''import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
@Configuration
public class SecurityConfiguration {
 @Bean JwtDecoder jwtDecoder(Environment env,ObjectProvider<LocalOidcIssuer> local){LocalOidcIssuer dev=local.getIfAvailable();String issuer=dev==null?env.getRequiredProperty("indexforge.issuer"):dev.issuer();String jwks=dev==null?env.getRequiredProperty("indexforge.jwks"):issuer+"/jwks";NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),strictClaims("indexforge")));return decoder;}
 public static OAuth2TokenValidator<Jwt> strictClaims(String audience){return jwt->{try{Object tenant=jwt.getClaims().get("tenant");Object scope=jwt.getClaims().get("scope");if(jwt.getExpiresAt()==null||jwt.getSubject()==null||jwt.getSubject().isBlank()||jwt.getAudience()==null||!jwt.getAudience().contains(audience)||!(tenant instanceof String t)||!t.matches("[a-zA-Z0-9_-]{1,64}")||!(scope instanceof String s)||!Arrays.asList(s.split(" ")).contains("search")||!jwt.getExpiresAt().isAfter(Instant.now()))return invalid();return OAuth2TokenValidatorResult.success();}catch(RuntimeException e){return invalid();}};}
 private static OAuth2TokenValidatorResult invalid(){return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","required claims invalid",null));}
 @Bean SecurityFilterChain security(HttpSecurity http)throws Exception{return http.csrf(csrf->csrf.disable()).authorizeHttpRequests(requests->requests.requestMatchers("/health").permitAll().anyRequest().authenticated()).oauth2ResourceServer(resource->resource.jwt(jwt->{})).sessionManagement(session->session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS)).build();}
}
''')
java('service','AdmissionFilter',r'''import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
@Component @Order(-200)
public class AdmissionFilter extends OncePerRequestFilter {
 private final Semaphore permits=new Semaphore(32);
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{if(!permits.tryAcquire()){response.sendError(429);return;}try{if(request.getContentLengthLong()>65536){response.sendError(413);return;}if(SetLike.hasBody(request.getMethod())){byte[] bytes=request.getInputStream().readNBytes(65537);if(bytes.length>65536){response.sendError(413);return;}chain.doFilter(new HttpServletRequestWrapper(request){@Override public ServletInputStream getInputStream(){ByteArrayInputStream in=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener l){throw new UnsupportedOperationException();}};}@Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}@Override public int getContentLength(){return bytes.length;}@Override public long getContentLengthLong(){return bytes.length;}},response);}else chain.doFilter(request,response);}finally{permits.release();}}
 private static final class SetLike{static boolean hasBody(String method){return method.equals("POST")||method.equals("PUT")||method.equals("PATCH");}}
}
''')
java('service','SearchController',r'''import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
public class SearchController implements AutoCloseable {
 public record IngestRequest(String repository,String revision,String idempotencyKey,String path,boolean archive,boolean complete,Set<String> deletions){}
 public record SuggestRequest(String prefix){}
 private final SearchEngine engine;private final AuthorizationFilter auth;private final Path sources;private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),Thread.ofPlatform().name("indexforge-ingest-",0).factory(),new ThreadPoolExecutor.AbortPolicy());private final ConcurrentMap<String,Future<?>> futures=new ConcurrentHashMap<>();private final ConcurrentMap<String,String> states=new ConcurrentHashMap<>();private final AtomicLong searches=new AtomicLong(),failures=new AtomicLong();
 public SearchController(SearchEngine engine,AuthorizationFilter auth,Environment env)throws IOException{this.engine=engine;this.auth=auth;sources=Path.of(env.getProperty("indexforge.sources",".runtime/sources"));Files.createDirectories(sources);}
 private AuthorizationFilter.Access context(Jwt jwt,boolean write){if(jwt==null)throw new SecurityException("access denied");var a=auth.current(jwt.getSubject());if(!a.tenant().equals(jwt.getClaimAsString("tenant"))||(write&&!Arrays.asList(jwt.getClaimAsString("scope").split(" ")).contains("ingest")))throw new SecurityException("access denied");return a;}
 @GetMapping("/health") Map<String,String> health(){return Map.of("status","UP");}
 @PostMapping("/v1/search") SearchEngine.Response search(@AuthenticationPrincipal Jwt jwt,@RequestBody SearchEngine.Request request)throws IOException{var a=context(jwt,false);searches.incrementAndGet();return engine.search(a.principal(),request);}
 @PostMapping("/v1/suggest") List<String> suggest(@AuthenticationPrincipal Jwt jwt,@RequestBody SuggestRequest request)throws IOException{return engine.suggest(context(jwt,false).principal(),request.prefix());}
 @PostMapping("/v1/ingestions") ResponseEntity<Map<String,String>> ingest(@AuthenticationPrincipal Jwt jwt,@RequestBody IngestRequest request)throws IOException{var a=context(jwt,true);auth.require(a,a.tenant(),request.repository());if(request.path()==null||request.revision()==null||request.idempotencyKey()==null)throw new IllegalArgumentException("missing ingestion field");SafeSourceReader reader=new SafeSourceReader(sources,SafeSourceReader.Limits.defaults(),engine.contentStore());SnapshotManifest manifest=request.archive()?reader.archive(a.tenant(),request.repository(),request.revision(),Path.of(request.path()),()->false):reader.directory(a.tenant(),request.repository(),request.revision(),Path.of(request.path()),request.complete(),request.deletions()==null?Set.of():request.deletions(),()->false);var job=engine.register(a,request.idempotencyKey(),manifest);synchronized(futures){if(!futures.containsKey(job.id())&&!engine.completed(job.id())){FutureTask<Void> task=new FutureTask<>(()->{states.put(job.id(),"RUNNING");try{engine.ingest(a,request.idempotencyKey(),manifest,GenerationBuilder.Fault.none(),Thread.currentThread()::isInterrupted);states.put(job.id(),"COMPLETE");}catch(Exception e){states.put(job.id(),Thread.currentThread().isInterrupted()?"CANCELLED":"FAILED");failures.incrementAndGet();}return null;});states.put(job.id(),"QUEUED");workers.execute(task);futures.put(job.id(),task);}}return ResponseEntity.accepted().body(Map.of("id",job.id(),"state",engine.completed(job.id())?"COMPLETE":states.getOrDefault(job.id(),"REGISTERED")));}
 @GetMapping("/v1/ingestions/{id}") Map<String,String> job(@AuthenticationPrincipal Jwt jwt,@PathVariable String id)throws IOException{engine.job(context(jwt,false),id);return Map.of("id",id,"state",engine.completed(id)?"COMPLETE":states.getOrDefault(id,"REGISTERED"));}
 @DeleteMapping("/v1/ingestions/{id}") Map<String,String> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable String id)throws IOException{engine.job(context(jwt,true),id);Future<?> f=futures.get(id);if(f!=null&&f.cancel(true))states.put(id,"CANCELLED");return Map.of("id",id,"state",states.getOrDefault(id,"REGISTERED"));}
 @PostMapping("/v1/reindexes") Map<String,String> reindex(@AuthenticationPrincipal Jwt jwt)throws IOException{engine.reindex(context(jwt,true));return Map.of("state","COMPLETE");}
 @GetMapping("/v1/metrics") Map<String,Long> metrics(@AuthenticationPrincipal Jwt jwt){if(!context(jwt,false).administrator())throw new SecurityException("access denied");return Map.of("searches",searches.get(),"jobFailures",failures.get(),"queuedJobs",(long)workers.getQueue().size());}
 @Override public void close(){workers.shutdownNow();}
}
''')
java('service','ErrorResponses',r'''import java.io.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public class ErrorResponses {
 @ExceptionHandler(SecurityException.class) ResponseEntity<Map<String,String>> denied(){return error(403,"ACCESS_DENIED");}
 @ExceptionHandler(IngestionJob.Conflict.class) ResponseEntity<Map<String,String>> conflict(){return error(409,"IDEMPOTENCY_CONFLICT");}
 @ExceptionHandler(GenerationPublisher.RetiredGeneration.class) ResponseEntity<Map<String,String>> retired(){return error(410,"GENERATION_RETIRED");}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,String>> invalid(){return error(400,"INVALID_REQUEST");}
 @ExceptionHandler(RejectedExecutionException.class) ResponseEntity<Map<String,String>> overload(){return error(429,"QUEUE_FULL");}
 @ExceptionHandler(IOException.class) ResponseEntity<Map<String,String>> io(){return error(422,"SOURCE_OR_STORAGE_FAILURE");}
 private ResponseEntity<Map<String,String>> error(int status,String code){return ResponseEntity.status(status).body(Map.of("error",code));}
}
''')
# Expose durable registration and status to bounded asynchronous adapter.
p=root/'query/src/main/java'/Path(ns.replace('.','/'))/'SearchEngine.java';s=p.read_text();s=s.replace(' public synchronized IngestionJob.Registered ingest(',''' public IngestionJob.Registered register(AuthorizationFilter.Access a,String key,SnapshotManifest m)throws IOException{authorization.require(a,m.tenantId(),m.repositoryId());return jobs.register(key,m);}
 public boolean completed(String id){return id.matches("[a-f0-9]{64}")&&Files.exists(root.resolve("jobs").resolve(id+".done"));}
 public synchronized IngestionJob.Registered ingest(''');p.write_text(s)
