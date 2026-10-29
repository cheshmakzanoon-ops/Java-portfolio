package fixtures;
public final class CLASS04Positive {
static String source(){return System.getenv("EXT_INPUT");}
static String pick(String value){return value;}
static void sink(String name)throws Exception{ClassLoader.getSystemClassLoader().loadClass(name);}
public static void run()throws Exception{String input=source();sink(pick(input));}
}
