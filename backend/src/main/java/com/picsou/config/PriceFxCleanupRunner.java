package com.picsou.config;

import com.picsou.model.AppSetting;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.PriceSnapshotRepository;
import com.picsou.service.PriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * One-shot startup purge of price_snapshot when the FX-conversion fix is
 * deployed. Before the fix, snapshots stored Yahoo's native-currency price
 * as if it were EUR (e.g. ¥3000 for 8729.T treated as €3000), so every row
 * is suspect. We wipe the table and let {@link PriceBackfillRunner} rebuild
 * the last 12 months with correct EUR values.
 *
 * Runs before {@link PriceBackfillRunner} (which has the default
 * LOWEST_PRECEDENCE) via {@code @Order(0)}. Idempotent through the
 * {@code price.fx_fix_cleanup_done} app_setting row created in V31.
 */
@Component
@Order(0)
public class PriceFxCleanupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PriceFxCleanupRunner.class);
    private static final String FLAG_KEY = "price.fx_fix_cleanup_done";

    private final AppSettingRepository appSettingRepository;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final PriceService priceService;

    public PriceFxCleanupRunner(AppSettingRepository appSettingRepository,
                                PriceSnapshotRepository priceSnapshotRepository,
                                PriceService priceService) {
        this.appSettingRepository = appSettingRepository;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.priceService = priceService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Optional<AppSetting> flag = appSettingRepository.findByKey(FLAG_KEY);
        if (flag.isEmpty()) {
            log.debug("Cleanup gate flag '{}' missing (migration V31 not applied yet?), skipping", FLAG_KEY);
            return;
        }
        if ("true".equalsIgnoreCase(flag.get().getValue())) {
            log.debug("Price FX cleanup already done — skipping");
            return;
        }

        log.warn("Purging price_snapshot for FX fix — Yahoo prices stored before the FX fix are in native currency, not EUR");
        int purged = priceSnapshotRepository.deleteAllSnapshots();
        priceService.clearPriceCache();

        int updated = appSettingRepository.compareAndSet(FLAG_KEY, "false", "true");
        if (updated == 0) {
            // Another instance won the race. We still purged successfully —
            // that's safe because the table is now empty either way and
            // PriceBackfillRunner will refill it.
            log.info("Cleanup flag was already flipped by another instance; purged {} rows anyway", purged);
        } else {
            log.info("Purged {} price_snapshot rows; PriceBackfillRunner will rebuild history with FX-corrected values", purged);
        }
    }
}
