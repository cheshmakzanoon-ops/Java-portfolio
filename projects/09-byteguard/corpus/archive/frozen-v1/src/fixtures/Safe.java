package fixtures;
public final class Safe {
 public static String sqlIdentifier(String value){if(!value.matches("[a-zA-Z_][a-zA-Z_0-9]{0,63}"))throw new IllegalArgumentException();return value;}
 public static String shellToken(String value){if(!value.matches("[a-zA-Z0-9_]{1,64}"))throw new IllegalArgumentException();return value;}
 public static String confinedPath(String value){java.nio.file.Path root=java.nio.file.Path.of("/sandbox");java.nio.file.Path path=root.resolve(value).normalize();if(!path.startsWith(root)||java.nio.file.Path.of(value).isAbsolute())throw new IllegalArgumentException();return path.toString();}
 public static String pretendClean(String value){return value.trim();}
}
