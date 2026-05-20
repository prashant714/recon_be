package com.reconciliation.user;

import com.reconciliation.user.entity.User;
import com.reconciliation.user.service.UserPersistenceService;
import com.reconciliation.user.repository.UserRepository;
import com.reconciliation.user.service.UserIdentityService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserIdentityServiceTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final UserPersistenceService persistenceService = mock(UserPersistenceService.class);
    private final UserIdentityService service = new UserIdentityService(repository, persistenceService);

    @Test
    void backfillsNameWhenExistingUserMatchesByPhone() {
        User existing = User.builder()
                .id(7L)
                .merchantId("merchant_001")
                .phone("+917667818015")
                .email("payer@example.com")
                .name(null)
                .firstSeenAt(OffsetDateTime.now())
                .lastSeenAt(OffsetDateTime.now())
                .build();

        when(repository.findByMerchantIdAndEmail("merchant_001", "shrey@example.com"))
                .thenReturn(Optional.empty());
        when(repository.findByMerchantIdAndPhone("merchant_001", "+917667818015"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Long userId = service.resolveUserId(
                "merchant_001",
                "shrey@example.com",
                "+917667818015",
                "Shrey Mishra");

        assertThat(userId).isEqualTo(7L);
        assertThat(existing.getName()).isEqualTo("Shrey Mishra");
        verify(repository).save(existing);
    }

    @Test
    void returnsExistingUserWhenCreateRacesOnUniqueEmail() {
        when(repository.findByMerchantIdAndEmail("merchant_001", "mayank@example.com"))
                .thenReturn(Optional.empty());
        when(repository.findByMerchantIdAndPhone("merchant_001", "+918182800097"))
                .thenReturn(Optional.empty());
        when(persistenceService.createUser("merchant_001", "mayank@example.com", "+918182800097", "Mayank Gaur"))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
        when(persistenceService.findExistingUserId("merchant_001", "mayank@example.com", "+918182800097"))
                .thenReturn(5L);

        Long userId = service.resolveUserId(
                "merchant_001",
                "mayank@example.com",
                "+918182800097",
                "Mayank Gaur");

        assertThat(userId).isEqualTo(5L);
    }
}
