package fixtures;
public final class SHELL10Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c","/bin/echo "+text});}

public static void run()throws Exception{String input=source();sink("prefix-"+fixtures.Safe.shellToken(input)+"-suffix");}
}
