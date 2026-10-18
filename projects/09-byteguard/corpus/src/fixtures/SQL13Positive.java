package fixtures;
public final class SQL13Positive {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}
static String field;
public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();field=input;sink(field,statement);}
}
