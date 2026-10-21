package fixtures;
public final class PATH05Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}
static String route(String s){return s;}static String route(int n){return "fixed";}
public static void run()throws Exception{String input=source();sink(Safe.confinedPath(route(input)));}
}
