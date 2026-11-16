package com.tomcatmanager.application;

import com.tomcatmanager.access.Access;
import com.tomcatmanager.access.AccessScope;
import com.tomcatmanager.access.NotPermittedException;
import com.tomcatmanager.access.Team;
import com.tomcatmanager.access.TeamRepository;
import com.tomcatmanager.release.ReleaseRepository;
import jakarta.validation.Valid;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The application registry. Users see the applications of the teams they have access to; administrators of a team
 * register and change its applications. An application deploys only to Tomcat instances of its own team.
 */
@Controller
@RequestMapping("/applications")
public class ApplicationController {
    private final ApplicationService service;
    private final ReleaseRepository releases;
    private final Access access;
    private final TeamRepository teams;
    private final com.tomcatmanager.release.ReleaseService releaseService;

    public ApplicationController(ApplicationService service, ReleaseRepository releases, Access access, TeamRepository teams,
                                 com.tomcatmanager.release.ReleaseService releaseService) {
        this.service = service; this.releases = releases; this.access = access; this.teams = teams; this.releaseService = releaseService;
    }

    /** Per-application release count and most recent upload. */
    public record Stats(long releaseCount, Instant latestUpload) { }

    @GetMapping
    public String index(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String team, Model model) {
        Map<Long, Stats> stats = new HashMap<>();
        for (Object[] row : releases.statisticsByApplication()) stats.put((Long) row[0], new Stats((Long) row[1], (Instant) row[2]));
        com.tomcatmanager.access.AccessScope s = access.current();
        // Team filter and badges, when the user sees more than one team.
        java.util.List<com.tomcatmanager.access.Team> teamOptions = teams.findAllByOrderByNameAsc().stream().filter(t -> s.canViewTeam(t.getId())).toList();
        Long teamId = teamOptions.stream().map(com.tomcatmanager.access.Team::getId).filter(id -> id.toString().equals(team.strip())).findFirst().orElse(null);
        Map<Long, String> teamNames = new HashMap<>();
        teamOptions.forEach(t -> teamNames.put(t.getId(), t.getName()));
        model.addAttribute("teamOptions", teamOptions);
        model.addAttribute("teamNames", teamNames);
        model.addAttribute("showTeams", teamOptions.size() > 1);
        model.addAttribute("team", teamId == null ? "" : teamId.toString());
        model.addAttribute("pageTitle", "Applications");
        model.addAttribute("applications", service.searchVisible(q, s).stream().filter(a -> teamId == null || teamId.equals(a.getTeamId())).toList());
        model.addAttribute("stats", stats);
        model.addAttribute("noStats", new Stats(0, null));
        model.addAttribute("q", q);
        return "applications/index";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        AccessScope s = access.current();
        ManagedApplication app = service.findVisible(id, s);
        model.addAttribute("pageTitle", app.getName());
        model.addAttribute("app", app);
        model.addAttribute("canManage", s.canManageApplication(app.getTeamId()));
        model.addAttribute("appTeam", app.getTeamId() == null ? null : teams.findById(app.getTeamId()).orElse(null));
        model.addAttribute("nextVersion", releaseService.previewVersion(app));
        model.addAttribute("releases", releases.findByApplicationIdOrderByUploadedAtDesc(id));
        return "applications/detail";
    }

    @GetMapping("/new")
    public String create(Model model) {
        List<Team> mine = manageableTeams(access.current());
        if (mine.isEmpty()) throw new NotPermittedException("You need a team to register an application. Ask a super user to assign one to you.");
        ApplicationForm form = new ApplicationForm();
        if (mine.size() == 1) form.setTeamId(mine.get(0).getId());
        return form(model, form);
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable long id, Model model) { return form(model, ApplicationForm.from(service.findManageable(id, access.current()))); }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("form") ApplicationForm form, BindingResult result, Model model, Principal principal, RedirectAttributes flash) {
        AccessScope s = access.current();
        if (form.getId() != null) form.setTeamId(service.findManageable(form.getId(), s).getTeamId()); // an application stays in its team
        else if (form.getTeamId() == null) result.rejectValue("teamId", "required", "Choose the team this application belongs to.");
        else if (!s.canManageTeam(form.getTeamId())) result.rejectValue("teamId", "forbidden", "You cannot register applications in this team.");
        service.validate(form, result);
        if (result.hasErrors()) return form(model, form);
        try {
            ManagedApplication saved = service.save(form, principal.getName());
            flash.addFlashAttribute("success", form.getId() == null ? "Application registered. Upload its first release next." : "Application updated.");
            return "redirect:/applications/" + saved.getId();
        } catch (ObjectOptimisticLockingFailureException e) {
            result.reject("stale", "Someone else changed this application while you were editing. Reload the page to see their changes.");
            return form(model, form);
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, Principal principal, RedirectAttributes flash) {
        service.findManageable(id, access.current());
        try {
            service.delete(id, principal.getName());
            flash.addFlashAttribute("success", "Application deleted.");
            return "redirect:/applications";
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/applications/" + id;
        }
    }

    private List<Team> manageableTeams(AccessScope s) {
        return teams.findAllByOrderByNameAsc().stream().filter(t -> s.canManageTeam(t.getId())).toList();
    }

    private String form(Model model, ApplicationForm form) {
        model.addAttribute("pageTitle", form.getId() == null ? "Register application" : "Edit application");
        model.addAttribute("form", form);
        model.addAttribute("tomcatLines", ApplicationForm.TOMCAT_LINES);
        model.addAttribute("teams", manageableTeams(access.current()));
        model.addAttribute("versionPresets", com.tomcatmanager.release.VersionPattern.PRESETS);
        // For an existing application with automatic versions: what its next upload will be called.
        if (form.getId() != null) model.addAttribute("nextVersion", releaseService.previewVersion(service.find(form.getId())));
        model.addAttribute("appTeam", form.getTeamId() == null ? null : teams.findById(form.getTeamId()).orElse(null));
        return "applications/form";
    }
}
