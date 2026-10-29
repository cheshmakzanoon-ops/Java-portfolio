package io.github.cheshmakzanoon.portfolio.backtrace.report;

import io.github.cheshmakzanoon.portfolio.backtrace.analysis.StackAggregator.Flame;
import java.util.List;
import java.util.Locale;

/**
 * A static icicle graph of the sampled stack tree: the root is the top row, a frame's width is its
 * inclusive sample share, children sit left-aligned under their parent and whatever is left of the
 * parent is its self time. Samples that the bounded tree recorded only in a prefix are drawn as an
 * explicit grey {@value FlameExport#TRUNCATED}/{@value FlameExport#UNATTRIBUTED} box rather than
 * being passed off as self time. Output is inline SVG with no script, link, font or external
 * resource, so it is valid under the report's {@code default-src 'none'} policy and byte-for-byte
 * deterministic.
 */
final class FlameSvg {
  static final int WIDTH = 1200, ROW = 18;

  /** Frames narrower than this many pixels are left out; the tree below the graph lists them. */
  static final double MINIMUM_WIDTH = 0.5;

  private final StringBuilder body = new StringBuilder();
  private final double total;
  private int maxDepth, omitted;

  private FlameSvg(double total) {
    this.total = total;
  }

  /** Returns an {@code <svg>} element, or an explanatory paragraph when there are no samples. */
  static String render(Flame root) {
    if (root.inclusive() == 0)
      return "<p>No execution samples were observed, so there is no flame graph to draw.</p>";
    FlameSvg svg = new FlameSvg(root.inclusive());
    svg.node(root, 0, 0, 0, true);
    int height = (svg.maxDepth + 1) * ROW + 2;
    StringBuilder out = new StringBuilder();
    out.append("<svg class=\"flame-svg\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
        .append(WIDTH)
        .append(' ')
        .append(height)
        .append("\" role=\"img\" aria-label=\"Flame graph of ")
        .append(root.inclusive())
        .append(" sampled stacks. The same data follows as a navigable tree.\">")
        .append("<title>Flame graph: width is the inclusive share of ")
        .append(root.inclusive())
        .append(" samples</title>")
        .append(svg.body)
        .append("</svg>");
    if (svg.omitted > 0)
      out.append("<p class=\"table-hint\">")
          .append(svg.omitted)
          .append(
              " frames narrower than half a pixel are not drawn; the tree below lists them.</p>");
    return out.toString();
  }

  private void node(Flame node, double x, double width, int depth, boolean isRoot) {
    maxDepth = Math.max(maxDepth, depth);
    if (isRoot) width = WIDTH;
    draw(node.frame(), x, width, depth, node.inclusive(), node.self(), color(node.frame()));
    double cursor = x;
    long children = 0;
    List<Flame> sorted = node.children();
    for (Flame child : sorted) {
      double w = child.inclusive() / total * WIDTH;
      children += child.inclusive();
      if (w < MINIMUM_WIDTH) {
        omitted += 1 + count(child);
      } else {
        node(child, cursor, w, depth + 1, false);
      }
      cursor += w;
    }
    long residual = node.inclusive() - node.self() - children;
    if (residual > 0) {
      double w = residual / total * WIDTH;
      if (w >= MINIMUM_WIDTH) {
        maxDepth = Math.max(maxDepth, depth + 1);
        draw(
            isRoot ? FlameExport.UNATTRIBUTED : FlameExport.TRUNCATED,
            cursor,
            w,
            depth + 1,
            residual,
            residual,
            "#c4ccd6");
      } else omitted++;
    }
  }

  private static int count(Flame node) {
    int n = 0;
    for (Flame child : node.children()) n += 1 + count(child);
    return n;
  }

  private void draw(
      String frame, double x, double width, int depth, long inclusive, long self, String fill) {
    String name = ReportWriter.escape(frame);
    body.append("<g><title>")
        .append(name)
        .append(": ")
        .append(inclusive)
        .append(" samples inclusive (")
        .append(percent(inclusive))
        .append("), ")
        .append(self)
        .append(" self</title><rect x=\"")
        .append(number(x))
        .append("\" y=\"")
        .append(depth * ROW)
        .append("\" width=\"")
        .append(number(width))
        .append("\" height=\"")
        .append(ROW - 1)
        .append("\" fill=\"")
        .append(fill)
        .append("\"/>");
    int characters = (int) ((width - 6) / 6.2);
    if (characters >= 3) {
      String shown = FlameExport.displayName(frame);
      if (shown.length() > characters) shown = shown.substring(0, characters - 1) + "…";
      body.append("<text x=\"")
          .append(number(x + 3))
          .append("\" y=\"")
          .append(depth * ROW + 12)
          .append("\">")
          .append(ReportWriter.escape(shown))
          .append("</text>");
    }
    body.append("</g>");
  }

  private String percent(long samples) {
    return String.format(Locale.ROOT, "%.2f%%", samples * 100.0 / total);
  }

  private static String number(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  /** Stable warm colour per frame name so the same method looks the same in every report. */
  private static String color(String frame) {
    int hash = 0;
    for (int i = 0; i < frame.length(); i++) hash = 31 * hash + frame.charAt(i);
    int hue = Math.floorMod(hash, 50), lightness = 58 + Math.floorMod(hash >>> 8, 10);
    return "hsl(" + hue + ",75%," + lightness + "%)";
  }
}
