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
    private final com.tomcatmanager.access.Access access;
    private final com.tomcatmanager.access.AccessAdminService accessAdmin;

    public UserAdminController(UserAccountService accounts, SecurityProperties properties, com.tomcatmanager.access.Access access,
                               com.tomcatmanager.access.AccessAdminService accessAdmin) {
        this.accounts = accounts; this.properties = properties; this.access = access; this.accessAdmin = accessAdmin;
    }

    // ---- Which accounts an administrator sees and changes ----
    // Super users see and change everyone. An administrator sees the users who share one of their teams and users with
    // no access yet (just created); they change a user only when all of that user's teams are theirs. Super users stay hidden.

    private boolean visible(AppUser u, com.tomcatmanager.access.AccessScope s, java.util.Map<Long, java.util.Set<Long>> teams) {
        if (s.isEverything()) return true;
        if (u.getRole() == Role.SUPER_ADMIN) return false;
        if (u.getUsername().equalsIgnoreCase(s.username())) return true;
        java.util.Set<Long> t = teams.getOrDefault(u.getId(), java.util.Set.of());
        return t.isEmpty() || t.stream().anyMatch(s::canManageTeam);
    }

    private boolean manageable(AppUser u, com.tomcatmanager.access.AccessScope s, java.util.Map<Long, java.util.Set<Long>> teams) {
        if (s.isEverything()) return true;
        if (u.getRole() == Role.SUPER_ADMIN) return false;
        return teams.getOrDefault(u.getId(), java.util.Set.of()).stream().allMatch(s::canManageTeam);
    }

    /** The account, if the administrator may change it (not found when they cannot even see it). */
    private AppUser manageableAccount(long id) {
        com.tomcatmanager.access.AccessScope s = access.current();
        java.util.Map<Long, java.util.Set<Long>> teams = access.teamsByUser();
        AppUser u = accounts.find(id);
        if (!visible(u, s, teams)) throw new java.util.NoSuchElementException("User " + id + " not found.");
        if (!manageable(u, s, teams)) throw new com.tomcatmanager.access.NotPermittedException("This user also works in teams you do not administer; ask a super user.");
        return u;
    }

    @GetMapping
    public String index(Model model) {
        com.tomcatmanager.access.AccessScope s = access.current();
        java.util.Map<Long, java.util.Set<Long>> teams = access.teamsByUser();
        java.util.List<AppUser> mine = accounts.all().stream().filter(u -> visible(u, s, teams)).toList();
        model.addAttribute("pageTitle", "Users");
        model.addAttribute("users", mine);
        model.addAttribute("manageableIds", mine.stream().filter(u -> manageable(u, s, teams)).map(AppUser::getId).collect(java.util.stream.Collectors.toSet()));
        model.addAttribute("roles", s.isEverything() ? java.util.List.of(Role.values()) : java.util.List.of(Role.ADMIN, Role.DEPLOYMENT_OPERATOR, Role.VIEWER));
        // The teams each user works in, by name (administrators: assigned teams; others: the teams their grants point into).
        java.util.Map<Long, String> teamNames = new java.util.HashMap<>();
        accessAdmin.allTeams().forEach(t -> teamNames.put(t.getId(), t.getName()));
        java.util.Map<Long, java.util.List<String>> userTeams = new java.util.HashMap<>();
        for (AppUser u : mine)
            userTeams.put(u.getId(), teams.getOrDefault(u.getId(), java.util.Set.of()).stream().map(teamNames::get)
                    .filter(java.util.Objects::nonNull).sorted().toList());
        model.addAttribute("userTeams", userTeams);
        return "admin/users";
    }

    @GetMapping("/new")
    public String create(Principal principal, Model model) { return form(model, new UserForm(), principal.getName()); }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable long id, Principal principal, Model model) {
        AppUser account = manageableAccount(id);
        model.addAttribute("account", account);
        accessModel(model, account);
        return form(model, UserForm.from(account), principal.getName());
    }

    /** The Access panel of the edit page: an administrator's teams, or an operator's or viewer's instance grants. */
    private void accessModel(Model model, AppUser account) {
        com.tomcatmanager.access.AccessScope s = access.current();
        model.addAttribute("selfAccess", account.getUsername().equalsIgnoreCase(s.username()));
        model.addAttribute("assignedTeamIds", accessAdmin.teamsOf(account.getId()).stream().map(com.tomcatmanager.access.Team::getId)
                .collect(java.util.stream.Collectors.toSet()));
        model.addAttribute("assignableTeams", accessAdmin.assignableTeams(s));
        java.util.List<com.tomcatmanager.access.AccessAdminService.GrantView> grants = accessAdmin.grantsOf(account.getId());
        model.addAttribute("grants", grants);
        model.addAttribute("removableGrantIds", grants.stream()
                .filter(g -> g.teamId() == null ? s.isSuperUser() : s.canManageTeam(g.teamId()))
                .map(g -> g.grant().getId()).collect(java.util.stream.Collectors.toSet()));
        model.addAttribute("grantOptions", accessAdmin.grantOptions(s));
        model.addAttribute("superActor", s.isSuperUser());
        model.addAttribute("today", java.time.LocalDate.now());
    }

    private void requireNotSelf(AppUser account) {
        if (account.getUsername().equalsIgnoreCase(access.current().username()))
            throw new com.tomcatmanager.access.NotPermittedException("You cannot change your own access. Ask a super user or another administrator.");
    }

    @PostMapping("/{id}/teams")
    public String saveTeams(@PathVariable long id, @RequestParam(name = "teamIds", required = false) java.util.List<Long> teamIds, RedirectAttributes flash) {
        AppUser account = manageableAccount(id);
        requireNotSelf(account);
        try {
            accessAdmin.setAdminTeams(account, teamIds, access.current());
            flash.addFlashAttribute("success", "Teams of " + account.getUsername() + " saved.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users/" + id + "/edit#access";
    }

    @PostMapping("/{id}/grants")
    public String addGrant(@PathVariable long id, @RequestParam(required = false) String target,
                           @RequestParam(required = false) com.tomcatmanager.access.AccessGrant.Level level,
                           @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate expires,
                           @RequestParam(required = false) String reason, RedirectAttributes flash) {
        AppUser account = manageableAccount(id);
        requireNotSelf(account);
        try {
            boolean added = accessAdmin.grant(account, target, level, expires, reason, access.current());
            flash.addFlashAttribute("success", added ? "Access given to " + account.getUsername() + "."
                    : account.getUsername() + " already had access there; its level, expiry and reason were updated.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", "Access not given: " + e.getMessage());
        }
        return "redirect:/admin/users/" + id + "/edit#access";
    }

    @PostMapping("/{id}/grants/{grantId}/delete")
    public String removeGrant(@PathVariable long id, @PathVariable long grantId, RedirectAttributes flash) {
        AppUser account = manageableAccount(id);
        requireNotSelf(account);
        accessAdmin.revoke(account, grantId, access.current());
        flash.addFlashAttribute("success", "Access removed from " + account.getUsername() + ".");
        return "redirect:/admin/users/" + id + "/edit#access";
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("form") UserForm form, BindingResult result, Principal principal, Model model, RedirectAttributes flash) {
        if (!form.isNew()) manageableAccount(form.getId());
        accounts.validate(form, principal.getName(), result);
        if (result.hasErrors()) {
            if (!form.isNew()) {
                AppUser account = accounts.find(form.getId());
                model.addAttribute("account", account);
                accessModel(model, account);
            }
            return form(model, form, principal.getName());
        }
        if (form.isNew()) {
            AppUser created = accounts.create(form, principal.getName());
            if (created.getRole() == Role.SUPER_ADMIN) {
                flash.addFlashAttribute("success", "Super user " + created.getUsername() + " created. Give them the initial password; they must change it at first sign-in.");
                return "redirect:/admin/users";
            }
            // A new administrator, operator or viewer has no access until it is given here.
            flash.addFlashAttribute("success", "User " + created.getUsername() + " created. Now give them access below, then pass on the initial password"
                    + " (they must change it at first sign-in).");
            return "redirect:/admin/users/" + created.getId() + "/edit#access";
        }
        Role oldRole = accounts.find(form.getId()).getRole();
        AppUser updated = accounts.update(form, principal.getName());
        accessAdmin.roleChanged(updated, oldRole);
        flash.addFlashAttribute("success", "User " + updated.getUsername() + " updated."
                + (oldRole != updated.getRole() && updated.getRole() != Role.SUPER_ADMIN ? " The role changed: check their access." : ""));
        return oldRole != updated.getRole() ? "redirect:/admin/users/" + updated.getId() + "/edit#access" : "redirect:/admin/users";
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable long id, @RequestParam String temporaryPassword, Principal principal, RedirectAttributes flash) {
        manageableAccount(id);
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
    public String unlock(@PathVariable long id, Principal principal, RedirectAttributes flash) {
        manageableAccount(id);
        try {
            accounts.unlock(id, principal.getName());
            flash.addFlashAttribute("success", "User " + accounts.find(id).getUsername() + " unlocked.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    private String form(Model model, UserForm form, String actor) {
        model.addAttribute("pageTitle", form.isNew() ? "Create user" : "Edit user " + form.getUsername());
        model.addAttribute("form", form);
        // Only a super user can give the super user role.
        model.addAttribute("roles", accounts.assignableRoles(actor));
        model.addAttribute("minPasswordLength", accounts.minPasswordLength());
        return "admin/user-form";
    }
}
