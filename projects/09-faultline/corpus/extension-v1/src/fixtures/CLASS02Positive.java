package fixtures;
public final class CLASS02Positive {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String name)throws Exception{Class.forName(name);}
public static void run()throws Exception{String input=source();String name;if(System.nanoTime()>0)name=input;else name="java.lang.String";sink(name);}
}
