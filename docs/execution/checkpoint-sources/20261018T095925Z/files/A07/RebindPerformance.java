import io.github.cheshmakzanoon.portfolio.indexforge.*;
import java.nio.file.*;
import java.util.*;

/** Verify unchanged committed index with the newly certified executable; no timed runs. */
class RebindPerformance {
 public static void main(String[] args) throws Exception {
  if(Runtime.getRuntime().maxMemory()>2147483648L)throw new IllegalArgumentException("heap cap");
  var mode=QueryPlanner.Mode.valueOf(System.getProperty("indexforge.ranking-mode"));
  var profile=RankingProfile.valueOf(System.getProperty("indexforge.ranking-profile"));
  if(mode!=QueryPlanner.Mode.BASELINE||profile!=RankingProfile.CODE_HIGH)throw new IllegalArgumentException("fixed candidate required");
  var auth=new AuthorizationFilter();for(int t=0;t<3;t++)auth.update("load"+t,"tenant"+t,Set.of("public"),true);
  try(var engine=new SearchEngine(Path.of(args[0]),auth,mode,profile)) {
   List<Integer> counts=new ArrayList<>();for(int t=0;t<3;t++)try(var lease=engine.generations("tenant"+t).acquire()){counts.add(lease.reader().numDocs());}
   if(!counts.equals(List.of(33334,33333,33333)))throw new AssertionError("100k committed counts changed");
   var response=engine.search("load0",new SearchEngine.Request("atomic publication",10,null,Map.of()));
   if(response.totalHits()!=33334||!response.scoringVersion().equals("bm25-content-v1"))throw new AssertionError("actual selected runtime mismatch");
   System.out.println("{\"phase\":\"unmeasured-prepared-index-revalidation\",\"tenantCounts\":"+counts+",\"documents\":100000,\"heapMaxBytes\":"+Runtime.getRuntime().maxMemory()+",\"rankingMode\":\""+mode+"\",\"rankingProfile\":\""+profile+"\",\"scoringVersion\":\""+response.scoringVersion()+"\",\"compiledModelSha256\":\""+RelevanceEvaluator.modelFingerprint()+"\",\"sanityQueries\":1,\"measuredRuns\":0}");
  }
 }
}
