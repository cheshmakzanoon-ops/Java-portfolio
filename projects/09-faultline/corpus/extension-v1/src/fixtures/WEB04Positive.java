package fixtures;
public final class WEB04Positive {
static void sink(String filter,javax.naming.directory.DirContext context)throws Exception{context.search("ou=people",filter,new javax.naming.directory.SearchControls());}
public static void run(jakarta.servlet.http.HttpServletRequest request,javax.naming.directory.DirContext context)throws Exception{sink("(uid="+request.getQueryString()+")",context);}
}
