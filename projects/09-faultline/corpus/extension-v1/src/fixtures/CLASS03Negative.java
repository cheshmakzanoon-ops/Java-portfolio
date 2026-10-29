package fixtures;
public final class CLASS03Negative {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String name)throws Exception{Class.forName("java.lang.String");System.out.println(name);}
public static void run()throws Exception{String input=source();sink(input);}
}
