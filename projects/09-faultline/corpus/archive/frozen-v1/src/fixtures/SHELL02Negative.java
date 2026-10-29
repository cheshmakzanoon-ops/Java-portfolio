package fixtures;
public final class SHELL02Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}

public static void run()throws Exception{String input=source();String text;if(System.nanoTime()>0)text=fixtures.Safe.shellToken(input);else text="fixed";sink(text);}
}
