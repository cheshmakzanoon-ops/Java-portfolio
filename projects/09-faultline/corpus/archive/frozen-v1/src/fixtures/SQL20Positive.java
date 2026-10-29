package fixtures;
public final class SQL20Positive {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}

public static void run()throws Exception{String input=source();Object text=input;sink((String)text);}
}
