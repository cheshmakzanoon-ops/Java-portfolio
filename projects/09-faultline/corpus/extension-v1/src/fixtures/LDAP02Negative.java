package fixtures;
public final class LDAP02Negative {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String base,javax.naming.directory.DirContext context)throws Exception{context.search(base,"(objectClass=*)",new javax.naming.directory.SearchControls());}
public static void run(javax.naming.directory.DirContext context)throws Exception{String input=source();sink(input,context);}
}
