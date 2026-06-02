package com.picsou.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.finary")
public class FinaryProperties {

    private String email;
    private String password;
    private boolean useCurlFallback;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isUseCurlFallback() {
        return useCurlFallback;
    }

    public void setUseCurlFallback(boolean useCurlFallback) {
        this.useCurlFallback = useCurlFallback;
    }
}
