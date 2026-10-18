package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.IOException;
import java.util.*;

/** Executable identifier components use the existing analyzer and bounded128-term member quota. */
public final class SourceAnchors {
  private SourceAnchors() {}
  public static Set<String> terms(Collection<String> identifiers)throws IOException {
    Set<String> out=new TreeSet<>();
    for(String identifier:identifiers) {
      for(var part:CodeIdentifierAnalyzer.parts(identifier)) {
        // One identifier is at most256characters; the existing code analyzer preserves original
        // components without introducing a vocabulary/catalog entry for a fixture word.
        out.addAll(SourceApiCatalog.terms(part.term()));
        if(out.size()>128)throw new Quota();
      }
    }
    return Collections.unmodifiableSet(out);
  }
  public static SourceFacts.Unit checked(SourceFacts.Unit unit)throws IOException {
    if(!unit.declarationInventoryKnown())return unit;
    try{for(var member:unit.members())terms(member.identifiers());return unit;}
    catch(Quota overflow){return SourceFacts.Unit.unknown(SourceFacts.Status.TREE_QUOTA);}
  }
  public static final class Quota extends RuntimeException {}
}
