package fixtures;
public final class WEB03Positive {
static void sink(String expression,javax.xml.xpath.XPath xpath,org.w3c.dom.Node node)throws Exception{xpath.evaluate(expression,node);}
public static void run(javax.servlet.ServletRequest request,javax.xml.xpath.XPath xpath,org.w3c.dom.Node node)throws Exception{sink("//user[@id='"+request.getParameter("id")+"']",xpath,node);}
}
