package fixtures;
public final class SHELL06Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c","/bin/echo "+text});}
interface Pass {String pass(String s);}static class Identity implements Pass {public String pass(String s){return s;}}
public static void run()throws Exception{String input=source();Pass pass=new Identity();sink(pass.pass(fixtures.Safe.shellToken(input)));}
}
