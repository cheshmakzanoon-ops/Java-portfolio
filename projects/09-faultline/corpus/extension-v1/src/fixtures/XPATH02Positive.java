package fixtures;
public final class XPATH02Positive {
static String source(){return System.getenv("EXT_INPUT");}
static void sink(String expression,javax.xml.xpath.XPath xpath)throws Exception{xpath.compile(expression);}
public static void run(javax.xml.xpath.XPath xpath)throws Exception{String input=source();String expression;if(System.nanoTime()>0)expression=input;else expression="/root";sink(expression,xpath);}
}
