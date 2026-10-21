package fixtures;
public final class PATH11Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}
static String identity(String s){return s;}
public static void run()throws Exception{String input=source();java.util.function.Function<String,String> f=PATH11Positive::identity;sink(f.apply(input));}
}
