package fixtures;
public final class WEB01Positive {
static void sink(String name)throws Exception{Class.forName(name);}
public static void run(javax.servlet.http.HttpServletRequest request)throws Exception{sink(request.getParameter("type"));}
}
