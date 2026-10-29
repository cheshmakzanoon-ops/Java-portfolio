package fixtures;
public final class XPATH02Negative {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(javax.xml.xpath.XPath xpath)throws Exception{xpath.compile("/root/item");}
public static void run(javax.xml.xpath.XPath xpath)throws Exception{String input=source();sink(xpath);}
}
