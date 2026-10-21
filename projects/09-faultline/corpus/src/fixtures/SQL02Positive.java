package fixtures;
public final class SQL02Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}

public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();String text;if(System.nanoTime()>0)text=input;else text="fixed";sink(text,statement);}
}
