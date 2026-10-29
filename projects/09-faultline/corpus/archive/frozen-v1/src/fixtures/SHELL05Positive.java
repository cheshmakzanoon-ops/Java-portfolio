package fixtures;
public final class SHELL05Positive {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}
static String route(String s){return s;}static String route(int n){return "fixed";}
public static void run()throws Exception{String input=source();sink(route(input));}
}
