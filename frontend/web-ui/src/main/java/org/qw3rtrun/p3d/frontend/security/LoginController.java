package org.qw3rtrun.p3d.frontend.security;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the sign-in page - plan item D3.
 *
 * <p>Spring Security can generate a login page itself, and this replaces it for one reason: the
 * generated one is unstyled and says "Please sign in" above a form whose action this application
 * controls anyway. A farm that is being handed to an operator should look like the rest of itself.
 *
 * <p>The CSRF token it needs reaches the model through {@link PageModelAdvice}, which does the
 * same for every page - see there for why WebFlux needs it spelled out.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login.html";
    }
}
