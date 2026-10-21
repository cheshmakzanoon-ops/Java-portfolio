package fixtures;
public final class SQL05Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}
static String route(String s){return s;}static String route(int n){return "fixed";}
public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();sink(route(fixtures.Safe.sqlIdentifier(input)),statement);}
}
