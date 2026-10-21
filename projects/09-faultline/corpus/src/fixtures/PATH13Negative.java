package fixtures;
public final class PATH13Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}
static String field;
public static void run()throws Exception{String input=source();field=input;sink(Safe.confinedPath(field));}
}
