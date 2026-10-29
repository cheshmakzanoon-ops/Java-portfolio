package fixtures;
public final class CLASS01Negative {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String name)throws Exception{Class.forName(name);}
public static void run()throws Exception{String input=source();sink(SafeExtension.className(input));}
}
