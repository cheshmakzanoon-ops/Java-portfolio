package javax.servlet.http;
public interface HttpServletRequest extends javax.servlet.ServletRequest {
 String getHeader(String name);
 String getQueryString();
 String getPathInfo();
 String getRequestURI();
 String getMethod();
}
