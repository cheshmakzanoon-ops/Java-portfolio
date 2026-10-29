package fixtures;
public final class URL03Positive {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String suffix){java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create("https://"+suffix));}
public static void run()throws Exception{String input=source();sink(input);}
}
