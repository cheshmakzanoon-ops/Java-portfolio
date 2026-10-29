package fixtures;
public final class XPATH03Negative {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String item,javax.xml.xpath.XPath xpath)throws Exception{xpath.evaluate("//user",(Object)item);}
public static void run(javax.xml.xpath.XPath xpath)throws Exception{String input=source();sink(input,xpath);}
}
