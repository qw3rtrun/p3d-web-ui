package org.qw3rtrun.p3d.frontend.security;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the sign-in page - plan item D3.
 *
 * <p>Replaces Spring Security's own generated page, which is unstyled and says "Please sign in"
 * above a form this application controls anyway. The CSRF token it needs reaches the model through
 * {@link PageModelAdvice}, which does the same for every page.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login.html";
    }
}
