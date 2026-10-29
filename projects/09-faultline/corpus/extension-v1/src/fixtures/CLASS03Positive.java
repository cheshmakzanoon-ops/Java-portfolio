package fixtures;
public final class CLASS03Positive {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String name)throws Exception{Class.forName(name,true,ClassLoader.getSystemClassLoader());}
public static void run()throws Exception{String input=source();sink("com.example.plugins."+input);}
}
