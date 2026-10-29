package fixtures;
public final class SQL08Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}

public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();String text=fixtures.Safe.sqlIdentifier(input);try{if(input.length()>2)throw new IllegalArgumentException();text=text+"suffix";}catch(IllegalArgumentException e){text=text+"fallback";}sink(text,statement);}
}
