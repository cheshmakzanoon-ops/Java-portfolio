package fixtures;
public final class SHELL11Positive {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c","/bin/echo "+text});}
static String identity(String s){return s;}
public static void run()throws Exception{String input=source();java.util.function.Function<String,String> f=SHELL11Positive::identity;sink(f.apply(input));}
}
