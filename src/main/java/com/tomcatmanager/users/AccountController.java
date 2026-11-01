package com.tomcatmanager.users;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

/** Sign-in page, first-administrator setup and the signed-in user's own account. */
@Controller
public class AccountController {
    private final UserAccountService accounts;
    private final UserBootstrap bootstrap;

    public AccountController(UserAccountService accounts, UserBootstrap bootstrap) {
        this.accounts = accounts; this.bootstrap = bootstrap;
    }

    @GetMapping("/login")
    public String login(Model model) {
        if (bootstrap.setupOpen()) return "redirect:/setup";
        model.addAttribute("pageTitle", "Sign in");
        return "account/login";
    }

    @GetMapping("/setup")
    public String setup(Model model) {
        if (!bootstrap.setupOpen()) return "redirect:/login";
        model.addAttribute("pageTitle", "First-time setup");
        return "account/setup";
    }

    @PostMapping("/setup")
    public String completeSetup(@RequestParam String token, @RequestParam String username, @RequestParam String password,
                                @RequestParam String passwordConfirmation, Model model, RedirectAttributes flash) {
        if (!bootstrap.setupOpen()) return "redirect:/login";
        try {
            bootstrap.completeSetup(token, username, password, passwordConfirmation);
        } catch (IllegalArgumentException e) {
            model.addAttribute("pageTitle", "First-time setup");
            model.addAttribute("error", e.getMessage());
            model.addAttribute("username", username);
            return "account/setup";
        }
        flash.addFlashAttribute("success", "Administrator created. Sign in with your new account.");
        return "redirect:/login";
    }

    @GetMapping("/account")
    public String account(Principal principal, Model model) {
        model.addAttribute("pageTitle", "My account");
        model.addAttribute("user", accounts.findByUsername(principal.getName()).orElseThrow());
        return "account/account";
    }

    @GetMapping(PasswordChangeEnforcement.CHANGE_URL)
    public String passwordForm(@RequestParam(required = false) String required, Principal principal, Model model) {
        model.addAttribute("pageTitle", "Change password");
        model.addAttribute("required", required != null);
        model.addAttribute("user", accounts.findByUsername(principal.getName()).orElseThrow());
        return "account/password";
    }

    @PostMapping(PasswordChangeEnforcement.CHANGE_URL)
    public String changePassword(@RequestParam String currentPassword, @RequestParam String newPassword, @RequestParam String passwordConfirmation,
                                 Principal principal, HttpSession session, Model model, RedirectAttributes flash) {
        try {
            accounts.changeOwnPassword(principal.getName(), currentPassword, newPassword, passwordConfirmation);
        } catch (IllegalArgumentException e) {
            model.addAttribute("pageTitle", "Change password");
            model.addAttribute("required", Boolean.TRUE.equals(session.getAttribute(PasswordChangeEnforcement.SESSION_FLAG)));
            model.addAttribute("user", accounts.findByUsername(principal.getName()).orElseThrow());
            model.addAttribute("error", e.getMessage());
            return "account/password";
        }
        session.removeAttribute(PasswordChangeEnforcement.SESSION_FLAG);
        flash.addFlashAttribute("success", "Your password was changed.");
        return "redirect:/account";
    }
}
