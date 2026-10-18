package fixtures;
public final class SHELL06Positive {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}
interface Pass {String pass(String s);}static class Identity implements Pass {public String pass(String s){return s;}}
public static void run()throws Exception{String input=source();Pass pass=new Identity();sink(pass.pass(input));}
}
