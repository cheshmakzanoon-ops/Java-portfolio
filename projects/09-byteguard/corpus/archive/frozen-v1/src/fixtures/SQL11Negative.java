package fixtures;
public final class SQL11Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}
static String identity(String s){return s;}
public static void run()throws Exception{String input=source();java.util.function.Function<String,String> f=SQL11Negative::identity;sink(f.apply(fixtures.Safe.sqlIdentifier(input)));}
}
