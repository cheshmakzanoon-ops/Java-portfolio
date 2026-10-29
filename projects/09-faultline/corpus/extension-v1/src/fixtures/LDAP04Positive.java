package fixtures;
public final class LDAP04Positive {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String filter,javax.naming.directory.InitialDirContext context)throws Exception{context.search("ou=people",filter,new javax.naming.directory.SearchControls());}
public static void run(javax.naming.directory.InitialDirContext context)throws Exception{String input=source();sink("(cn="+input+")",context);}
}
