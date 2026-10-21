package fixtures;
public final class SQL14Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}

public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();String[] values=new String[1];values[0]=fixtures.Safe.sqlIdentifier(input);sink(values[0],statement);}
}
