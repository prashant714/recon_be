package com.reconciliation.merchant;

import com.reconciliation.config.JwtConfig;
import com.reconciliation.merchant.entity.Merchant;
import com.reconciliation.merchant.repository.MerchantRepository;
import com.reconciliation.merchant.service.MerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MerchantServiceTest {

    private MerchantRepository merchantRepository;
    private JwtConfig jwtConfig;
    private BCryptPasswordEncoder passwordEncoder;
    private MerchantService service;

    @BeforeEach
    void setUp() {
        merchantRepository = mock(MerchantRepository.class);
        jwtConfig = mock(JwtConfig.class);
        passwordEncoder = new BCryptPasswordEncoder(); // real encoder — hashing is the point

        service = new MerchantService(merchantRepository, jwtConfig, passwordEncoder);
    }

    // ─── Registration ─────────────────────────────────────────────────────────

    @Test
    void register_savesHashedKeyAndReturnsMaskedRawKey() {
        when(merchantRepository.existsByMerchantId("zomato")).thenReturn(false);
        when(merchantRepository.existsByEmail("ops@zomato.com")).thenReturn(false);
        when(merchantRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Map<String, String> result = service.register("zomato", "Zomato Ltd", "ops@zomato.com");

        assertThat(result.get("merchantId")).isEqualTo("zomato");
        String rawKey = result.get("apiKey");
        assertThat(rawKey).isNotBlank();
        assertThat(result.get("webhookSecret")).isNotBlank();
        assertThat(result.get("note")).contains("not be shown again");

        // captured merchant must have bcrypt hash, not the raw key
        verify(merchantRepository).save(argThat(m ->
                m.getApiKeyHash() != null
                        && !m.getApiKeyHash().equals(rawKey)
                        && m.getWebhookSecret() != null
                        && passwordEncoder.matches(rawKey, m.getApiKeyHash())));
    }

    @Test
    void register_duplicateMerchantId_throwsIllegalArgument() {
        when(merchantRepository.existsByMerchantId("zomato")).thenReturn(true);

        assertThatThrownBy(() -> service.register("zomato", "Zomato", "new@zomato.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");

        verify(merchantRepository, never()).save(any());
    }

    @Test
    void register_duplicateEmail_throwsIllegalArgument() {
        when(merchantRepository.existsByMerchantId("swiggy")).thenReturn(false);
        when(merchantRepository.existsByEmail("ops@zomato.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register("swiggy", "Swiggy", "ops@zomato.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email already registered");

        verify(merchantRepository, never()).save(any());
    }

    // ─── Authentication ───────────────────────────────────────────────────────

    @Test
    void authenticate_validKey_returnsJwtToken() {
        String rawKey = "some_raw_api_key_value";
        String hash = passwordEncoder.encode(rawKey);

        Merchant merchant = activeMerchant("zomato", hash);
        when(merchantRepository.findByMerchantId("zomato")).thenReturn(Optional.of(merchant));
        when(jwtConfig.generateMerchantToken("zomato")).thenReturn("jwt.token.here");

        String token = service.authenticate("zomato", rawKey);

        assertThat(token).isEqualTo("jwt.token.here");
        verify(jwtConfig).generateMerchantToken("zomato");
    }

    @Test
    void authenticate_invalidKey_throwsIllegalArgument() {
        String hash = passwordEncoder.encode("correct_key");
        Merchant merchant = activeMerchant("zomato", hash);
        when(merchantRepository.findByMerchantId("zomato")).thenReturn(Optional.of(merchant));

        assertThatThrownBy(() -> service.authenticate("zomato", "wrong_key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid API key");

        verify(jwtConfig, never()).generateMerchantToken(any());
    }

    @Test
    void authenticate_unknownMerchant_throwsIllegalArgument() {
        when(merchantRepository.findByMerchantId("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate("ghost", "any_key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown merchant");
    }

    @Test
    void authenticate_suspendedMerchant_throwsIllegalState() {
        String hash = passwordEncoder.encode("key");
        Merchant merchant = activeMerchant("zomato", hash);
        merchant.setStatus("SUSPENDED");
        when(merchantRepository.findByMerchantId("zomato")).thenReturn(Optional.of(merchant));

        assertThatThrownBy(() -> service.authenticate("zomato", "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not active");

        verify(jwtConfig, never()).generateMerchantToken(any());
    }

    // ─── Get by ID ───────────────────────────────────────────────────────────

    @Test
    void getByMerchantId_existingMerchant_returnsMerchant() {
        Merchant merchant = activeMerchant("zomato", "hash");
        when(merchantRepository.findByMerchantId("zomato")).thenReturn(Optional.of(merchant));

        Merchant result = service.getByMerchantId("zomato");

        assertThat(result.getMerchantId()).isEqualTo("zomato");
    }

    @Test
    void getByMerchantId_missingMerchant_throwsIllegalArgument() {
        when(merchantRepository.findByMerchantId("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByMerchantId("nobody"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Merchant not found");
    }

    // ─── helper ──────────────────────────────────────────────────────────────

    private Merchant activeMerchant(String merchantId, String apiKeyHash) {
        return Merchant.builder()
                .merchantId(merchantId)
                .name("Test Merchant")
                .email(merchantId + "@test.com")
                .apiKeyHash(apiKeyHash)
                .status("ACTIVE")
                .build();
    }
}
