package fixtures;
public final class XPATH04Positive {
static String source(){return System.getenv("EXT_INPUT");}
static Object sink(String expression,javax.xml.xpath.XPath xpath,org.w3c.dom.Node node)throws Exception{return xpath.evaluate(expression,node,javax.xml.xpath.XPathConstants.NODE);}
public static void run(javax.xml.xpath.XPath xpath,org.w3c.dom.Node node)throws Exception{String input=source();sink("//item[@id="+input+"]",xpath,node);}
}
