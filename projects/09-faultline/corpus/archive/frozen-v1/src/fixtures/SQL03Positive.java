package fixtures;
public final class SQL03Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}

public static void run()throws Exception{String input=source();String text=input;for(int i=0;i<3;i++){text=text+"x";}sink(text);}
}
