package fixtures;
public final class SQL11Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}
static String identity(String s){return s;}
public static void run()throws Exception{String input=source();java.util.function.Function<String,String> f=SQL11Positive::identity;sink(f.apply(input));}
}
