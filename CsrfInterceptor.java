package University.exam.interceptor;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public class CsrfInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(CsrfInterceptor.class);

    public static final String CSRF_SESSION_ATTR = "CSRF_TOKEN";
    public static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    public static final String CSRF_HEADER_NAME = "X-CSRF-TOKEN";
    public static final String CSRF_PARAM_NAME = "_csrf";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(true);
        String token = (String) session.getAttribute(CSRF_SESSION_ATTR);
        if (token == null) {
            token = UUID.randomUUID().toString();
            session.setAttribute(CSRF_SESSION_ATTR, token);
        }

        // Set request attributes for templates
        request.setAttribute(CSRF_PARAM_NAME, token);
        request.setAttribute("csrfToken", token);

        // Set XSRF-TOKEN cookie for JavaScript fetch calls
        Cookie cookie = new Cookie(CSRF_COOKIE_NAME, token);
        cookie.setPath("/");
        cookie.setHttpOnly(false);
        response.addCookie(cookie);

        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method) || "TRACE".equalsIgnoreCase(method)) {
            return true;
        }

        // Validate state-changing requests (POST, PUT, DELETE, PATCH)
        String requestToken = request.getHeader(CSRF_HEADER_NAME);
        if (requestToken == null || requestToken.trim().isEmpty()) {
            requestToken = request.getHeader("X-XSRF-TOKEN");
        }
        if (requestToken == null || requestToken.trim().isEmpty()) {
            requestToken = request.getParameter(CSRF_PARAM_NAME);
        }
        if (requestToken == null || requestToken.trim().isEmpty()) {
            requestToken = request.getParameter("csrfToken");
        }
        if (requestToken == null || requestToken.trim().isEmpty()) {
            String qs = request.getQueryString();
            if (qs != null && qs.contains(CSRF_PARAM_NAME + "=")) {
                for (String part : qs.split("&")) {
                    if (part.startsWith(CSRF_PARAM_NAME + "=")) {
                        requestToken = part.substring((CSRF_PARAM_NAME + "=").length());
                        break;
                    }
                }
            }
        }

        if (requestToken != null && requestToken.equals(token)) {
            return true;
        }

        logger.warn("CSRF validation failed for URI [{}] and method [{}]", request.getRequestURI(), method);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        if (request.getRequestURI().contains("/api/")) {
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Invalid or missing CSRF token\"}");
        } else {
            response.sendRedirect(request.getContextPath() + "/?error=invalid_csrf");
        }
        return false;
    }
}

