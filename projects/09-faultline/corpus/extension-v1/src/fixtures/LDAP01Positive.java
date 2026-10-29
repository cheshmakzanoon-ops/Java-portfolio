package fixtures;
public final class LDAP01Positive {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String filter,javax.naming.directory.DirContext context)throws Exception{context.search("ou=people",filter,new javax.naming.directory.SearchControls());}
public static void run(javax.naming.directory.DirContext context)throws Exception{String input=source();String filter=input;sink(filter,context);}
}
