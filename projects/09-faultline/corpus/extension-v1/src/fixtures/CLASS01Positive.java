package fixtures;
public final class CLASS01Positive {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String name)throws Exception{Class.forName(name);}
public static void run()throws Exception{String input=source();String name=input;sink(name);}
}
