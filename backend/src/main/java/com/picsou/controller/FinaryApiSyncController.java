package com.picsou.controller;

import com.picsou.dto.FinaryApiSyncExecuteRequest;
import com.picsou.dto.FinaryAutoSyncResponse;
import com.picsou.dto.FinaryConnectionStatusResponse;
import com.picsou.dto.FinaryImportResultResponse;
import com.picsou.dto.FinaryLoginRequest;
import com.picsou.dto.FinaryPreviewResponse;
import com.picsou.finary.FinaryApiSyncService;
import com.picsou.service.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for Finary API direct sync (two-phase: preview + execute)
 */
@RestController
@RequestMapping("/api/finary")
@RequiredArgsConstructor
public class FinaryApiSyncController {

    private final FinaryApiSyncService finaryApiSyncService;
    private final UserContext userContext;

    /**
     * Get current Finary connection status
     */
    @GetMapping("/status")
    public FinaryConnectionStatusResponse getStatus() {
        return finaryApiSyncService.getConnectionStatus(userContext.currentMemberId());
    }

    /**
     * Store Finary credentials (encrypted)
     */
    @PostMapping("/login")
    public void login(@RequestBody FinaryLoginRequest request) {
        finaryApiSyncService.login(request.email(), request.password(), userContext.currentMemberId());
    }

    /**
     * Delete stored Finary session
     */
    @DeleteMapping("/session")
    public ResponseEntity<Void> deleteSession() {
        finaryApiSyncService.deleteSession(userContext.currentMemberId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Preview phase: authenticate, fetch accounts + transactions, return preview for mapping
     */
    @PostMapping("/api-sync/preview")
    public FinaryPreviewResponse apiSyncPreview(@RequestParam(required = false) String totp) {
        return finaryApiSyncService.preview(totp, userContext.currentMemberId());
    }

    /**
     * Execute phase: apply user mappings and import accounts + transactions
     */
    @PostMapping("/api-sync/execute")
    public FinaryImportResultResponse apiSyncExecute(@RequestBody FinaryApiSyncExecuteRequest request) {
        return finaryApiSyncService.execute(request.syncToken(), request.mappings(), userContext.currentMemberId());
    }

    /**
     * Auto-sync: runs preview + execute in one step if all accounts are already mapped.
     * Returns NEEDS_MAPPING if new accounts are discovered (user must go through mapping UI).
     */
    @PostMapping("/api-sync/auto")
    public FinaryAutoSyncResponse apiSyncAuto() {
        return finaryApiSyncService.autoSync(userContext.currentMemberId());
    }
}
