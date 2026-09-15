package hu.gov.nav.xsdparsertool.web.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * A statikus webes felület root-relative URL-jeit a tényleges servlet context
 * path alá helyezi.
 *
 * <p>Az eredeti NAV frontend több helyen {@code /api/...}, {@code /js/...},
 * {@code /styles/...}, illetve {@code /valami.html} alakú hivatkozásokat használ.
 * Ez root contextben működik, de például {@code /m2m-service} WAR context alatt
 * a böngésző a domain gyökerére küldené ezeket a kéréseket. A filter csak a
 * statikus HTML/JS/CSS válaszokat módosítja, és csak akkor, ha a context path
 * nem üres.</p>
 *
 * <p>Nem égetjük bele a {@code /m2m-service} nevet, ezért ugyanaz a WAR root
 * contextben és tetszőleges Tomcat context path alatt is használható.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class ContextPathStaticResourceFilter extends OncePerRequestFilter {

    private static final Pattern HTML_ROOT_ATTRIBUTE = Pattern.compile(
            "(?i)(\\b(?:href|src|action)\\s*=\\s*)([\\\"'])/(?!/)");

    /*
     * JS-ben szándékosan nem írunk át minden '/'-rel kezdődő stringet, mert
     * azok XPath kifejezések is lehetnek. Csak ismert webes URL mintákhoz nyúlunk.
     * A quote-ok mellett a template literal (`) is támogatott, mert a frontend
     * dinamikus importjai ilyen URL-eket is használnak.
     */
    private static final Pattern JS_ROOT_URL = Pattern.compile(
            "([\\\"'`])/(?!/)(api/|js/|styles/|images/|login(?:\\.html)?(?:[/?#]|(?=[\\\"'`]))|logout(?:[/?#]|(?=[\\\"'`]))|[A-Za-z0-9._-]+\\.html(?:[?#]|(?=[\\\"'`])))");

    private static final Pattern CSS_ROOT_URL = Pattern.compile(
            "(?i)(url\\(\\s*[\\\"']?)/(?!/)");

    private static final Pattern CSS_ROOT_IMPORT = Pattern.compile(
            "(?i)(@import\\s+[\\\"'])/(?!/)");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            return true;
        }
        String uri = request.getRequestURI();
        String relative = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        return !(relative.isEmpty()
                || "/".equals(relative)
                || relative.endsWith(".html")
                || relative.endsWith(".js")
                || relative.endsWith(".css"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        BufferingResponseWrapper wrapper = new BufferingResponseWrapper(response);
        filterChain.doFilter(request, wrapper);
        wrapper.flushBuffer();

        byte[] original = wrapper.toByteArray();
        if (original.length == 0 || response.getStatus() == HttpServletResponse.SC_NOT_MODIFIED) {
            if (original.length > 0) {
                response.getOutputStream().write(original);
            }
            return;
        }

        Charset charset = resolveCharset(response.getCharacterEncoding());
        String body = new String(original, charset);
        String contextPath = normalizeContextPath(request.getContextPath());
        String relative = request.getRequestURI().substring(request.getContextPath().length());

        String rewritten;
        if (relative.isEmpty() || "/".equals(relative) || relative.endsWith(".html")) {
            rewritten = rewriteHtml(body, contextPath);
        } else if (relative.endsWith(".js")) {
            rewritten = rewriteJavascript(body, contextPath);
        } else if (relative.endsWith(".css")) {
            rewritten = rewriteCss(body, contextPath);
        } else {
            rewritten = body;
        }

        byte[] output = rewritten.getBytes(charset);
        response.setContentLength(output.length);
        response.getOutputStream().write(output);
    }

    private String rewriteHtml(String body, String contextPath) {
        Matcher matcher = HTML_ROOT_ATTRIBUTE.matcher(body);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(
                match.group(1) + match.group(2) + contextPath + "/"));
    }

    private String rewriteJavascript(String body, String contextPath) {
        Matcher matcher = JS_ROOT_URL.matcher(body);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(
                match.group(1) + contextPath + "/" + match.group(2)));
    }

    private String rewriteCss(String body, String contextPath) {
        String rewritten = CSS_ROOT_URL.matcher(body).replaceAll(match -> Matcher.quoteReplacement(
                match.group(1) + contextPath + "/"));
        return CSS_ROOT_IMPORT.matcher(rewritten).replaceAll(match -> Matcher.quoteReplacement(
                match.group(1) + contextPath + "/"));
    }

    private String normalizeContextPath(String contextPath) {
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            return "";
        }
        String value = contextPath.trim();
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private Charset resolveCharset(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private static final class BufferingResponseWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(32 * 1024);
        private ServletOutputStream outputStream;
        private PrintWriter writer;

        private BufferingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (writer != null) {
                throw new IllegalStateException("getWriter() már használatban van.");
            }
            if (outputStream == null) {
                outputStream = new ServletOutputStream() {
                    @Override
                    public void write(int b) {
                        buffer.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        buffer.write(b, off, len);
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener writeListener) {
                        // A statikus válaszok szinkron módon készülnek.
                    }
                };
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (outputStream != null) {
                throw new IllegalStateException("getOutputStream() már használatban van.");
            }
            if (writer == null) {
                Charset charset;
                try {
                    charset = Charset.forName(getCharacterEncoding());
                } catch (Exception ignored) {
                    charset = StandardCharsets.UTF_8;
                }
                writer = new PrintWriter(new OutputStreamWriter(buffer, charset));
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) {
                writer.flush();
            }
            if (outputStream != null) {
                outputStream.flush();
            }
        }

        private byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }
}
