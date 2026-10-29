package fixtures;
public final class URL04Positive {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String host,int port)throws Exception{new java.net.Socket(host,port);}
public static void run()throws Exception{String input=source();String host=System.nanoTime()>0?input:"localhost";sink(host,80);}
}
