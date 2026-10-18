import io.github.cheshmakzanoon.portfolio.routeweaver.*;
class MissingFieldProbe {public static void main(String[]args){System.out.println(Json.read("{\"meters\":2}",Model.Arc.class));}}
