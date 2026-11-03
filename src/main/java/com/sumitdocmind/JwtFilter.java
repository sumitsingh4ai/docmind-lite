package com.sumitdocmind;
import jakarta.servlet.*; import jakarta.servlet.http.*; import java.io.IOException; import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtUtil jwt;
    private final boolean jwtEnabled;
    private final long defaultUser;

    public JwtFilter(JwtUtil j,
                     @Value("${app.jwt-enabled:false}") boolean jwtEnabled,
                     @Value("${app.jwt-default-user:1}") long defaultUser) {
        jwt = j;
        this.jwtEnabled = jwtEnabled;
        this.defaultUser = defaultUser;
    }

    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!jwtEnabled) {
            // Auth disabled for local dev: act as the configured default user.
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(defaultUser, null, List.of()));
        } else {
            String h = req.getHeader("Authorization");
            if (h != null && h.startsWith("Bearer ")) {
                Long id = jwt.verify(h.substring(7));
                if (id != null)
                    SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(id, null, List.of()));
            }
        }
        chain.doFilter(req, res);
    }
}
