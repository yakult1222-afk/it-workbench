package com.qinghuan.workbench.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;

@Getter
public class LoginUser implements UserDetails {
    private final Long id;
    private final String username;
    private final String password;
    private final String displayName;
    private final Long roleId;
    private final String roleCode;
    private final String roleName;
    private final boolean enabled;
    private final Set<String> permissions;

    public LoginUser(Long id, String username, String password, String displayName,
                     Long roleId, String roleCode, String roleName,
                     boolean enabled, Set<String> permissions) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.roleId = roleId;
        this.roleCode = roleCode;
        this.roleName = roleName;
        this.enabled = enabled;
        this.permissions = Set.copyOf(permissions);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream().map(SimpleGrantedAuthority::new).toList();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
