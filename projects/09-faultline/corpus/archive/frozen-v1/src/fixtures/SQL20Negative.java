package fixtures;
public final class SQL20Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}

public static void run()throws Exception{String input=source();Object text=fixtures.Safe.sqlIdentifier(input);sink((String)text);}
}
