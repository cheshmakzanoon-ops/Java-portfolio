package io.github.cheshmakzanoon.portfolio.clearledger;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only diagnostics; administrator-corrupted source is outside normal tenant permissions. */
public final class SourceAudit {
  private static final String SQL = load();
  private SourceAudit() {}
  private static String load() {
    try(var input=SourceAudit.class.getResourceAsStream("/clearledger/source-audit.sql")) {
      if(input==null)throw new IllegalStateException("SOURCE_AUDIT_RESOURCE_MISSING");
      return new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
  }
  public static List<Map<String,String>> journals(LedgerStore store) {
    return store.tx(c->LedgerStore.query(c,SQL));
  }
}
