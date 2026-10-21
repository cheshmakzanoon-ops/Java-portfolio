package fixtures;
public final class SQL03Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}

public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();String text=fixtures.Safe.sqlIdentifier(input);for(int i=0;i<3;i++){text=text+"x";}sink(text,statement);}
}
