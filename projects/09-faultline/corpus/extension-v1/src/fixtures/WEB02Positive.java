package fixtures;
public final class WEB02Positive {
static void sink(String url)throws Exception{new java.net.URL(url);}
public static void run(jakarta.servlet.http.HttpServletRequest request)throws Exception{sink(request.getHeader("X-Callback"));}
}
