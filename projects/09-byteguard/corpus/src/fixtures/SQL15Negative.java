package fixtures;
public final class SQL15Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}

public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();StringBuilder builder=new StringBuilder("prefix");builder.append(fixtures.Safe.sqlIdentifier(input));sink(builder.toString(),statement);}
}
