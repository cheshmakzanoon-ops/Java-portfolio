package fixtures;
public final class PATH14Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}

public static void run()throws Exception{String input=source();String[] values=new String[1];values[0]=input;sink(values[0]);}
}
