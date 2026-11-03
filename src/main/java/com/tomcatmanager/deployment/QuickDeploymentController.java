package com.tomcatmanager.deployment;

import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.application.ManagedApplication;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.release.ArtifactStorage;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.UUID;

/** Quick Deployment page: upload a WAR, pick instances, check connectivity, deploy. */
@Controller
public class QuickDeploymentController {
    private final ApplicationRepository applications;
    private final TomcatInstanceRepository instances;
    private final ArtifactStorage storage;

    public QuickDeploymentController(ApplicationRepository applications, TomcatInstanceRepository instances, ArtifactStorage storage) {
        this.applications = applications; this.instances = instances; this.storage = storage;
    }

    @GetMapping("/deployments/quick")
    public String page(Model model) {
        model.addAttribute("pageTitle", "Quick deployment");
        model.addAttribute("requestId", UUID.randomUUID());
        model.addAttribute("hasInstances", instances.count() > 0);
        model.addAttribute("applicationNames", applications.findAllByOrderByNameAsc().stream().map(ManagedApplication::getName).toList());
        model.addAttribute("maxBytes", storage.maxBytes());
        model.addAttribute("maxSize", ArtifactStorage.humanSize(storage.maxBytes()));
        return "deployments/quick";
    }
}
