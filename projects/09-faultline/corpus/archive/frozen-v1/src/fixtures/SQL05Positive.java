package fixtures;
public final class SQL05Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}
static String route(String s){return s;}static String route(int n){return "fixed";}
public static void run()throws Exception{String input=source();sink(route(input));}
}
