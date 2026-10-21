package fixtures;
public final class PATH06Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.nio.file.Files.readString(java.nio.file.Path.of(text));}
interface Pass {String pass(String s);}static class Identity implements Pass {public String pass(String s){return s;}}
public static void run()throws Exception{String input=source();Pass pass=new Identity();sink(Safe.confinedPath(pass.pass(input)));}
}
