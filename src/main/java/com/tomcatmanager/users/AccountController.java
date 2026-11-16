package com.tomcatmanager.users;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

/** Sign-in page, first super user setup and the signed-in user's own account. */
@Controller
public class AccountController {
    private final UserAccountService accounts;
    private final UserBootstrap bootstrap;
    private final boolean secureCookie;
    private final com.tomcatmanager.access.AccessAdminService accessAdmin;

    public AccountController(UserAccountService accounts, UserBootstrap bootstrap,
                             @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie,
                             com.tomcatmanager.access.AccessAdminService accessAdmin) {
        this.accounts = accounts; this.bootstrap = bootstrap; this.secureCookie = secureCookie; this.accessAdmin = accessAdmin;
    }

    /** Shown in the password hints of the setup and change-password pages. */
    @org.springframework.web.bind.annotation.ModelAttribute("minPasswordLength")
    public int minPasswordLength() { return accounts.minPasswordLength(); }

    @GetMapping("/login")
    public String login(Model model) {
        if (bootstrap.setupOpen()) return "redirect:/setup";
        model.addAttribute("pageTitle", "Sign in");
        // Days a "Remember me" sign-in lasts; the checkbox is hidden when the option is switched off (0).
        model.addAttribute("rememberMeDays", accounts.rememberMeDays());
        model.addAttribute("secureCookie", secureCookie);
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
        flash.addFlashAttribute("success", "Super user created. Sign in with your new account.");
        return "redirect:/login";
    }

    @GetMapping("/account")
    public String account(Principal principal, Model model) {
        model.addAttribute("pageTitle", "My account");
        AppUser user = accounts.findByUsername(principal.getName()).orElseThrow();
        model.addAttribute("user", user);
        // My access: an administrator's teams, or an operator's or viewer's instance grants.
        model.addAttribute("myTeams", accessAdmin.teamsOf(user.getId()));
        model.addAttribute("myGrants", accessAdmin.grantsOf(user.getId()));
        model.addAttribute("rememberMeDays", accounts.rememberMeDays());
        return "account/account";
    }

    /** Signs this user out of every browser where they chose "Remember me" (including this one, at its next visit). */
    @PostMapping("/account/forget-browsers")
    public String forgetBrowsers(Principal principal, org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        accounts.forgetRememberedLogins(principal.getName());
        accounts.recordForgottenBrowsers(principal.getName());
        flash.addFlashAttribute("success", "Remembered browsers were forgotten: everywhere you chose \"Remember me\", you will have to sign in again.");
        return "redirect:/account";
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
