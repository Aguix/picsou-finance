package com.picsou.service;

import com.picsou.dto.FamilyMemberRequest;
import com.picsou.dto.FamilyMemberResponse;
import com.picsou.dto.SharingSettingsRequest;
import com.picsou.dto.SharingSettingsResponse;
import com.picsou.model.*;
import com.picsou.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class FamilyService {

    private final FamilyMemberRepository memberRepository;
    private final AppUserRepository userRepository;
    private final UserMfaRepository userMfaRepository;
    private final SharingSettingsRepository sharingSettingsRepository;
    private final SharedResourceRepository sharedResourceRepository;
    private final PasswordEncoder passwordEncoder;

    public FamilyService(
        FamilyMemberRepository memberRepository,
        AppUserRepository userRepository,
        UserMfaRepository userMfaRepository,
        SharingSettingsRepository sharingSettingsRepository,
        SharedResourceRepository sharedResourceRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.userMfaRepository = userMfaRepository;
        this.sharingSettingsRepository = sharingSettingsRepository;
        this.sharedResourceRepository = sharedResourceRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<FamilyMemberResponse> listMembers() {
        return memberRepository.findAllByOrderByCreatedAtAsc().stream()
            .map(m -> {
                AppUser user = userRepository.findByMemberId(m.getId()).orElse(null);
                boolean mfaEnabled = user != null && userMfaRepository.findByUserId(user.getId())
                    .map(UserMfa::isEnabled)
                    .orElse(false);
                return FamilyMemberResponse.from(m, user, mfaEnabled);
            })
            .toList();
    }

    @Transactional
    public FamilyMemberResponse createManagedProfile(FamilyMemberRequest req) {
        FamilyMember member = FamilyMember.builder()
            .displayName(req.displayName())
            .avatarColor(req.avatarColor() != null ? req.avatarColor() : "#6366f1")
            .managed(true)
            .build();
        member = memberRepository.save(member);
        return FamilyMemberResponse.from(member, null, false);
    }

    @Transactional
    public FamilyMemberResponse updateDisplayName(Long id, String displayName) {
        FamilyMember member = memberRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        member.setDisplayName(displayName);
        member = memberRepository.save(member);
        AppUser user = userRepository.findByMemberId(id).orElse(null);
        boolean mfaEnabled = user != null && userMfaRepository.findByUserId(user.getId())
            .map(UserMfa::isEnabled)
            .orElse(false);
        return FamilyMemberResponse.from(member, user, mfaEnabled);
    }

    /**
     * Deletes a family member together with their login and all owned data
     * (accounts, goals, requisitions, bank sessions, wallets, debts…).
     *
     * <p>An activated member is no longer protected — the admin who runs the instance
     * may remove anyone. Two guards remain to keep the instance usable:
     * <ul>
     *   <li>an admin cannot delete their own member (would lock themselves out);</li>
     *   <li>the last remaining administrator cannot be deleted (no one left to administer).</li>
     * </ul>
     *
     * <p><b>Deletion order matters.</b> The member's {@link AppUser} is loaded into the
     * persistence context above (for the last-admin guard). {@code AppUser.member} is a
     * non-nullable {@code @OneToOne}, so deleting the member while that managed user still
     * references it makes Hibernate throw {@code TransientObjectException} at flush —
     * before any SQL runs, so the DB {@code ON DELETE CASCADE} never gets a chance. We
     * therefore delete the loaded {@code AppUser} first; the member delete then cascades
     * the remaining (unloaded) owned rows at the database level.
     *
     * @param requesterMemberId the member id of the admin performing the deletion
     */
    @Transactional
    public void deleteMember(Long id, Long requesterMemberId) {
        FamilyMember member = memberRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        if (id.equals(requesterMemberId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete your own account");
        }
        AppUser user = userRepository.findByMemberId(id).orElse(null);
        if (user != null && user.getRole() == UserRole.ADMIN
            && userRepository.countByRole(UserRole.ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete the last administrator");
        }
        if (user != null) {
            // Remove the only managed entity referencing the member before deleting it,
            // otherwise Hibernate fails the flush with TransientObjectException.
            userRepository.delete(user);
        }
        memberRepository.delete(member);
    }

    @Transactional
    public String resetPasswordToken(Long memberId) {
        memberRepository.findById(memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

        AppUser user = userRepository.findByMemberId(memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Member has no login to reset"));

        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);

        user.setActivationToken(token);
        user.setActivationTokenExpires(Instant.now().plus(7, ChronoUnit.DAYS));
        userRepository.save(user);

        return token;
    }

    @Transactional
    public String generateActivationToken(Long memberId) {
        FamilyMember member = memberRepository.findById(memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

        AppUser existingUser = userRepository.findByMemberId(memberId).orElse(null);
        if (existingUser != null && existingUser.isActivated()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This account already has an active login.");
        }

        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);

        if (existingUser != null) {
            existingUser.setActivationToken(token);
            existingUser.setActivationTokenExpires(Instant.now().plus(7, ChronoUnit.DAYS));
            userRepository.save(existingUser);
        } else {
            AppUser user = AppUser.builder()
                .username(deriveUsername(member.getDisplayName()))
                .passwordHash("")
                .member(member)
                .role(UserRole.MEMBER)
                .activated(false)
                .activationToken(token)
                .activationTokenExpires(Instant.now().plus(7, ChronoUnit.DAYS))
                .acknowledgedWarning(false)
                .build();
            userRepository.save(user);
        }

        return token;
    }

    /**
     * Derives a readable, login-safe username from a member's display name
     * (e.g. "Jean Dupont" -> "jean.dupont"), replacing the legacy "member_<id>"
     * scheme. Accents are stripped, non-alphanumeric runs collapse to a dot, and
     * a numeric suffix (".2", ".3", ...) guarantees uniqueness on collision.
     */
    private String deriveUsername(String displayName) {
        String base = Normalizer
            .normalize(displayName == null ? "" : displayName, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")        // strip accent marks
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", ".")     // any separator run -> single dot
            .replaceAll("^\\.+|\\.+$", "");    // trim leading/trailing dots
        if (base.isBlank()) {
            base = "user";
        }
        if (base.length() > 45) {
            base = base.substring(0, 45);      // leave room for a numeric suffix
        }
        String candidate = base;
        int n = 2;
        while (userRepository.existsByUsername(candidate)) {
            candidate = base + "." + n++;
        }
        return candidate;
    }

    public SharingSettingsResponse getSharingSettings(Long memberId, String resourceType) {
        SharingSettings settings = sharingSettingsRepository
            .findByMemberIdAndResourceType(memberId, resourceType)
            .orElseGet(() -> new SharingSettings(null, null, resourceType, SharingLevel.NONE));

        List<Long> sharedIds = List.of();
        if (settings.getSharingLevel() == SharingLevel.MANUAL) {
            sharedIds = sharedResourceRepository
                .findAllByOwnerMemberIdAndResourceType(memberId, resourceType).stream()
                .map(SharedResource::getResourceId)
                .toList();
        } else if (settings.getSharingLevel() == SharingLevel.ALL) {
            sharedIds = List.of(-1L);
        }

        return new SharingSettingsResponse(resourceType, settings.getSharingLevel(), sharedIds);
    }

    @Transactional
    public void updateSharingSettings(Long memberId, SharingSettingsRequest req) {
        SharingSettings settings = sharingSettingsRepository
            .findByMemberIdAndResourceType(memberId, req.resourceType())
            .orElseGet(() -> new SharingSettings(null, null, req.resourceType(), SharingLevel.NONE));

        FamilyMember member = memberRepository.findById(memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        if (settings.getMember() == null) {
            settings.setMember(member);
        }

        settings.setSharingLevel(req.sharingLevel());
        sharingSettingsRepository.save(settings);

        sharedResourceRepository.deleteAllByOwnerMemberIdAndResourceType(memberId, req.resourceType());

        if (req.sharingLevel() == SharingLevel.MANUAL && req.sharedResourceIds() != null) {
            for (Long resourceId : req.sharedResourceIds()) {
                SharedResource sr = SharedResource.builder()
                    .ownerMember(member)
                    .resourceType(req.resourceType())
                    .resourceId(resourceId)
                    .build();
                sharedResourceRepository.save(sr);
            }
        }
    }
}
