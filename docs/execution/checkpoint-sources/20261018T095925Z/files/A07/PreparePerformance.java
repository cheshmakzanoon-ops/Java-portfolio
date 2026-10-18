import io.github.cheshmakzanoon.portfolio.indexforge.*;
import java.nio.file.*;
import java.util.*;

/** Untimed preparation and one configuration sanity query; no performance measurement. */
class PreparePerformance {
 public static void main(String[] args) throws Exception {
  if(Runtime.getRuntime().maxMemory()>2147483648L)throw new IllegalArgumentException("2GiB maximum declared heap");
  var mode=QueryPlanner.Mode.valueOf(System.getProperty("indexforge.ranking-mode"));
  var profile=RankingProfile.valueOf(System.getProperty("indexforge.ranking-profile"));
  if(mode!=QueryPlanner.Mode.SOURCE_AWARE||profile!=RankingProfile.CODE_HIGH)throw new IllegalArgumentException("selected configuration required");
  Path data=Path.of(args[0]);if(Files.exists(data))throw new IllegalArgumentException("fresh preparation directory required");
  var auth=new AuthorizationFilter();List<AuthorizationFilter.Access> access=new ArrayList<>();for(int t=0;t<3;t++)access.add(auth.update("load"+t,"tenant"+t,Set.of("public"),true));
  try(var engine=new SearchEngine(data,auth,mode,profile)) {
   var generated=WorkloadGenerator.generate(engine.contentStore(),100000,7007);
   for(int t=0;t<3;t++)engine.ingest(access.get(t),"benchmark-100000",generated.manifests().get(t),GenerationBuilder.Fault.none(),()->false);
   long count=0;List<Integer> counts=new ArrayList<>();List<String> generations=new ArrayList<>();for(int t=0;t<3;t++){try(var lease=engine.generations("tenant"+t).acquire()){int n=lease.reader().numDocs();counts.add(n);count+=n;generations.add("\""+lease.id()+"\"");}}
   if(count!=100000||!counts.equals(List.of(33334,33333,33333)))throw new AssertionError("prepared count mismatch");
   var sanity=engine.search("load0",new SearchEngine.Request("atomic publication",10,null,Map.of()));
   if(!sanity.scoringVersion().equals(QueryPlanner.VERSION+"-"+profile)||sanity.totalHits()==0)throw new AssertionError("actual runtime selected config mismatch");
   System.out.println("{\"phase\":\"preparation-only\",\"seed\":7007,\"documents\":100000,\"tenantCounts\":"+counts+",\"generations\":["+String.join(",",generations)+"],\"sourceBytes\":"+generated.bytes()+",\"heapMaxBytes\":"+Runtime.getRuntime().maxMemory()+",\"rankingMode\":\""+mode+"\",\"rankingProfile\":\""+profile+"\",\"scoringVersion\":\""+sanity.scoringVersion()+"\",\"compiledModelSha256\":\""+RelevanceEvaluator.modelFingerprint()+"\",\"sanityQueries\":1,\"measuredRuns\":0}");
  }
 }
}
