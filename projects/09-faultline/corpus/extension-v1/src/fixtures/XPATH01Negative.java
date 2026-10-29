package fixtures;
public final class XPATH01Negative {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String expression,javax.xml.xpath.XPath xpath,org.w3c.dom.Node node)throws Exception{xpath.evaluate(expression,node);}
public static void run(javax.xml.xpath.XPath xpath,org.w3c.dom.Node node)throws Exception{String input=source();sink("//user[name='"+SafeExtension.xpathLiteral(input)+"']",xpath,node);}
}
