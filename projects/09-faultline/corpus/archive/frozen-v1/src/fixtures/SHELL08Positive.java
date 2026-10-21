package fixtures;
public final class SHELL08Positive {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{Runtime.getRuntime().exec(new String[]{"/bin/sh","-c",text});}

public static void run()throws Exception{String input=source();String text=input;try{if(input.length()>2)throw new IllegalArgumentException();text=text+"suffix";}catch(IllegalArgumentException e){text=text+"fallback";}sink(text);}
}
