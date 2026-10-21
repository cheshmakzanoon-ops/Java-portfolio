package fixtures;
public final class SHELL20Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}

public static void run()throws Exception{String input=source();Object text=fixtures.Safe.shellToken(input);sink((String)text);}
}
