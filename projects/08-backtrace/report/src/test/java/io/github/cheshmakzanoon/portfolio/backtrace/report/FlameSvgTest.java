package io.github.cheshmakzanoon.portfolio.backtrace.report;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.backtrace.analysis.StackAggregator;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.StackAggregator.Flame;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class FlameSvgTest {
  private static Flame tree(int limit, List<List<String>> stacks) {
    StackAggregator aggregator = new StackAggregator(limit);
    stacks.forEach(aggregator::accept);
    return aggregator.snapshot();
  }

  private static Document parse(String svg) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setNamespaceAware(true);
    return factory
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
  }

  private static List<Element> rects(Document document) {
    NodeList nodes = document.getElementsByTagName("rect");
    List<Element> result = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
    return result;
  }

  private static double number(Element element, String attribute) {
    return Double.parseDouble(element.getAttribute(attribute));
  }

  @Test
  void widthsAreInclusiveSharesAndChildrenNeverExceedTheirParent() throws Exception {
    List<List<String>> samples = new ArrayList<>();
    for (int i = 0; i < 6; i++) samples.add(List.of("a.A.run()V", "a.B.hot()V"));
    for (int i = 0; i < 3; i++) samples.add(List.of("a.A.run()V", "a.C.cold()V"));
    samples.add(List.of("a.A.run()V"));
    Document document = parse(FlameSvg.render(tree(50, samples)));
    List<Element> rects = rects(document);
    assertEquals(4, rects.size(), "root, run, hot and cold");
    Element root = rects.get(0), run = rects.get(1), hot = rects.get(2), cold = rects.get(3);
    assertEquals(1200.0, number(root, "width"), 0.01);
    assertEquals(1200.0, number(run, "width"), 0.01, "every sample passes through run()");
    assertEquals(720.0, number(hot, "width"), 0.01);
    assertEquals(360.0, number(cold, "width"), 0.01);
    assertEquals(number(run, "x"), number(hot, "x"), 0.01);
    assertEquals(number(hot, "x") + number(hot, "width"), number(cold, "x"), 0.01);
    assertTrue(number(hot, "y") > number(run, "y") && number(run, "y") > number(root, "y"));
    assertEquals("0 0 1200 56", document.getDocumentElement().getAttribute("viewBox"));
  }

  @Test
  void everyBoxHasTheExactGeometryAndLabelPosition() {
    // Two equal children: A fills the left half, B the right half, one row below the root.
    String svg =
        FlameSvg.render(
            tree(
                10,
                List.of(
                    List.of("a.A.run()V"),
                    List.of("a.A.run()V"),
                    List.of("b.B.hot()V"),
                    List.of("b.B.hot()V"))));
    assertTrue(svg.contains("viewBox=\"0 0 1200 38\""), svg);
    assertTrue(
        svg.contains(
            "<g><title>[root]: 4 samples inclusive (100.00%), 0 self</title>"
                + "<rect x=\"0.00\" y=\"0\" width=\"1200.00\" height=\"17\" fill=\"hsl(0,75%,60%)\"/>"
                + "<text x=\"3.00\" y=\"12\">[root]</text></g>"),
        svg);
    assertTrue(
        svg.contains(
            "<g><title>a.A.run()V: 2 samples inclusive (50.00%), 2 self</title>"
                + "<rect x=\"0.00\" y=\"18\" width=\"600.00\" height=\"17\" fill=\"hsl(48,75%,64%)\"/>"
                + "<text x=\"3.00\" y=\"30\">a.A.run()</text></g>"),
        svg);
    assertTrue(
        svg.contains(
            "<g><title>b.B.hot()V: 2 samples inclusive (50.00%), 2 self</title>"
                + "<rect x=\"600.00\" y=\"18\" width=\"600.00\" height=\"17\" fill=\"hsl(38,75%,67%)\"/>"
                + "<text x=\"603.00\" y=\"30\">b.B.hot()</text></g>"),
        svg);
  }

  @Test
  void colourMatchesTheDocumentedFormulaForKnownNames() {
    for (String[] known :
        new String[][] {
          {"x.X.negative()V", "hsl(24,75%,59%)"},
          {"zzzzzzzzzzzzzzzzzzzzzzzzz.Q.m()V", "hsl(23,75%,58%)"}
        })
      assertTrue(
          FlameSvg.render(tree(10, List.of(List.of(known[0]))))
              .contains("fill=\"" + known[1] + "\""),
          known[0]);
  }

  @Test
  void theHalfPixelAndLabelThresholdsAreExactBoundaries() {
    // 1 of 2,400 samples is exactly half a pixel: drawn. 1 of 2,401 is narrower: omitted.
    List<List<String>> exact = new ArrayList<>(Collections.nCopies(2399, List.of("big.B.run()V")));
    exact.add(List.of("edge.E.run()V"));
    assertTrue(FlameSvg.render(tree(10, exact)).contains("<title>edge.E.run()V:"));
    List<List<String>> under = new ArrayList<>(Collections.nCopies(2400, List.of("big.B.run()V")));
    under.add(List.of("edge.E.run()V"));
    String omitted = FlameSvg.render(tree(10, under));
    assertFalse(omitted.contains("<title>edge.E.run()V:"));
    assertTrue(omitted.contains("1 frames narrower than half a pixel"));
    // A box of 25 px fits three characters: two letters and an ellipsis. A box of 24 px has no
    // label.
    List<List<String>> labelled = new ArrayList<>(Collections.nCopies(47, List.of("zz.Z.run()V")));
    labelled.add(List.of("abcdef"));
    assertTrue(FlameSvg.render(tree(10, labelled)).contains(">ab…</text>"));
    List<List<String>> bare = new ArrayList<>(Collections.nCopies(49, List.of("zz.Z.run()V")));
    bare.add(List.of("abcdef"));
    assertFalse(FlameSvg.render(tree(10, bare)).contains("…</text>"));
  }

  @Test
  void omittedFramesCountEveryDescendantOfAnOmittedSubtree() {
    List<List<String>> samples =
        new ArrayList<>(Collections.nCopies(5000, List.of("big.B.run()V")));
    samples.add(List.of("tiny.T.a()V", "tiny.T.b()V", "tiny.T.c()V"));
    assertTrue(FlameSvg.render(tree(50, samples)).contains("3 frames narrower than half a pixel"));
  }

  @Test
  void aTruncationResidualIsDrawnToScaleAfterItsSiblingsAndOnlyWhenWideEnough() {
    // Limit 2: a.A is stored, its child b.B is stored, the sample under c.C cannot be.
    Flame flame =
        tree(
            2,
            List.of(
                List.of("a.A.m()V", "b.B.m()V"),
                List.of("a.A.m()V", "b.B.m()V"),
                List.of("a.A.m()V", "c.C.m()V")));
    String svg = FlameSvg.render(flame);
    assertTrue(
        svg.contains(
            "<g><title>[truncated]: 1 samples inclusive (33.33%), 1 self</title>"
                + "<rect x=\"800.00\" y=\"36\" width=\"400.00\" height=\"17\" fill=\"#c4ccd6\"/>"),
        svg);
    assertTrue(
        svg.contains("viewBox=\"0 0 1200 56\""), "the residual row counts towards the height");
  }

  @Test
  void tooltipsStateInclusiveSelfAndPercent() {
    String svg =
        FlameSvg.render(tree(50, List.of(List.of("a.A.run()V"), List.of("a.A.run()V"), List.of())));
    assertTrue(
        svg.contains("<title>a.A.run()V: 2 samples inclusive (66.67%), 2 self</title>"), svg);
    assertTrue(svg.contains("<title>[root]: 3 samples inclusive (100.00%), 0 self</title>"));
  }

  @Test
  void samplesRecordedOnlyInAPrefixAreAGreyBoxNotSelfTime() throws Exception {
    // Limit 2 nodes: the third distinct frame is not stored, so its sample ends in the prefix.
    Flame flame =
        tree(
            2,
            List.of(List.of("x.X.a()V", "x.X.b()V"), List.of("x.X.a()V", "x.X.c()V"), List.of()));
    String svg = FlameSvg.render(flame);
    assertTrue(svg.contains("<title>[truncated]: 1 samples inclusive"), svg);
    assertTrue(svg.contains("<title>[unattributed]: 1 samples inclusive"), svg);
    for (Element rect : rects(parse(svg)))
      if (rect.getAttribute("fill").equals("#c4ccd6"))
        assertEquals(400.0, number(rect, "width"), 0.01);
  }

  @Test
  void tinyFramesAreCountedAsOmittedInsteadOfDrawnAsInvisibleRects() throws Exception {
    List<List<String>> samples = new ArrayList<>();
    for (int i = 0; i < 5000; i++) samples.add(List.of("big.Hot.run()V"));
    samples.add(List.of("tiny.Cold.once()V", "tiny.Cold.deeper()V"));
    String svg = FlameSvg.render(tree(50, samples));
    assertEquals(
        2, rects(parse(svg.substring(0, svg.indexOf("</svg>") + 6))).size(), "root and Hot only");
    assertTrue(svg.contains("2 frames narrower than half a pixel are not drawn"), svg);
  }

  @Test
  void hostileFrameNamesAreEscapedAndNothingActiveOrExternalIsEmitted() throws Exception {
    String hostile = "<script>alert(\"x\")</script>&'\">";
    String svg = FlameSvg.render(tree(50, List.of(List.of(hostile))));
    parse(svg.substring(0, svg.indexOf("</svg>") + 6));
    assertFalse(svg.contains("<script"));
    assertTrue(svg.contains("&lt;script&gt;"));
    for (String forbidden :
        List.of("href", "src=", "url(", "onload", "onclick", "<style", "<image", "<foreignObject"))
      assertFalse(svg.contains(forbidden), forbidden);
  }

  @Test
  void longNamesAreShortenedInTheBoxButKeptInFullInTheTooltip() {
    String name = "com.example.very.long.package.name.ServiceImplementation.handleRequest()V";
    // A quarter of 1,200 px holds about 47 characters: enough for a label, not for the whole name.
    String svg =
        FlameSvg.render(
            tree(
                10,
                List.of(
                    List.of("w.W.x()V"), List.of("w.W.x()V"), List.of("w.W.x()V"), List.of(name))));
    assertTrue(svg.contains("<title>" + name + ":"), "tooltip carries the exact name");
    assertTrue(svg.contains("…</text>"), "wide enough for text, too narrow for the whole name");
    assertFalse(svg.contains(">" + name + "</text>"));
    // A box under ~25 px has no label at all.
    List<List<String>> narrow = new ArrayList<>();
    for (int i = 0; i < 100; i++) narrow.add(List.of("wide.W.run()V"));
    narrow.add(List.of("slim.S.run()V"));
    assertFalse(FlameSvg.render(tree(10, narrow)).contains(">slim.S.run()</text>"));
  }

  @Test
  void renderingIsDeterministicAndAnEmptyProfileSaysSo() {
    List<List<String>> samples = List.of(List.of("p.P.a()V", "p.P.b()V"), List.of("p.P.c()V"));
    assertEquals(FlameSvg.render(tree(10, samples)), FlameSvg.render(tree(10, samples)));
    assertTrue(FlameSvg.render(tree(10, List.of())).startsWith("<p>No execution samples"));
  }

  @Test
  void colourIsAFunctionOfTheNameOnly() {
    String a = FlameSvg.render(tree(10, List.of(List.of("p.P.a()V"), List.of("q.Q.b()V"))));
    String b =
        FlameSvg.render(
            tree(10, List.of(List.of("q.Q.b()V"), List.of("p.P.a()V"), List.of("p.P.a()V"))));
    java.util.regex.Matcher first =
        java.util.regex.Pattern.compile("<title>p\\.P\\.a\\(\\)V[^>]*>.*?fill=\"([^\"]+)\"")
            .matcher(a);
    java.util.regex.Matcher second =
        java.util.regex.Pattern.compile("<title>p\\.P\\.a\\(\\)V[^>]*>.*?fill=\"([^\"]+)\"")
            .matcher(b);
    assertTrue(first.find() && second.find());
    assertEquals(first.group(1), second.group(1));
    assertTrue(first.group(1).matches("hsl\\(\\d{1,2},75%,\\d{2}%\\)"), first.group(1));
  }
}
