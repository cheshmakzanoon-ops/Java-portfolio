from pathlib import Path
p=Path('projects/01-clearledger');base='io/github/cheshmakzanoon/portfolio/clearledger'
f=p/'ledger-domain/src/main/java'/base/'Money.java';s=f.read_text().replace('if (!SCALES.containsKey(currency))','if (currency == null || !SCALES.containsKey(currency))').replace('if (decimal == null','if (currency == null || decimal == null || decimal.length() > 64');f.write_text(s)
f=p/'ledger-domain/src/test/java'/base/'AccountingProperties.java';s=f.read_text().replace('assertEquals("1.20", Money.parse("CAD", "1.20").decimal());','assertEquals("1.20", Money.parse("CAD", "1.20").decimal());\n assertEquals("12.34", Money.parse("CAD", "12.34").decimal());\n assertEquals("0.00", Money.parse("CAD", "-0.00").decimal());\n assertThrows(IllegalArgumentException.class, () -> Money.parse("CAD", "1".repeat(100000)));');f.write_text(s)
f=p/'ledger-persistence/pom.xml';s=f.read_text().replace('<dependencies>','<dependencies><dependency><groupId>com.zaxxer</groupId><artifactId>HikariCP</artifactId></dependency>');f.write_text(s)
f=p/'ledger-persistence/src/main/java'/base/'LedgerStore.java';s=f.read_text().replace('public final class LedgerStore {','public final class LedgerStore implements AutoCloseable {\n private final com.zaxxer.hikari.HikariDataSource pool;');start=s.index('  public LedgerStore(');end=s.index('\n  public static void migrate',start)
s=s[:start]+''' public LedgerStore(String url,String user,String password) {this(url,user,password,32);}
 public LedgerStore(String url,String user,String password,int poolSize) {
 this.url=url;this.user=user;this.password=password;
 var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url);config.setUsername(user);config.setPassword(password);config.setMaximumPoolSize(poolSize);config.setMinimumIdle(0);config.setConnectionTimeout(15000);config.setInitializationFailTimeout(-1);config.setPoolName("clearledger-"+UUID.randomUUID());pool=new com.zaxxer.hikari.HikariDataSource(config);
 }
 public void close(){pool.close();}
''' + s[end:];s=s.replace('DriverManager.getConnection(url, user, password)','pool.getConnection()');f.write_text(s)
