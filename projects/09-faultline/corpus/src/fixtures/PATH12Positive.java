package fixtures;
public final class PATH12Positive {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}

public static void run()throws Exception{String input=source();java.util.List<String> list=new java.util.ArrayList<>();list.add(input);sink(list.get(0));}
}
