package fixtures;
public final class SQL14Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}

public static void run()throws Exception{String input=source();String[] values=new String[1];values[0]=input;sink(values[0]);}
}
