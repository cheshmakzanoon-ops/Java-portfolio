from pathlib import Path
import json
p=Path('projects/01-clearledger');base='io/github/cheshmakzanoon/portfolio/clearledger'
f=p/'evidence/integration.json';d=json.loads(f.read_text());d['acceptanceMapping']['P01-A1']['status']='IN_PROGRESS';d['acceptanceMapping']['P01-A1']['remaining']='Subsequent review found SQLNULL batch bypass in V1 privileged function; historical13IT did not cover SQLNULL. V2 fix/regressions pending; historical passing scenarios retained, not final acceptance.';f.write_text(json.dumps(d,indent=2)+'\n')
# Append immutable migration rather than change already-committed V1 history.
v1=(p/'ledger-persistence/src/main/resources/db/migration/V1__ledger.sql').read_text();start=v1.index('CREATE FUNCTION ledger.post_journal');end=v1.index('CREATE FUNCTION ledger.close_period',start);posting=v1[start:end].replace('CREATE FUNCTION ledger.post_journal','CREATE OR REPLACE FUNCTION ledger.post_journal',1);posting=posting.replace("IF jsonb_typeof(ls)<>'array' OR jsonb_array_length(ls) NOT BETWEEN 2 AND 100 THEN RAISE EXCEPTION 'BATCH_BOUND' USING ERRCODE='P0422';END IF;","IF ls IS NULL OR jsonb_typeof(ls) IS DISTINCT FROM 'array' THEN RAISE EXCEPTION 'BATCH_BOUND' USING ERRCODE='P0422';END IF;\n IF jsonb_array_length(ls) NOT BETWEEN 2 AND 100 THEN RAISE EXCEPTION 'BATCH_BOUND' USING ERRCODE='P0422';END IF;")
assert 'ls IS NULL' in posting
backstop='''
CREATE FUNCTION ledger.assert_journal_invariants() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger AS $$
DECLARE n bigint; debit numeric; credit numeric;
BEGIN
 SELECT count(*),coalesce(sum(CASE side WHEN 'DEBIT' THEN amount ELSE 0 END),0),coalesce(sum(CASE side WHEN 'CREDIT' THEN amount ELSE 0 END),0) INTO n,debit,credit FROM ledger.posting WHERE tenant_id=NEW.tenant_id AND journal_id=NEW.id;
 IF n NOT BETWEEN 2 AND 100 OR debit<>credit OR debit<=0 OR debit>999999999999999999999999999999 OR EXISTS(SELECT FROM ledger.posting p JOIN ledger.account a ON a.tenant_id=p.tenant_id AND a.id=p.account_id WHERE p.tenant_id=NEW.tenant_id AND p.journal_id=NEW.id AND a.currency<>NEW.currency) THEN RAISE EXCEPTION 'POSTING_INVARIANT_BACKSTOP' USING ERRCODE='P0422';END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ledger.assert_journal_invariants() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER journal_invariants AFTER INSERT ON ledger.journal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.assert_journal_invariants();
DO $$ BEGIN
 IF EXISTS(SELECT FROM ledger.journal j LEFT JOIN ledger.posting p ON p.tenant_id=j.tenant_id AND p.journal_id=j.id GROUP BY j.tenant_id,j.id HAVING count(p.line_no) NOT BETWEEN 2 AND 100 OR coalesce(sum(CASE p.side WHEN 'DEBIT' THEN p.amount ELSE -p.amount END),0)<>0) THEN RAISE EXCEPTION 'PREEXISTING_POSTING_INVARIANT_VIOLATIONS: stop and investigate; no history is rewritten';END IF;
END $$;
'''
(p/'ledger-persistence/src/main/resources/db/migration/V2__null_safe_posting_and_deferred_audit.sql').write_text('-- V1 remains immutable. Reject SQLNULL before JSON predicates and audit every journal at commit.\n'+posting+backstop)
# Real HTTP crash boundary.
(p/'ledger-verification/src/main/java'/base/'CrashWriter.java').write_text(Path('.work/A01/CrashWriter-http.java').read_text())
f=p/'ledger-verification/src/test/java'/base/'LedgerPostgresIT.java';s=f.read_text();a=s.index('  @Test\n  void actualProcessTerminationBeforeAndAfterCommit()');b=s.index('  @Test',a+10);s=s[:a]+Path('.work/A01/http-test-method.txt').read_text()+s[b:];f.write_text(s)
# Separate engines so Surefire XML cannot overwrite reports.
f=p/'ledger-domain/src/test/java'/base/'AccountingProperties.java';s=f.read_text();a=s.index('  @Test');jupiter=s[a:s.rfind('}')];prefix=s[:a];const=prefix[prefix.index('class AccountingProperties {')+len('class AccountingProperties {'):prefix.index('  @Property')];f.write_text(prefix+'}\n');(f.parent/'AccountingTest.java').write_text('package io.github.cheshmakzanoon.portfolio.clearledger;\nimport java.math.*;import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;\nclass AccountingTest {\n'+const+jupiter+'}\n')
# Root POM is read-only; install its artifact into this project's private cache without a root build.
f=p/'scripts/common.sh';s=f.read_text().replace(' "${MVN[@]}" -f "$PROJECT/ledger-verification/pom.xml" dependency:copy-dependencies', ' "${MVN[@]}" -f "$PROJECT/pom.xml" -N org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file -Dfile="$ROOT/pom.xml" -DpomFile="$ROOT/pom.xml" -DgeneratePom=false\n "${MVN[@]}" -f "$PROJECT/ledger-verification/pom.xml" dependency:copy-dependencies');f.write_text(s)
# Fixture coverage includes every account, with exact same-currency successors.
f=p/'ledger-verification/src/main/java'/base/'Workload.java';s=f.read_text().replace('int t = random.nextInt(20), currency = random.nextInt(3);\n        int a = random.nextInt(166) * 3 + currency, b = random.nextInt(166) * 3 + currency;\n        if (a == b) b = (b + 3) % 498;','''int t,currency,a,b;
        if(i<10000){t=i%20;a=i/20;currency=a%3;int cardinality=(499-currency)/3+1;b=(((a-currency)/3+1)%cardinality)*3+currency;}
        else{t=random.nextInt(20);currency=random.nextInt(3);int cardinality=(499-currency)/3+1;a=random.nextInt(cardinality)*3+currency;b=random.nextInt(cardinality)*3+currency;if(a==b)b=(((a-currency)/3+1)%cardinality)*3+currency;}''');s=s.replace('var expectedBalances = oracle.balances();','''var expectedBalances = new HashMap<UUID,BigInteger>();for(int t=0;t<20;t++)for(int account=0;account<500;account++)expectedBalances.put(id("account-"+t+"-"+account),BigInteger.ZERO);expectedBalances.putAll(oracle.balances());''');a=s.index('          if (signed.signum()');b=s.index('\n        }',a);s=s[:a]+'          actual.put(UUID.fromString(row.get("id")),signed);'+s[b:];s=s.replace('result.put("accounts", 10000);','result.put("accounts", 10000);\n      result.put("accountsTouched",oracle.balances().size());\n      result.put("tenantsTouched",intents.stream().map(Intent::tenant).distinct().count());');f.write_text(s)
