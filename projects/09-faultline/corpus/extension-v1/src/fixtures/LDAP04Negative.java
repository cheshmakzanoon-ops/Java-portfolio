package fixtures;
public final class LDAP04Negative {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String filter,javax.naming.directory.DirContext context)throws Exception{context.search("ou=people",filter,new javax.naming.directory.SearchControls());}
public static void run(javax.naming.directory.DirContext context)throws Exception{String input=source();String filter=input;filter="(objectClass=person)";sink(filter,context);}
}
