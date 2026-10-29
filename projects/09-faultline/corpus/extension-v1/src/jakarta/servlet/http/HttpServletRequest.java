package jakarta.servlet.http;
public interface HttpServletRequest extends jakarta.servlet.ServletRequest {
 String getHeader(String name);
 String getQueryString();
 String getPathInfo();
 String getRequestURI();
 String getMethod();
}
