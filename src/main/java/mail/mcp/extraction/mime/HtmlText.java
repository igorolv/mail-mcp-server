package mail.mcp.extraction.mime;

import mail.mcp.extraction.ExtractionContext;
import mail.mcp.extraction.ExtractionLimitException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

/** Local string parsing only: no URL connections, scripts, images, CSS or other external reads. */
final class HtmlText {
    private HtmlText() { }

    static String convert(String html, ExtractionContext context) {
        var document = Jsoup.parse(html);
        int[] nodes = {0};
        document.traverse(new NodeVisitor() {
            @Override public void head(Node node, int depth) {
                context.checkTime();
                if (++nodes[0] > context.limits().maxHtmlNodes()) throw new ExtractionLimitException("html-node-limit");
            }
            @Override public void tail(Node node, int depth) { }
        });
        document.select("script, style, head, template, noscript, [hidden]").remove();
        // Only explicit inline hiding; this does not simulate a CSS engine.
        document.select("[style]").stream().filter(e -> e.attr("style").toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", "").matches(".*(display:none|visibility:hidden).*" )).toList()
                .forEach(Node::remove);
        StringBuilder text = new StringBuilder();
        document.body().traverse(new NodeVisitor() {
            private void append(String value) {
                context.checkTime();
                if (value.length() > context.limits().maxTextChars() - text.length()) {
                    throw new ExtractionLimitException("text-limit");
                }
                text.append(value);
            }
            private void line() {
                if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') append("\n");
            }
            @Override public void head(Node node, int depth) {
                if (node instanceof TextNode value) {
                    boolean pre = false;
                    for (Node parent = value.parent(); parent != null; parent = parent.parent()) {
                        if (parent instanceof Element e && e.normalName().equals("pre")) { pre = true; break; }
                    }
                    append(pre ? value.getWholeText() : value.text());
                }
                if (node instanceof Element e) {
                    String tag = e.normalName();
                    if (breaksLine(e) || tag.equals("br")) line();
                    if (tag.equals("li")) append("- ");
                    if (tag.equals("td") || tag.equals("th")) append(" | ");
                    if (tag.equals("img") && e.hasAttr("alt")) append(e.attr("alt"));
                }
            }
            @Override public void tail(Node node, int depth) {
                if (node instanceof Element e) {
                    if (e.normalName().equals("a")) {
                        String href = e.attr("href").strip();
                        if (href.matches("(?i)^(https?://|mailto:|cid:).*" ) && !href.equals(e.text())) {
                            append(" (" + href + ")");
                        }
                    }
                    if (breaksLine(e)) line();
                }
            }
        });
        return text.toString().strip();
    }

    private static boolean breaksLine(Element element) {
        return element.isBlock() && !element.normalName().equals("td") && !element.normalName().equals("th");
    }
}
