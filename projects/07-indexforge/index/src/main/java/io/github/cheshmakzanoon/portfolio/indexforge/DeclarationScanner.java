package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

/** Bounded conservative Java declaration hints; never compiles or executes source. */
public final class DeclarationScanner {
  private DeclarationScanner() {}

  private static final Set<String> TYPES = Set.of("class", "interface", "record", "enum");
  private static final Set<String> NON_TYPES =
      Set.of("return", "throw", "new", "case", "yield", "else", "do", "assert");
  private static final Set<String> CONTROLS =
      Set.of("if", "while", "for", "switch", "catch", "synchronized", "try", "this", "super");

  static List<String> tokens(String source) {
    if (source.length() > 1_048_576) throw new IllegalArgumentException("source quota");
    List<String> tokens = new ArrayList<>();
    for (int i = 0; i < source.length() && tokens.size() < 100_000; ) {
      char c = source.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
        i += 2;
        while (i < source.length() && source.charAt(i) != '\n') i++;
        continue;
      }
      if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
        i += 2;
        while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/'))
          i++;
        i = Math.min(source.length(), i + 2);
        continue;
      }
      if (c == '"' || c == '\'') {
        boolean text = c == '"' && source.startsWith("\"\"\"", i);
        i += text ? 3 : 1;
        while (i < source.length()) {
          if (source.charAt(i) == '\\') {
            i = Math.min(source.length(), i + 2);
            continue;
          }
          if (text ? source.startsWith("\"\"\"", i) : source.charAt(i) == c) {
            i += text ? 3 : 1;
            break;
          }
          i++;
        }
        tokens.add("<literal>");
        continue;
      }
      if (Character.isJavaIdentifierStart(c)) {
        int start = i++;
        while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
        tokens.add(source.substring(start, i));
      } else {
        tokens.add(String.valueOf(c));
        i++;
      }
    }
    return List.copyOf(tokens);
  }

  public static Set<String> names(String source) {
    List<String> tokens = tokens(source);
    Set<String> names = new LinkedHashSet<>();
    for (int i = 1; i + 1 < tokens.size() && names.size() < 256; i++) {
      String name = tokens.get(i), previous = tokens.get(i - 1);
      if (!Character.isJavaIdentifierStart(name.charAt(0))) continue;
      if (TYPES.contains(previous)) {
        names.add(name);
        continue;
      }
      if (!tokens.get(i + 1).equals("(") || CONTROLS.contains(name) || NON_TYPES.contains(previous))
        continue;
      if (!(Character.isJavaIdentifierStart(previous.charAt(0))
          || previous.equals("]")
          || previous.equals(">"))) continue;
      int depth = 1, j = i + 2;
      for (; j < tokens.size() && j < i + 1024 && depth > 0; j++) {
        if (tokens.get(j).equals("(")) depth++;
        else if (tokens.get(j).equals(")")) depth--;
      }
      if (depth != 0 || j >= tokens.size()) continue;
      if (tokens.get(j).equals("throws")) {
        j++;
        while (j < tokens.size()
            && j < i + 1024
            && !Set.of("{", ";", "=", ")").contains(tokens.get(j))) j++;
      }
      if (j < tokens.size() && (tokens.get(j).equals("{") || tokens.get(j).equals(";")))
        names.add(name);
    }
    return Collections.unmodifiableSet(names);
  }
}
