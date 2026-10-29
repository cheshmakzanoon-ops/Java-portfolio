package fixtures;
public final class SQL17Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}

public static void run()throws Exception{String input=source();java.sql.Connection conn=null;java.sql.PreparedStatement p=conn.prepareStatement("select ?");p.setString(1,input);p.execute();}
}
