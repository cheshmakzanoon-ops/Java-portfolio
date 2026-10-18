from pathlib import Path
p=Path('projects/01-clearledger');base='io/github/cheshmakzanoon/portfolio/clearledger'
resource=p/'ledger-verification/src/main/resources/clearledger';resource.mkdir(parents=True,exist_ok=True);(resource/'source-audit.sql').write_text(Path('.work/A01/source-audit.sql').read_text())
(p/'ledger-verification/src/main/java'/base/'SourceAudit.java').write_text(Path('.work/A01/SourceAudit.java').read_text())
a=p/'ledger-verification/src/main/java'/base/'Workload.java';s=a.read_text();start=s.index('      var violations =\n');end=s.index('      if (!violations.isEmpty())',start);s=s[:start]+'      var violations = SourceAudit.journals(admin);\n'+s[end:];a.write_text(s)
a=p/'ledger-verification/src/test/java'/base/'LedgerPostgresIT.java';s=a.read_text();start=s.index('      var violations =\n',s.index('  void backupRestoreReauditsImmutableSource'));end=s.index('      assertTrue(violations.isEmpty());',start);s=s[:start]+'      var violations = SourceAudit.journals(restoredStore);\n'+s[end:];pos=s.index('  @Test\n  void backupRestoreReauditsImmutableSource');s=s[:pos]+Path('.work/A01/audit-test-method.txt').read_text()+s[pos:];a.write_text(s)
a=p/'demo/demo.py';s=a.read_text();start=s.index("cmd=['docker','compose'");end=s.index("assert subprocess.check_output(cmd,text=True)",start);s=s[:start]+'''audit_sql=(project/'ledger-verification/src/main/resources/clearledger/source-audit.sql').read_text().strip().rstrip(';')
cmd=['docker','compose','-f',str(project/'infra/compose.yaml'),'exec','-T','postgres','psql','-At','-v','ON_ERROR_STOP=1','-U','postgres','-d','clearledger','-c','SELECT count(*) FROM ('+audit_sql+') bad;']
'''+s[end:];a.write_text(s)
