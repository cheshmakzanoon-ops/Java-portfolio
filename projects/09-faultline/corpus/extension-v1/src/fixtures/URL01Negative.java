package fixtures;
public final class URL01Negative {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String url)throws Exception{new java.net.URL(url);}
public static void run()throws Exception{String input=source();sink(SafeExtension.httpUrl(input));}
}
