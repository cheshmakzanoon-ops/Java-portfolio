import java.sql.*;
public class LockProbe {
 public static void main(String[] args)throws Exception{
  for(String lock:new String[]{"FOR UPDATE","FOR NO KEY UPDATE",""}){
   try(Connection owner=DriverManager.getConnection(args[0],"probe","local-lockprobe-only")){
    try(Statement s=owner.createStatement()){
     s.execute("DROP TABLE IF EXISTS audit, membership, tenant CASCADE");
     s.execute("CREATE TABLE tenant(id text PRIMARY KEY, epoch bigint); CREATE TABLE membership(id text REFERENCES tenant); CREATE TABLE audit(id text REFERENCES tenant)");
     s.execute("INSERT INTO tenant VALUES('harbor',1); INSERT INTO membership VALUES('harbor')");
    }
    try(Connection a=DriverManager.getConnection(args[0],"probe","local-lockprobe-only");Connection b=DriverManager.getConnection(args[0],"probe","local-lockprobe-only")){
     a.setAutoCommit(false);a.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
     b.setAutoCommit(false);b.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
     try(Statement s=a.createStatement();ResultSet r=s.executeQuery("SELECT epoch FROM tenant WHERE id='harbor'")){r.next();System.out.println(lock+" snapshot="+r.getLong(1));}
     try(Statement s=b.createStatement()){
      s.executeQuery("SELECT epoch FROM tenant WHERE id='harbor' "+lock).close();
      s.executeUpdate("UPDATE tenant SET epoch=epoch+1 WHERE id='harbor'");
     }
     b.commit();
     try(Statement s=a.createStatement()){
      s.executeUpdate("INSERT INTO audit VALUES('harbor')");a.commit();System.out.println("old audit COMMITTED");
     }catch(SQLException e){System.out.println("old audit FAILED "+e.getSQLState()+" "+e.getMessage());a.rollback();}
    }
   }
  }
 }
}
