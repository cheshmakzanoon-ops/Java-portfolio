package fixtures;
public final class SQL19Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}

public static void run()throws Exception{String input=source();sink(fixtures.Safe.sqlIdentifier(input));}
}
