WITH totals AS (
 SELECT j.tenant_id,j.id AS journal_id,count(p.line_no) AS posting_count,
        coalesce(sum(CASE p.side WHEN 'DEBIT' THEN p.amount ELSE 0 END),0) AS debits,
        coalesce(sum(CASE p.side WHEN 'CREDIT' THEN p.amount ELSE 0 END),0) AS credits,
        bool_or(p.amount<=0 OR p.amount>999999999999999999999999999999
                OR p.side NOT IN('DEBIT','CREDIT') OR a.currency IS DISTINCT FROM j.currency) AS invalid_line,
        j.reverses,j.effective_date,r.id AS matched_period_id,r.start_date,r.end_date
 FROM ledger.journal j
 LEFT JOIN ledger.accounting_period r ON r.tenant_id=j.tenant_id AND r.id=j.period_id
 LEFT JOIN ledger.posting p ON p.tenant_id=j.tenant_id AND p.journal_id=j.id
 LEFT JOIN ledger.account a ON a.tenant_id=p.tenant_id AND a.id=p.account_id
 GROUP BY j.tenant_id,j.id,j.effective_date,r.id,r.start_date,r.end_date
)
SELECT tenant_id,journal_id,posting_count,debits,credits
FROM totals t
WHERE posting_count NOT BETWEEN 2 AND 100 OR debits<>credits OR debits<=0
   OR debits>999999999999999999999999999999 OR invalid_line
   OR matched_period_id IS NULL OR effective_date<start_date OR effective_date>end_date
   OR (reverses IS NOT NULL AND EXISTS (
        (SELECT account_id,side,amount FROM ledger.posting WHERE tenant_id=t.tenant_id AND journal_id=t.journal_id
         EXCEPT ALL SELECT account_id,CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,amount FROM ledger.posting WHERE tenant_id=t.tenant_id AND journal_id=t.reverses)
        UNION ALL
        (SELECT account_id,CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,amount FROM ledger.posting WHERE tenant_id=t.tenant_id AND journal_id=t.reverses
         EXCEPT ALL SELECT account_id,side,amount FROM ledger.posting WHERE tenant_id=t.tenant_id AND journal_id=t.journal_id)
      ))
ORDER BY tenant_id,journal_id
