package fixtures;
public final class SHELL17Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}

public static void run()throws Exception{String input=source();Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",input});}
}
