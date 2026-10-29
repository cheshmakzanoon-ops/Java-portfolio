package fixtures;
public final class LDAP03Negative {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(javax.naming.directory.DirContext context)throws Exception{context.search("ou=people","(objectClass=person)",new javax.naming.directory.SearchControls());}
public static void run(javax.naming.directory.DirContext context)throws Exception{String input=source();sink(context);}
}
