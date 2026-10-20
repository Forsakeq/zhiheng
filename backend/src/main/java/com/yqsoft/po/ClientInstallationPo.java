package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户端安装与同步租约。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("client_installation")
public class ClientInstallationPo {

    /** 内部主键；API使用十进制字符串 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端写入UTC创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 服务端写入UTC更新时间；不自动替代业务版本 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /** 乐观锁版本；每次合法变更递增 */
    @TableField("row_version")
    private Long rowVersion;

    /** 普通软删除UTC时间；隐私清除按工单执行 */
    @TableField("deleted_at")
    private LocalDateTime deletedAt;

    /** 安装所属账号 */
    @TableField("user_id")
    private Long userId;

    /** 客户端生成安装 UUID，重装重新生成 */
    @TableField("installation_key")
    private String installationKey;

    /** ANDROID / HARMONYOS / IOS / WEB，按实际支持开放 */
    @TableField("platform")
    private String platform;

    /** 客户端版本 */
    @TableField("app_version")
    private String appVersion;

    /** 系统版本，避免过细设备指纹 */
    @TableField("os_version")
    private String osVersion;

    /** 用户可辨识名称 */
    @TableField("device_label")
    private String deviceLabel;

    /** 已验证能力，如 localReminder、watchRelay；非权限授权凭证 */
    @TableField("capabilities_json")
    private String capabilitiesJson;

    /** 最近在线 */
    @TableField("last_seen_at")
    private LocalDateTime lastSeenAt;

    /** 已完整本地应用并确认的 seq */
    @TableField("last_ack_seq")
    private Long lastAckSeq;

    /** 确认时间 */
    @TableField("last_ack_at")
    private LocalDateTime lastAckAt;

    /** 当前全量初始化租约编号 */
    @TableField("bootstrap_id")
    private String bootstrapId;

    /** C0，高水位起点 */
    @TableField("bootstrap_from_seq")
    private Long bootstrapFromSeq;

    /** 建议 2 小时租约 */
    @TableField("bootstrap_expires_at")
    private LocalDateTime bootstrapExpiresAt;

    /** 初始化捕获数据代际 */
    @TableField("bootstrap_epoch")
    private Long bootstrapEpoch;

    /** 白名单实体及主键分页进度，不含原始健康内容 */
    @TableField("bootstrap_progress_json")
    private String bootstrapProgressJson;

    /** UNKNOWN / GRANTED / DENIED，由客户端报告，不能证明投递 */
    @TableField("notification_permission")
    private String notificationPermission;

    /** 移除本机或停止同步 */
    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    /** PHONE / WATCH / WEB，WEB 可用于管理端 */
    @TableField("installation_kind")
    private String installationKind;

    /** WATCH 必填且指向同用户 PHONE，其他种类为空 */
    @TableField("parent_installation_id")
    private Long parentInstallationId;

    /** P2 一次性配对 token 哈希，消费后清空 */
    @TableField("pairing_token_hash")
    private byte[] pairingTokenHash;

    /** 配对码短期有效截止 */
    @TableField("pairing_expires_at")
    private LocalDateTime pairingExpiresAt;

    /** WATCH 仅最小任务投影/白名单动作，非完整用户权限 */
    @TableField("device_scope_json")
    private String deviceScopeJson;

    /** 手表专用凭据哈希，不复用手机 refresh token */
    @TableField("device_credential_hash")
    private byte[] deviceCredentialHash;

    /** 设备凭据截止 */
    @TableField("credential_expires_at")
    private LocalDateTime credentialExpiresAt;

    /** 解绑/换凭据递增，旧设备命令拒绝 */
    @TableField("credential_revision")
    private Long credentialRevision;

    /** 服务器已交付最高 seq，ack 不得超过该值 */
    @TableField("last_delivered_seq")
    private Long lastDeliveredSeq;

    /** UNINITIALIZED / INITIALIZING / READY */
    @TableField("sync_state")
    private String syncState;

    /** READY 时已初始化的数据代际；普通增量/ack与当前clock核对 */
    @TableField("sync_epoch")
    private Long syncEpoch;

    /** 全量分页和C0之后增量追平确认时间 */
    @TableField("bootstrap_completed_at")
    private LocalDateTime bootstrapCompletedAt;
}
