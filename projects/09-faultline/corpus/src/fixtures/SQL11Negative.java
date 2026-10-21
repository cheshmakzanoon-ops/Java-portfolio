package fixtures;
public final class SQL11Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}
static String identity(String s){return s;}
public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();java.util.function.Function<String,String> f=SQL11Negative::identity;sink(f.apply(fixtures.Safe.sqlIdentifier(input)),statement);}
}
