package io.github.cheshmakzanoon.portfolio.indexforge;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class DeclarationScannerTest {
 @Test void distinguishesDeclarationsFromCallsStringsAndComments() {
  String source="""
   package example;
   public class Handler<T> {
     // class Bogus { void forged() {} }
     /* public void invisible() {} */
     String literal="public void fake() {}";
     char escaped='\\'';
     String block=\"\"\"
       class Hidden { void forged() {} }
       \"\"\";
     public <R> R transform(T input) throws java.io.IOException { return service.call(input); }
     int[] values() { return new int[0]; }
     abstract void later();
     void execute() { call(); service.apply(); if(ready()) factory.build(); new Handler(); return; }
   }
   interface Contract { void accept(); }
   record Key(String id) {}
   enum Mode { ACTIVE }
   """;
  assertEquals(Set.of("Handler","transform","values","later","execute","Contract","accept","Key","Mode"),DeclarationScanner.names(source));
 }
 @Test void malformedAndAdversarialInputStopsWithinDeclaredBounds() {
  assertEquals(Set.of(),DeclarationScanner.names("/* unfinished"));
  assertEquals(Set.of(),DeclarationScanner.names("\"unfinished"));
  assertEquals(Set.of(),DeclarationScanner.names("int broken((((;"));
  assertEquals(Set.of(),DeclarationScanner.names("int bounded("+"(".repeat(1100)+")".repeat(1101)+"{}"));
  assertEquals(256,DeclarationScanner.names(java.util.stream.IntStream.range(0,300).mapToObj(i->"class C"+i+" {}").collect(java.util.stream.Collectors.joining())).size());
  assertEquals(Set.of(),DeclarationScanner.names("; ".repeat(51000)+"class Beyond {}"));
  assertThrows(IllegalArgumentException.class,()->DeclarationScanner.names(" ".repeat(1_048_577)));
  assertEquals(Set.of("Tail"),DeclarationScanner.names("char c='x'; String s=\"a\\\\b\"; class Tail {}"));
 }
}
