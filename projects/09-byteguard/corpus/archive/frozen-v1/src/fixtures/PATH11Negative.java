package fixtures;
public final class PATH11Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}
static String identity(String s){return s;}
public static void run()throws Exception{String input=source();java.util.function.Function<String,String> f=PATH11Negative::identity;sink(f.apply(fixtures.Safe.confinedPath(input)));}
}
