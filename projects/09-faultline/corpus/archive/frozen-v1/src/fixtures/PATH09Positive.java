package fixtures;
public final class PATH09Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}
static String wide(long x,double y,String s){long[] a={x};a[0]++;return s+x+y+a[0];}
public static void run()throws Exception{String input=source();sink(wide(42L,2.5,input));}
}
