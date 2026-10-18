package fixtures;
public final class SQL06Positive {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}
interface Pass {String pass(String s);}static class Identity implements Pass {public String pass(String s){return s;}}
public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();Pass pass=new Identity();sink(pass.pass(input),statement);}
}
