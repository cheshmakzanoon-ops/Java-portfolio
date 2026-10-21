package fixtures;
public final class PATH04Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}

public static void run()throws Exception{String input=source();String text=switch((int)(System.nanoTime()%3)){case 0 -> input;case 1 -> "fixed";default -> "other";};sink(text);}
}
