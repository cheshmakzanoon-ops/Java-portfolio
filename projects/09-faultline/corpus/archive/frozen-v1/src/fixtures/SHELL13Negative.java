package fixtures;
public final class SHELL13Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}
static String field;
public static void run()throws Exception{String input=source();field=fixtures.Safe.shellToken(input);sink(field);}
}
