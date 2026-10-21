package fixtures;
public final class SQL13Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}
static String field;
public static void run()throws Exception{String input=source();field=fixtures.Safe.sqlIdentifier(input);sink(field);}
}
