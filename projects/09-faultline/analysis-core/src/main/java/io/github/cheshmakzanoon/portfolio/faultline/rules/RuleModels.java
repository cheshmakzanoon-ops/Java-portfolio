package io.github.cheshmakzanoon.portfolio.faultline.rules;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.MethodDescriptor;
import java.util.*;

/** Exact descriptors. The model is versioned; names alone never establish sanitization. */
public final class RuleModels {
  /** Current model. 1.1.0 added the class-loading, URL, XPath and LDAP rules and web sources. */
  public static final String VERSION = "1.1.0";

  /** The original three-rule model, still accepted in rule files and baselines. */
  public static final String LEGACY_VERSION = "1.0.0";

  public static final Set<String> SUPPORTED_VERSIONS = Set.of(LEGACY_VERSION, VERSION);

  /** Each rule owns one context bit; a sanitizer clears exactly one. */
  public static final Map<String, Integer> CONTEXTS =
      Map.of(
          "BG-SQL-TEXT", 1,
          "BG-SHELL-COMMAND", 2,
          "BG-PATH-ACCESS", 4,
          "BG-CLASS-LOAD", 8,
          "BG-URL-FETCH", 16,
          "BG-XPATH-QUERY", 32,
          "BG-LDAP-FILTER", 64);

  public static final Set<String> LEGACY_RULES =
      Set.of("BG-SQL-TEXT", "BG-SHELL-COMMAND", "BG-PATH-ACCESS");

  /** Every context bit a taint label may carry. */
  public static final int ALL_CONTEXTS = 0x7F;

  public record Sink(String id, int mask, int argument) {}

  private static final List<String> SERVLET_PACKAGES = List.of("javax", "jakarta");

  private static final Set<String> DEFAULT_SOURCES = defaultSources();
  private static final Map<String, Sink> DEFAULT_SINKS = defaultSinks();
  private static final Map<String, Integer> DEFAULT_SANITIZERS =
      Map.of(
          "fixtures/Safe.sqlIdentifier(Ljava/lang/String;)Ljava/lang/String;", 1,
          "fixtures/Safe.shellToken(Ljava/lang/String;)Ljava/lang/String;", 2,
          "fixtures/Safe.confinedPath(Ljava/lang/String;)Ljava/lang/String;", 4,
          "fixtures/SafeExtension.className(Ljava/lang/String;)Ljava/lang/String;", 8,
          "fixtures/SafeExtension.httpUrl(Ljava/lang/String;)Ljava/lang/String;", 16,
          "fixtures/SafeExtension.xpathLiteral(Ljava/lang/String;)Ljava/lang/String;", 32,
          "fixtures/SafeExtension.ldapValue(Ljava/lang/String;)Ljava/lang/String;", 64);

  private final Set<String> configuredSources;
  private final Map<String, Sink> configuredSinks;
  private final Map<String, Integer> configuredSanitizers;

  public RuleModels() {
    configuredSources = null;
    configuredSinks = null;
    configuredSanitizers = null;
  }

  public RuleModels(Set<String> sources, Map<String, Sink> sinks, Map<String, Integer> sanitizers) {
    configuredSources = Set.copyOf(sources);
    configuredSinks = Map.copyOf(sinks);
    configuredSanitizers = Map.copyOf(sanitizers);
    sources.forEach(RuleModels::valueDescriptor);
    sanitizers.keySet().forEach(RuleModels::valueDescriptor);
    for (var entry : sinks.entrySet()) {
      String descriptor = descriptor(entry.getKey());
      Sink sink = entry.getValue();
      if (sink.argument() >= org.objectweb.asm.Type.getArgumentTypes(descriptor).length)
        throw new IllegalArgumentException("sink argument outside exact descriptor");
      if (!Integer.valueOf(sink.mask()).equals(CONTEXTS.get(sink.id())))
        throw new IllegalArgumentException("sink rule/context mismatch");
    }
    for (Sink sink : sinks.values())
      if (!rules().contains(sink.id()) || sink.mask() < 1 || sink.argument() < 0)
        throw new IllegalArgumentException("invalid exact sink model");
    for (int context : sanitizers.values())
      if (!CONTEXTS.containsValue(context))
        throw new IllegalArgumentException("sanitizer must have exactly one context");
  }

  /** Rules a document of this model version must declare, each exactly once. */
  public static Set<String> rulesFor(String version) {
    if (version.equals(VERSION)) return CONTEXTS.keySet();
    if (version.equals(LEGACY_VERSION)) return LEGACY_RULES;
    throw new IllegalArgumentException("unsupported model version");
  }

  private static String descriptor(String id) {
    int start = id.indexOf('(');
    if (start < 1 || !id.substring(0, start).matches("[A-Za-z0-9_$/]+\\.(?:[A-Za-z0-9_$]+|<init>)"))
      throw new IllegalArgumentException("exact owner.name descriptor required");
    try {
      String descriptor = id.substring(start);
      boolean constructor = id.substring(0, start).endsWith(".<init>");
      MethodDescriptor.parameterSlots(descriptor, !constructor);
      var args = org.objectweb.asm.Type.getArgumentTypes(descriptor);
      var returns = org.objectweb.asm.Type.getReturnType(descriptor);
      if (constructor && returns.getSort() != org.objectweb.asm.Type.VOID)
        throw new IllegalArgumentException("constructor descriptor must return void");
      if (!org.objectweb.asm.Type.getMethodDescriptor(returns, args).equals(descriptor))
        throw new IllegalArgumentException("invalid descriptor");
      return descriptor;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("invalid method descriptor: " + id);
    }
  }

  private static void valueDescriptor(String id) {
    String method = descriptor(id);
    if (org.objectweb.asm.Type.getReturnType(method).getSort() == org.objectweb.asm.Type.VOID)
      throw new IllegalArgumentException("source and sanitizer contracts require a returned value");
  }

  public boolean source(String id) {
    if (configuredSources != null) return configuredSources.contains(id);
    return DEFAULT_SOURCES.contains(id);
  }

  public Sink sink(String id) {
    if (configuredSinks != null) return configuredSinks.get(id);
    return DEFAULT_SINKS.get(id);
  }

  public int sanitizer(String id) {
    if (configuredSanitizers != null) return configuredSanitizers.getOrDefault(id, 0);
    return DEFAULT_SANITIZERS.getOrDefault(id, 0);
  }

  public Set<String> rules() {
    return CONTEXTS.keySet();
  }

  /** The built-in source descriptors, for tooling that lists the active model. */
  public Set<String> sources() {
    return new TreeSet<>(configuredSources != null ? configuredSources : DEFAULT_SOURCES);
  }

  /** The built-in or configured sinks keyed by exact descriptor, in descriptor order. */
  public SortedMap<String, Sink> sinks() {
    return new TreeMap<>(configuredSinks != null ? configuredSinks : DEFAULT_SINKS);
  }

  /** The built-in or configured sanitizers keyed by exact descriptor, in descriptor order. */
  public SortedMap<String, Integer> sanitizers() {
    return new TreeMap<>(configuredSanitizers != null ? configuredSanitizers : DEFAULT_SANITIZERS);
  }

  private static Set<String> defaultSources() {
    Set<String> sources =
        new HashSet<>(
            Set.of(
                "java/lang/System.getenv(Ljava/lang/String;)Ljava/lang/String;",
                "java/lang/System.getProperty(Ljava/lang/String;)Ljava/lang/String;",
                "java/io/BufferedReader.readLine()Ljava/lang/String;",
                "java/util/Scanner.nextLine()Ljava/lang/String;",
                "java/io/Console.readLine()Ljava/lang/String;"));
    String string = "Ljava/lang/String;";
    for (String pkg : SERVLET_PACKAGES) {
      sources.add(pkg + "/servlet/ServletRequest.getParameter(" + string + ")" + string);
      String http = pkg + "/servlet/http/HttpServletRequest.";
      sources.add(http + "getParameter(" + string + ")" + string);
      sources.add(http + "getHeader(" + string + ")" + string);
      sources.add(http + "getQueryString()" + string);
      sources.add(http + "getPathInfo()" + string);
      sources.add(http + "getRequestURI()" + string);
    }
    return Set.copyOf(sources);
  }

  private static Map<String, Sink> defaultSinks() {
    Map<String, Sink> sinks = new HashMap<>();
    for (String sql :
        List.of(
            "java/sql/Statement.execute(Ljava/lang/String;)Z",
            "java/sql/Statement.executeQuery(Ljava/lang/String;)Ljava/sql/ResultSet;",
            "java/sql/Statement.executeUpdate(Ljava/lang/String;)I",
            "java/sql/Connection.prepareStatement(Ljava/lang/String;)Ljava/sql/PreparedStatement;"))
      sinks.put(sql, new Sink("BG-SQL-TEXT", 1, 0));
    for (String path :
        List.of(
            "java/nio/file/Files.readString(Ljava/nio/file/Path;)Ljava/lang/String;",
            "java/nio/file/Files.readAllBytes(Ljava/nio/file/Path;)[B",
            "java/io/FileInputStream.<init>(Ljava/lang/String;)V"))
      sinks.put(path, new Sink("BG-PATH-ACCESS", 4, 0));
    for (String load :
        List.of(
            "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
            "java/lang/Class.forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
            "java/lang/ClassLoader.loadClass(Ljava/lang/String;)Ljava/lang/Class;",
            "java/lang/ClassLoader.loadClass(Ljava/lang/String;Z)Ljava/lang/Class;",
            "java/lang/invoke/MethodHandles$Lookup.findClass(Ljava/lang/String;)Ljava/lang/Class;"))
      sinks.put(load, new Sink("BG-CLASS-LOAD", 8, 0));
    for (String url :
        List.of(
            "java/net/URL.<init>(Ljava/lang/String;)V",
            "java/net/Socket.<init>(Ljava/lang/String;I)V",
            "java/net/http/HttpRequest.newBuilder(Ljava/net/URI;)Ljava/net/http/HttpRequest$Builder;",
            "java/net/http/HttpRequest$Builder.uri(Ljava/net/URI;)Ljava/net/http/HttpRequest$Builder;"))
      sinks.put(url, new Sink("BG-URL-FETCH", 16, 0));
    String xpath = "javax/xml/xpath/XPath.";
    for (String expression :
        List.of(
            xpath + "evaluate(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;",
            xpath
                + "evaluate(Ljava/lang/String;Ljava/lang/Object;Ljavax/xml/namespace/QName;)Ljava/lang/Object;",
            xpath + "evaluate(Ljava/lang/String;Lorg/xml/sax/InputSource;)Ljava/lang/String;",
            xpath + "compile(Ljava/lang/String;)Ljavax/xml/xpath/XPathExpression;",
            xpath
                + "evaluateExpression(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;"))
      sinks.put(expression, new Sink("BG-XPATH-QUERY", 32, 0));
    String enumeration = "Ljavax/naming/NamingEnumeration;";
    String controls = "Ljavax/naming/directory/SearchControls;";
    for (String owner :
        List.of("javax/naming/directory/DirContext", "javax/naming/directory/InitialDirContext"))
      for (String name : List.of("Ljava/lang/String;", "Ljavax/naming/Name;")) {
        sinks.put(
            owner + ".search(" + name + "Ljava/lang/String;" + controls + ")" + enumeration,
            new Sink("BG-LDAP-FILTER", 64, 1));
        sinks.put(
            owner
                + ".search("
                + name
                + "Ljava/lang/String;[Ljava/lang/Object;"
                + controls
                + ")"
                + enumeration,
            new Sink("BG-LDAP-FILTER", 64, 1));
      }
    return Map.copyOf(sinks);
  }
}
