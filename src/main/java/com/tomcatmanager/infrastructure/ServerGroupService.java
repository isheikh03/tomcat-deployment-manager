package com.tomcatmanager.infrastructure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;

import java.util.List;
import java.util.NoSuchElementException;

/** Server groups and their membership. A Tomcat instance can be a member of a group only once. */
@Service
public class ServerGroupService {
    private final ServerGroupRepository groups;
    private final TomcatInstanceRepository instances;

    public ServerGroupService(ServerGroupRepository groups, TomcatInstanceRepository instances) {
        this.groups = groups; this.instances = instances;
    }

    public ServerGroup find(long id) { return groups.findById(id).orElseThrow(() -> new NoSuchElementException("Server group " + id + " not found.")); }

    public void validate(ServerGroup group, Errors errors) {
        if (group.getName() == null || group.getName().isBlank()) return;
        groups.findAll().stream()
                .filter(g -> g.getName().equalsIgnoreCase(group.getName().trim()) && !g.getId().equals(group.getId()))
                .findFirst().ifPresent(g -> errors.rejectValue("name", "duplicate", "A server group with this name already exists."));
    }

    @Transactional
    public ServerGroup save(ServerGroup group) {
        if (group.getId() == null) return groups.save(group);
        ServerGroup existing = find(group.getId());
        existing.setName(group.getName()); existing.setDescription(group.getDescription());
        return groups.save(existing);
    }

    @Transactional
    public void delete(long id) { groups.delete(find(id)); }

    public List<TomcatInstance> members(long groupId) {
        return instances.findAllByOrderByNameAsc().stream().filter(i -> i.getGroups().stream().anyMatch(g -> g.getId().equals(groupId))).toList();
    }

    public List<TomcatInstance> nonMembers(long groupId) {
        return instances.findAllByOrderByNameAsc().stream().filter(i -> i.getGroups().stream().noneMatch(g -> g.getId().equals(groupId))).toList();
    }

    /** Adds an instance to a group; refuses a duplicate membership. */
    @Transactional
    public TomcatInstance addMember(long groupId, long instanceId) {
        ServerGroup group = find(groupId);
        TomcatInstance instance = instances.findById(instanceId).orElseThrow(() -> new NoSuchElementException("Tomcat instance " + instanceId + " not found."));
        if (instance.getGroups().stream().anyMatch(g -> g.getId().equals(groupId)))
            throw new IllegalStateException(instance.getName() + " is already a member of " + group.getName() + ".");
        instance.getGroups().add(group);
        return instances.save(instance);
    }

    @Transactional
    public TomcatInstance removeMember(long groupId, long instanceId) {
        ServerGroup group = find(groupId);
        TomcatInstance instance = instances.findById(instanceId).orElseThrow(() -> new NoSuchElementException("Tomcat instance " + instanceId + " not found."));
        if (!instance.getGroups().removeIf(g -> g.getId().equals(groupId)))
            throw new IllegalStateException(instance.getName() + " is not a member of " + group.getName() + ".");
        return instances.save(instance);
    }
}
