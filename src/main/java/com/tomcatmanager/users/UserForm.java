package com.tomcatmanager.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Create/edit form for a user account. The password fields are used when creating (initial password). */
public class UserForm {
    private Long id;
    @Size(max = 60) private String username;
    @Size(max = 120) private String displayName;
    @Email(message = "Enter a valid email address.") @Size(max = 254) private String email;
    @NotNull(message = "Choose a role.") private Role role = Role.VIEWER;
    private boolean enabled = true;
    @Size(max = 128) private String password;
    @Size(max = 128) private String passwordConfirmation;
    private long version;

    public static UserForm from(AppUser u) {
        UserForm f = new UserForm();
        f.id = u.getId(); f.username = u.getUsername(); f.displayName = u.getDisplayName(); f.email = u.getEmail();
        f.role = u.getRole(); f.enabled = u.isEnabled(); f.version = u.getVersion();
        return f;
    }

    public boolean isNew() { return id == null; }
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public String getUsername() { return username; } public void setUsername(String v) { username = v == null ? null : v.strip().toLowerCase(); }
    public String getDisplayName() { return displayName; } public void setDisplayName(String v) { displayName = v; }
    public String getEmail() { return email; } public void setEmail(String v) { email = v == null || v.isBlank() ? null : v.strip(); }
    public Role getRole() { return role; } public void setRole(Role v) { role = v; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
    public String getPassword() { return password; } public void setPassword(String v) { password = v; }
    public String getPasswordConfirmation() { return passwordConfirmation; } public void setPasswordConfirmation(String v) { passwordConfirmation = v; }
    public long getVersion() { return version; } public void setVersion(long v) { version = v; }
}
