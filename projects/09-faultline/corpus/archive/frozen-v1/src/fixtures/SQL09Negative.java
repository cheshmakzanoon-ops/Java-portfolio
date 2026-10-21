package fixtures;
public final class SQL09Negative {
static String source(){return System.getenv("FAULTLINE_INPUT");}
static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}
static String wide(long x,double y,String s){long[] a={x};a[0]++;return s+x+y+a[0];}
public static void run()throws Exception{String input=source();sink(wide(42L,2.5,fixtures.Safe.sqlIdentifier(input)));}
}
