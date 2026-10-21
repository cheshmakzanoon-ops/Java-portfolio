package fixtures;
public final class PATH02Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}

public static void run()throws Exception{String input=source();String text;if(System.nanoTime()>0)text=input;else text="fixed";sink(text);}
}
