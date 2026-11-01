package com.tomcatmanager.users;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

/** User management (administrators only; enforced in the security configuration for /admin/**). */
@Controller
@RequestMapping("/admin/users")
public class UserAdminController {
    private final UserAccountService accounts;
    private final SecurityProperties properties;

    public UserAdminController(UserAccountService accounts, SecurityProperties properties) {
        this.accounts = accounts; this.properties = properties;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("pageTitle", "Users");
        model.addAttribute("users", accounts.all());
        model.addAttribute("roles", Role.values());
        return "admin/users";
    }

    @GetMapping("/new")
    public String create(Model model) { return form(model, new UserForm()); }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        model.addAttribute("account", accounts.find(id));
        return form(model, UserForm.from(accounts.find(id)));
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("form") UserForm form, BindingResult result, Principal principal, Model model, RedirectAttributes flash) {
        accounts.validate(form, principal.getName(), result);
        if (result.hasErrors()) {
            if (!form.isNew()) model.addAttribute("account", accounts.find(form.getId()));
            return form(model, form);
        }
        if (form.isNew()) {
            AppUser created = accounts.create(form, principal.getName());
            flash.addFlashAttribute("success", "User " + created.getUsername() + " created. Give them the initial password; they must change it at first sign-in.");
        } else {
            AppUser updated = accounts.update(form, principal.getName());
            flash.addFlashAttribute("success", "User " + updated.getUsername() + " updated.");
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable long id, @RequestParam String temporaryPassword, Principal principal, RedirectAttributes flash) {
        try {
            accounts.resetPassword(id, temporaryPassword, principal.getName());
            flash.addFlashAttribute("success", "Temporary password set for " + accounts.find(id).getUsername()
                    + ". Their open sessions were ended; they must choose a new password at the next sign-in.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", "Password not reset: " + e.getMessage());
        }
        return "redirect:/admin/users/" + id + "/edit";
    }

    @PostMapping("/{id}/unlock")
    public String unlock(@PathVariable long id, RedirectAttributes flash) {
        accounts.unlock(id);
        flash.addFlashAttribute("success", "User " + accounts.find(id).getUsername() + " unlocked.");
        return "redirect:/admin/users";
    }

    private String form(Model model, UserForm form) {
        model.addAttribute("pageTitle", form.isNew() ? "Create user" : "Edit user " + form.getUsername());
        model.addAttribute("form", form);
        model.addAttribute("roles", Role.values());
        model.addAttribute("minPasswordLength", Math.max(8, properties.minPasswordLength()));
        return "admin/user-form";
    }
}
