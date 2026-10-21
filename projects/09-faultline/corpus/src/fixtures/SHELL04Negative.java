package fixtures;
public final class SHELL04Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c","/bin/echo "+text});}

public static void run()throws Exception{String input=source();String text=switch((int)(System.nanoTime()%3)){case 0 -> fixtures.Safe.shellToken(input);case 1 -> "fixed";default -> "other";};sink(text);}
}
