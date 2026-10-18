import io.github.cheshmakzanoon.portfolio.routeweaver.*;
class CoercionProbe { public static void main(String[] args) {
for (String body : new String[]{"{\"seconds\":1.5,\"meters\":2}","{\"seconds\":\"1\",\"meters\":2}"}) {
try { System.out.println(body + " => " + Json.read(body,Model.Arc.class)); }
catch (IllegalArgumentException e) { System.out.println(body + " => REJECTED"); }
}}}
