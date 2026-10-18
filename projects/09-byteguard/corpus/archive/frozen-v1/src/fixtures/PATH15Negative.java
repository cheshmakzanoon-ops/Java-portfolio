package fixtures;
public final class PATH15Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}

public static void run()throws Exception{String input=source();StringBuilder builder=new StringBuilder("prefix");builder.append(fixtures.Safe.confinedPath(input));sink(builder.toString());}
}
