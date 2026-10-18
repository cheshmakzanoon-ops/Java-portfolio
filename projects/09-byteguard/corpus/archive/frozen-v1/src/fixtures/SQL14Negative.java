package fixtures;
public final class SQL14Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}

public static void run()throws Exception{String input=source();String[] values=new String[1];values[0]=fixtures.Safe.sqlIdentifier(input);sink(values[0]);}
}
