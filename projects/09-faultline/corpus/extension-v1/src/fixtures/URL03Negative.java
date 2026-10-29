package fixtures;
public final class URL03Negative {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(int port)throws Exception{new java.net.Socket("api.example.org",port);}
public static void run()throws Exception{String input=source();sink(Integer.parseInt(input));}
}
