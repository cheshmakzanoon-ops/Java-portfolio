package fixtures;
public final class SQL09Negative {
static String source(){return System.getenv("BYTEGUARD_INPUT");}
static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}
static String wide(long x,double y,String s){long[] a={x};a[0]++;return s+x+y+a[0];}
public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{String input=source();sink(wide(42L,2.5,fixtures.Safe.sqlIdentifier(input)),statement);}
}
