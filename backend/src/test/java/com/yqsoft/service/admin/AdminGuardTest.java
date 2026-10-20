package com.yqsoft.service.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.resource.admin.AdminDtos.*;
import com.yqsoft.resource.common.Rows;
import com.yqsoft.security.*;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;

import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.*;
import java.util.function.Supplier;

class AdminGuardTest {
    private final SqlStore sql = mock(SqlStore.class);
    private final MutationExecutor mutations = mock(MutationExecutor.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private AdminService service;

    @BeforeEach
    void setup() {
        PlatformTransactionManager tx =
                new PlatformTransactionManager() {
                    public TransactionStatus getTransaction(TransactionDefinition definition) {
                        return new SimpleTransactionStatus();
                    }

                    public void commit(TransactionStatus status) {}

                    public void rollback(TransactionStatus status) {}
                };
        service =
                new AdminService(
                        sql,
                        mutations,
                        mock(CursorService.class),
                        mock(CryptoService.class),
                        mapper,
                        new MockEnvironment(),
                        tx);
        AuthContext.set(
                new AuthenticatedUser(
                        1, 5, 3, "ADMIN", Set.of("user.security", "rbac.manage", "config.manage")));
        when(sql.one(anyString(), anyLong()))
                .thenReturn(
                        Rows.map(
                                "id",
                                1L,
                                "row_version",
                                1L,
                                "status",
                                "ACTIVE",
                                "security_version",
                                1L,
                                "data_write_fence",
                                "OPEN",
                                "role_code",
                                "CUSTOM"));
        when(sql.count(startsWith("SELECT COUNT(*) FROM auth_session"), eq(5L), eq(1L)))
                .thenReturn(1L);
        when(sql.query(startsWith("SELECT DISTINCT p.permission_code"), eq(1L)))
                .thenReturn(
                        List.of(
                                Rows.map("permission_code", "user.security"),
                                Rows.map("permission_code", "rbac.manage"),
                                Rows.map("permission_code", "config.manage")));
        when(mutations.mutate(anyString(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(3).get());
    }

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    @Test
    void cannotLockLastEffectiveSystemAdministrator() {
        when(sql.count(startsWith("SELECT COUNT(DISTINCT a.user_id)"), eq(1L))).thenReturn(1L);
        when(sql.count(startsWith("SELECT COUNT(DISTINCT a.user_id)"))).thenReturn(1L);
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                service.security(
                                        1, new UserSecurityCommand("1", "交接测试", true), true));
        assertEquals("LAST_ADMIN_PROTECTED", error.getCode());
        verify(sql, never()).update(anyString(), anyLong(), anyLong(), anyMap());
        var order = inOrder(sql);
        order.verify(sql)
                .one(
                        "SELECT id FROM sys_role WHERE role_code='SYSTEM_ADMIN' AND deleted_at IS"
                                + " NULL FOR UPDATE");
        order.verify(sql).one("SELECT * FROM user_sync_clock WHERE user_id=? FOR UPDATE", 1L);
    }

    @Test
    void cannotGrantPermissionsBeyondCurrentActorCeiling() {
        when(sql.one(startsWith("SELECT permission_code FROM sys_permission"), eq(9L)))
                .thenReturn(Rows.map("permission_code", "content.publish"));
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () -> service.putPermissions(1, new RolePermissionsPut("1", List.of("9"))));
        assertEquals("GRANT_EXCEEDS_AUTHORITY", error.getCode());
        verify(sql, never()).execute(startsWith("DELETE FROM role_permission"), anyLong());
    }

    @Test
    void configurationRejectsValuesFailingStoredSchema() {
        when(sql.find(
                        startsWith("SELECT * FROM app_configuration"),
                        eq("DEV"),
                        eq("feature.ai_plan")))
                .thenReturn(
                        Optional.of(
                                Rows.map(
                                        "id",
                                        12L,
                                        "row_version",
                                        1L,
                                        "scope",
                                        "PUBLIC",
                                        "value_type",
                                        "BOOLEAN",
                                        "schema_json",
                                        mapper.createObjectNode().put("type", "object"))));
        when(sql.count(startsWith("SELECT JSON_SCHEMA_VALID"), anyString(), anyString()))
                .thenReturn(0L);
        var request =
                new ConfigurationPut(
                        "1",
                        "DEV",
                        "PUBLIC",
                        "BOOLEAN",
                        mapper.createObjectNode().put("booleanValue", "wrong-type"),
                        "测试说明",
                        true,
                        null,
                        "测试原因");
        BusinessException error =
                assertThrows(
                        BusinessException.class,
                        () -> service.putConfiguration("feature.ai_plan", request));
        assertEquals("CONFIG_SCHEMA_INVALID", error.getCode());
        verify(sql, never()).update(anyString(), anyLong(), anyLong(), anyMap());
    }
}
