package fixtures;
public final class SHELL12Positive {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c","/bin/echo "+text});}

public static void run()throws Exception{String input=source();java.util.List<String> list=new java.util.ArrayList<>();list.add(input);sink(list.get(0));}
}
