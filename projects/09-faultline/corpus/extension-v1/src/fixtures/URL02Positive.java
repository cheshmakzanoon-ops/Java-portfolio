package fixtures;
public final class URL02Positive {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(java.net.URI uri){java.net.http.HttpRequest.newBuilder(uri);}
public static void run()throws Exception{String input=source();sink(java.net.URI.create(input));}
}
