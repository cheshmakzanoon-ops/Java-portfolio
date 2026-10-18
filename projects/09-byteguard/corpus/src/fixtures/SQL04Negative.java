package fixtures;
public final class SQL04Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}

public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();String text=switch((int)(System.nanoTime()%3)){case 0 -> fixtures.Safe.sqlIdentifier(input);case 1 -> "fixed";default -> "other";};sink(text,statement);}
}
