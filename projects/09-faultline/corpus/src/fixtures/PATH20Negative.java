package fixtures;
public final class PATH20Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}

public static void run()throws Exception{String input=source();Object text=input;sink(Safe.confinedPath((String)text));}
}
