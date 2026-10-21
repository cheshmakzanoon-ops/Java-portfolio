package fixtures;
public final class SHELL09Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}
static String wide(long x,double y,String s){long[] a={x};a[0]++;return s+x+y+a[0];}
public static void run()throws Exception{String input=source();sink(wide(42L,2.5,fixtures.Safe.shellToken(input)));}
}
