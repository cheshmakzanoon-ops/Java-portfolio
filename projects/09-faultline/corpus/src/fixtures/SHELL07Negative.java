package fixtures;
public final class SHELL07Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c","/bin/echo "+text});}
static String recursive(String s,int n){if(n<=0)return s;return recursive(s,n-1);}
public static void run()throws Exception{String input=source();sink(recursive(fixtures.Safe.shellToken(input),3));}
}
