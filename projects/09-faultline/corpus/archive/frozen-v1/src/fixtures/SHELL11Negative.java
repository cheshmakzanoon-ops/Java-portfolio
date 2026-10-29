package fixtures;
public final class SHELL11Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}
static String identity(String s){return s;}
public static void run()throws Exception{String input=source();java.util.function.Function<String,String> f=SHELL11Negative::identity;sink(f.apply(fixtures.Safe.shellToken(input)));}
}
