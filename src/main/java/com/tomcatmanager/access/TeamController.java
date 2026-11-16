package com.tomcatmanager.access;

import com.tomcatmanager.infrastructure.ManagedHostRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.NoSuchElementException;

/**
 * Teams. Super users create, rename and delete them (also enforced in the security configuration); an administrator
 * sees the teams assigned to them and who has access to each.
 */
@Controller
@RequestMapping("/admin/teams")
public class TeamController {
    private final AccessAdminService service;
    private final Access access;
    private final ManagedHostRepository hosts;

    public TeamController(AccessAdminService service, Access access, ManagedHostRepository hosts) {
        this.service = service; this.access = access; this.hosts = hosts;
    }

    @GetMapping
    public String index(Model model) {
        AccessScope s = access.current();
        model.addAttribute("pageTitle", "Teams");
        model.addAttribute("rows", service.teamRows(s));
        model.addAttribute("superUser", s.isSuperUser());
        return "admin/teams";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        AccessScope s = access.current();
        if (!s.canManageTeam(id)) throw new NoSuchElementException("Team " + id + " not found.");
        Team team = service.team(id);
        model.addAttribute("pageTitle", team.getName());
        model.addAttribute("team", team);
        model.addAttribute("row", service.teamRows(s).stream().filter(r -> r.team().getId().equals(id)).findFirst().orElse(null));
        model.addAttribute("holders", service.holdersOfTeam(id));
        model.addAttribute("hosts", hosts.findAllByOrderByNameAsc().stream().filter(h -> id == h.getTeamId()).toList());
        model.addAttribute("superUser", s.isSuperUser());
        return "admin/team-detail";
    }

    @GetMapping("/new")
    public String create(Model model) {
        return form(model, null, "", "", null);
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        Team team = service.team(id);
        return form(model, team.getId(), team.getName(), team.getDescription(), null);
    }

    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long id, @RequestParam(defaultValue = "") String name,
                       @RequestParam(required = false) String description, Principal principal, Model model, RedirectAttributes flash) {
        try {
            Team saved = service.saveTeam(id, name, description, principal.getName());
            flash.addFlashAttribute("success", id == null
                    ? "Team " + saved.getName() + " created. Assign it to an administrator on the Users page; they can then add its Linux hosts."
                    : "Team " + saved.getName() + " saved.");
            return "redirect:/admin/teams/" + saved.getId();
        } catch (IllegalArgumentException e) {
            return form(model, id, name, description, e.getMessage());
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, RedirectAttributes flash) {
        String name = service.team(id).getName();
        try {
            service.deleteTeam(id);
            flash.addFlashAttribute("success", "Team " + name + " deleted.");
            return "redirect:/admin/teams";
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/teams/" + id;
        }
    }

    private String form(Model model, Long id, String name, String description, String error) {
        model.addAttribute("pageTitle", id == null ? "Create team" : "Edit team");
        model.addAttribute("teamId", id);
        model.addAttribute("name", name);
        model.addAttribute("description", description);
        model.addAttribute("nameError", error);
        return "admin/team-form";
    }
}
