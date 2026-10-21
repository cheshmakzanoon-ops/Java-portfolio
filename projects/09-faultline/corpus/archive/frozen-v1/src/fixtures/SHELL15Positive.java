package fixtures;
public final class SHELL15Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}

public static void run()throws Exception{String input=source();StringBuilder builder=new StringBuilder("prefix");builder.append(input);sink(builder.toString());}
}
