package com.yqsoft.listener;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.yqsoft.dao.SqlStore;
import com.yqsoft.resource.common.Rows;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

class AdminBootstrapInitializerTest {
    private final SqlStore sql = mock(SqlStore.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    private final PlatformTransactionManager manager =
            new PlatformTransactionManager() {
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                public void commit(TransactionStatus status) {}

                public void rollback(TransactionStatus status) {}
            };

    @Test
    void noDeploymentOptInDoesNotAccessDatabase() {
        initializer(new MockEnvironment()).run(arguments());
        verifyNoInteractions(sql);
    }

    @Test
    void arbitraryRoleCodesAreRejectedBeforeWriting() {
        var environment =
                configured()
                        .withProperty(
                                "zhiheng.bootstrap.additional-role-codes", "CUSTOM_SUPERUSER");
        assertThrows(IllegalStateException.class, () -> initializer(environment).run(arguments()));
        verifyNoInteractions(sql);
    }

    @Test
    void anotherEffectiveAdministratorBlocksBootstrapAndLocksGlobalGuardFirst() {
        prepare();
        when(sql.count(
                        startsWith("SELECT COUNT(*) FROM user_role_assignment a JOIN app_user"),
                        eq(10L),
                        any(Instant.class),
                        any(Instant.class)))
                .thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> initializer(configured()).run(arguments()));
        verify(sql, never()).insert(anyString(), anyMap());
        var locks = inOrder(sql);
        locks.verify(sql)
                .one(
                        "SELECT * FROM sys_role WHERE role_code='SYSTEM_ADMIN' AND deleted_at IS"
                                + " NULL FOR UPDATE");
        locks.verify(sql).one("SELECT * FROM user_sync_clock WHERE user_id=? FOR UPDATE", 1L);
        locks.verify(sql).one("SELECT * FROM app_user WHERE id=? FOR UPDATE", 1L);
    }

    @Test
    void firstAdministratorUsesExistingAccountAndAuditsOnlyRoleCodes() {
        prepare();
        when(sql.one(startsWith("SELECT id FROM sys_role WHERE role_code=?"), eq("CONTENT_EDITOR")))
                .thenReturn(Rows.map("id", 11L));
        when(sql.insert(eq("user_role_assignment"), anyMap())).thenReturn(100L, 101L);
        initializer(
                        configured()
                                .withProperty(
                                        "zhiheng.bootstrap.additional-role-codes",
                                        "CONTENT_EDITOR"))
                .run(arguments());
        verify(sql, times(2)).insert(eq("user_role_assignment"), anyMap());
        verify(sql, never()).insert(eq("app_user"), anyMap());
        verify(sql)
                .execute(
                        startsWith("UPDATE app_user SET security_version=security_version+1"),
                        eq(clock.instant()),
                        eq(1L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(sql).insert(eq("audit_log"), audit.capture());
        assertEquals("WORKER", audit.getValue().get("actor_type"));
        assertNull(audit.getValue().get("actor_user_id"));
        assertEquals("BOOTSTRAP_ADMIN", audit.getValue().get("action_code"));
        assertEquals(
                Map.of("roleCodes", java.util.Set.of("SYSTEM_ADMIN", "CONTENT_EDITOR")),
                audit.getValue().get("metadata_json"));
    }

    @Test
    void accountUnderPrivacyFenceCannotBecomeAdministrator() {
        prepare();
        when(sql.one("SELECT * FROM app_user WHERE id=? FOR UPDATE", 1L))
                .thenReturn(Rows.map("id", 1L, "status", "ACTIVE", "data_write_fence", "ERASING"));
        assertThrows(IllegalStateException.class, () -> initializer(configured()).run(arguments()));
        verify(sql, never()).insert(anyString(), anyMap());
    }

    @Test
    void historicalAssignmentIsNeverSilentlyResurrected() {
        prepare();
        when(sql.count(
                        "SELECT COUNT(*) FROM user_role_assignment WHERE user_id=? AND role_id=?",
                        1L,
                        10L))
                .thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> initializer(configured()).run(arguments()));
        verify(sql, never()).insert(anyString(), anyMap());
    }

    private void prepare() {
        when(sql.one(
                        "SELECT * FROM sys_role WHERE role_code='SYSTEM_ADMIN' AND deleted_at IS"
                                + " NULL FOR UPDATE"))
                .thenReturn(Rows.map("id", 10L, "status", "ACTIVE", "deleted_at", null));
        when(sql.one("SELECT * FROM user_sync_clock WHERE user_id=? FOR UPDATE", 1L))
                .thenReturn(Rows.map("user_id", 1L));
        when(sql.one("SELECT * FROM app_user WHERE id=? FOR UPDATE", 1L))
                .thenReturn(
                        Rows.map(
                                "id",
                                1L,
                                "status",
                                "ACTIVE",
                                "data_write_fence",
                                "OPEN",
                                "deleted_at",
                                null));
    }

    private MockEnvironment configured() {
        return new MockEnvironment().withProperty("zhiheng.bootstrap.admin-user-id", "1");
    }

    private AdminBootstrapInitializer initializer(MockEnvironment environment) {
        return new AdminBootstrapInitializer(sql, clock, environment, manager);
    }

    private DefaultApplicationArguments arguments() {
        return new DefaultApplicationArguments(new String[0]);
    }
}
