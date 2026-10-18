package fixtures;
public final class SQL05Positive {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}
static String route(String s){return s;}static String route(int n){return "fixed";}
public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();sink(route(input),statement);}
}
