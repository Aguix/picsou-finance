package com.picsou.controller;

import com.picsou.crypto.CryptoImportRequest;
import com.picsou.crypto.CryptoImportResult;
import com.picsou.crypto.CryptoImportService;
import com.picsou.crypto.CryptoPreviewResponse;
import com.picsou.crypto.CryptoSourceInfo;
import com.picsou.service.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Multi-exchange crypto CSV import. The uploaded file's format is auto-detected against the
 * registered {@link com.picsou.crypto.CryptoCsvParser}s. All endpoints are member-scoped via
 * {@link UserContext}; an access-key principal acts only on its owner's data.
 */
@RestController
@RequestMapping("/api/crypto")
@RequiredArgsConstructor
public class CryptoController {

    private final CryptoImportService importService;
    private final UserContext userContext;

    /** The supported CSV source formats, for the import UI. */
    @GetMapping("/sources")
    public List<CryptoSourceInfo> sources() {
        return importService.sources();
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CryptoPreviewResponse preview(@RequestParam("file") MultipartFile file) {
        return importService.preview(file, userContext.currentMemberId());
    }

    @PostMapping("/import")
    public CryptoImportResult importData(@Valid @RequestBody CryptoImportRequest request) {
        return importService.execute(request, userContext.currentMemberId());
    }
}
