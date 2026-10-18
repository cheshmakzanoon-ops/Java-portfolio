package fixtures;
public final class SQL04Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}

public static void run()throws Exception{String input=source();String text=switch((int)(System.nanoTime()%3)){case 0 -> fixtures.Safe.sqlIdentifier(input);case 1 -> "fixed";default -> "other";};sink(text);}
}
