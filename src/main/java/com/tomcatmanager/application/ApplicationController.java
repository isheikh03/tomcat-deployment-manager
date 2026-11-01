package com.tomcatmanager.application;

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
import java.util.Map;

@Controller
@RequestMapping("/applications")
public class ApplicationController {
    private final ApplicationService service;
    private final ReleaseRepository releases;

    public ApplicationController(ApplicationService service, ReleaseRepository releases) {
        this.service = service; this.releases = releases;
    }

    /** Per-application release count and most recent upload. */
    public record Stats(long releaseCount, Instant latestUpload) { }

    @GetMapping
    public String index(@RequestParam(defaultValue = "") String q, Model model) {
        Map<Long, Stats> stats = new HashMap<>();
        for (Object[] row : releases.statisticsByApplication()) stats.put((Long) row[0], new Stats((Long) row[1], (Instant) row[2]));
        model.addAttribute("pageTitle", "Applications");
        model.addAttribute("applications", service.search(q));
        model.addAttribute("stats", stats);
        model.addAttribute("noStats", new Stats(0, null));
        model.addAttribute("q", q);
        return "applications/index";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        ManagedApplication app = service.find(id);
        model.addAttribute("pageTitle", app.getName());
        model.addAttribute("app", app);
        model.addAttribute("releases", releases.findByApplicationIdOrderByUploadedAtDesc(id));
        return "applications/detail";
    }

    @GetMapping("/new")
    public String create(Model model) { return form(model, new ApplicationForm()); }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable long id, Model model) { return form(model, ApplicationForm.from(service.find(id))); }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("form") ApplicationForm form, BindingResult result, Model model, Principal principal, RedirectAttributes flash) {
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
        try {
            service.delete(id, principal.getName());
            flash.addFlashAttribute("success", "Application deleted.");
            return "redirect:/applications";
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/applications/" + id;
        }
    }

    private String form(Model model, ApplicationForm form) {
        model.addAttribute("pageTitle", form.getId() == null ? "Register application" : "Edit application");
        model.addAttribute("form", form);
        model.addAttribute("tomcatLines", ApplicationForm.TOMCAT_LINES);
        return "applications/form";
    }
}
