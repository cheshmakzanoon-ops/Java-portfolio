package fixtures;
public final class SHELL16Positive {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}

public static void run()throws Exception{String input=source();sink(Safe.confinedPath(input));}
}
