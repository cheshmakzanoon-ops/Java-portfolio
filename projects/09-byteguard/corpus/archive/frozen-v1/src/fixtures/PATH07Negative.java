package fixtures;
public final class PATH07Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}
static String recursive(String s,int n){if(n<=0)return s;return recursive(s,n-1);}
public static void run()throws Exception{String input=source();sink(recursive(fixtures.Safe.confinedPath(input),3));}
}
