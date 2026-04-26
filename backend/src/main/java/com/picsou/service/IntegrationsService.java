package com.picsou.service;

import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Toggles {@code integration.{key}.enabled} flags in {@code app_setting}.
 * The "intent → config → enable" ordering is deliberate: each substep's
 * *success* endpoint is what actually flips the flag to true. The Step 3
 * picker only records user intent in the frontend store; if the user abandons
 * a substep the flag stays false and the post-setup app stays consistent.
 */
@Service
public class IntegrationsService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationsService.class);

    private final AppSettingRepository settingRepository;

    public IntegrationsService(AppSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @Transactional
    public void enable(String key) {
        assertKnown(key);
        upsert(SetupService.integrationKey(key), "true");
        log.info("setup.integration.enabled key={}", key);
    }

    @Transactional
    public void disable(String key) {
        assertKnown(key);
        upsert(SetupService.integrationKey(key), "false");
        log.info("setup.integration.disabled key={}", key);
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String key) {
        assertKnown(key);
        return settingRepository.findByKey(SetupService.integrationKey(key))
            .map(s -> Boolean.parseBoolean(s.getValue()))
            .orElse(false);
    }

    private void assertKnown(String key) {
        if (!SetupService.INTEGRATIONS.contains(key)) {
            throw new IllegalArgumentException("Unknown integration: " + key);
        }
    }

    private void upsert(String key, String value) {
        AppSetting setting = settingRepository.findByKey(key)
            .orElseGet(() -> AppSetting.builder().key(key).build());
        setting.setValue(value);
        settingRepository.save(setting);
    }
}
