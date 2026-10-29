package fixtures;
public final class WEB02Negative {
static void sink(int port)throws Exception{new java.net.Socket("api.example.org",port);}
public static void run(jakarta.servlet.http.HttpServletRequest request)throws Exception{sink(Integer.parseInt(request.getParameter("port")));}
}
