package fixtures;
public final class SQL18Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}

public static void run()throws Exception{String input=source();sink(Missing.identity(fixtures.Safe.sqlIdentifier(input)));}
}
