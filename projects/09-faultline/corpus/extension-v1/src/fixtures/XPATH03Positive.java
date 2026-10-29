package fixtures;
public final class XPATH03Positive {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String expression,javax.xml.xpath.XPath xpath,org.w3c.dom.Node node)throws Exception{xpath.evaluate(expression,node);}
public static void run(javax.xml.xpath.XPath xpath,org.w3c.dom.Node node)throws Exception{String input=source();StringBuilder b=new StringBuilder("//user[name='");b.append(input);b.append("']");sink(b.toString(),xpath,node);}
}
