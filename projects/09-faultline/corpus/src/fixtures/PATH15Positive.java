package fixtures;
public final class PATH15Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}

public static void run()throws Exception{String input=source();StringBuilder builder=new StringBuilder("prefix");builder.append(input);sink(builder.toString());}
}
