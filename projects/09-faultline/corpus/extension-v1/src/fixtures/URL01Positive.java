package fixtures;
public final class URL01Positive {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String url)throws Exception{new java.net.URL(url);}
public static void run()throws Exception{String input=source();String url=input;sink(url);}
}
