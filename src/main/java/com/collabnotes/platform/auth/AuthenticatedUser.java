package com.collabnotes.platform.auth;

import java.util.Collection;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.collabnotes.platform.user.UserCredentials;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** 登录成功后 ProviderManager 会擦除密码哈希，会话只保留身份。 */
public final class AuthenticatedUser implements UserDetails, CredentialsContainer {
    private final long id;
    private final String username;
    private String passwordHash;

    public AuthenticatedUser(UserCredentials credentials) {
        this.id = credentials.id();
        this.username = credentials.username();
        this.passwordHash = credentials.passwordHash();
    }

    public long getId() { return id; }
    @Override public String getUsername() { return username; }
    @Override @JsonIgnore public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        // 统一的已登录用户标识；尚未引入业务角色体系。
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }
    @Override public void eraseCredentials() { passwordHash = null; }
    @Override public String toString() { return "AuthenticatedUser[id=" + id + "]"; }
}
