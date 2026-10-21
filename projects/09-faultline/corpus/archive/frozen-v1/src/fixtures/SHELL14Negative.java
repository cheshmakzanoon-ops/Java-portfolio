package fixtures;
public final class SHELL14Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}

public static void run()throws Exception{String input=source();String[] values=new String[1];values[0]=fixtures.Safe.shellToken(input);sink(values[0]);}
}
