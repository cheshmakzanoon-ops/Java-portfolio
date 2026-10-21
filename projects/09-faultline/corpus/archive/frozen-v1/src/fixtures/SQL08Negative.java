package fixtures;
public final class SQL08Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}

public static void run()throws Exception{String input=source();String text=fixtures.Safe.sqlIdentifier(input);try{if(input.length()>2)throw new IllegalArgumentException();text=text+"suffix";}catch(IllegalArgumentException e){text=text+"fallback";}sink(text);}
}
