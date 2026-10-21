package fixtures;
public final class SQL07Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}
static String recursive(String s,int n){if(n<=0)return s;return recursive(s,n-1);}
public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();sink(recursive(fixtures.Safe.sqlIdentifier(input),3),statement);}
}
