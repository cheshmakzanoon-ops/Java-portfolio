package fixtures;
public final class SafeExtension {
 public static String className(String value){if(!value.matches("com\\.example\\.plugins\\.[A-Za-z0-9_]{1,64}"))throw new IllegalArgumentException();return value;}
 public static String httpUrl(String value){java.net.URI uri=java.net.URI.create(value);if(!"https".equals(uri.getScheme())||!"api.example.org".equals(uri.getHost()))throw new IllegalArgumentException();return value;}
 public static String xpathLiteral(String value){if(!value.matches("[A-Za-z0-9_ ]{0,64}"))throw new IllegalArgumentException();return value;}
 public static String ldapValue(String value){if(!value.matches("[A-Za-z0-9_ ]{0,64}"))throw new IllegalArgumentException();return value;}
}
