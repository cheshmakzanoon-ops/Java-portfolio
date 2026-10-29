package fixtures;
public final class SQL02Positive {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}

public static void run()throws Exception{String input=source();String text;if(System.nanoTime()>0)text=input;else text="fixed";sink(text);}
}
