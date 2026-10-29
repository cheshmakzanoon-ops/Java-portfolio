package fixtures;
public final class WEB01Negative {
static void sink(String name)throws Exception{Class.forName(name);}
public static void run(javax.servlet.http.HttpServletRequest request)throws Exception{sink(SafeExtension.className(request.getParameter("type")));}
}
