import io.github.cheshmakzanoon.portfolio.matchcore.*;
import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;
public class ReviewProbe {
 public static void main(String[] a) {
  Book b=new Book();
  b.apply(1,new Command("init","alice",Type.INIT,null,null,0,0,null,false,1000,0));
  b.apply(2,new Command("new","alice",Type.NEW,"order",Side.BUY,10,2,Tif.GTC,false,0,0));
  System.out.println(b.apply(3,new Command("replace","alice",Type.REPLACE,"order",Side.BUY,10,1,Tif.IOC,false,0,0)).code());
  System.out.println("remaining open orders="+b.view().orders().size());
  var token=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("fixture").header("alg","RS256").issuer("https://issuer.invalid").subject("alice").issuedAt(java.time.Instant.now()).claim("aud",java.util.List.of("matchcore")).build();
  System.out.println("missing exp accepted by configured time/issuer validator="+!org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer("https://issuer.invalid").validate(token).hasErrors());
 }
}
