package fixtures;
public final class SHELL18Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}

public static void run()throws Exception{String input=source();sink(Missing.identity(fixtures.Safe.shellToken(input)));}
}
