package com.yqsoft.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人档案。
 *
 * <p>持久化对象不直接作为接口响应；敏感列只能由服务层解密和投影。
 */
@Data
@TableName("user_profile")
public class UserProfilePo {

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

    /** 账号 FK */
    @TableField("user_id")
    private Long userId;

    /** 用户昵称，1–60 字 */
    @TableField("display_name")
    private String displayName;

    /** 头像私有文件 FK */
    @TableField("avatar_file_id")
    private Long avatarFileId;

    /** IANA 时区，如 Asia/Shanghai，客户端首次确认 */
    @TableField("time_zone")
    private String timeZone;

    /** 语言区域 */
    @TableField("locale")
    private String locale;

    /** 成年人确认时间，不保存完整出生日期 */
    @TableField("adult_confirmed_at")
    private LocalDateTime adultConfirmedAt;

    /** GOALS / AVAILABILITY / PREFERENCES / COMPLETED */
    @TableField("onboarding_step")
    private String onboardingStep;

    /** 引导结束时间 */
    @TableField("onboarding_completed_at")
    private LocalDateTime onboardingCompletedAt;

    /** 可选个人备注密文 */
    @TableField("bio_cipher")
    private byte[] bioCipher;

    /** 备注加密密钥引用 */
    @TableField("encryption_key_ref")
    private String encryptionKeyRef;

    /** 1–7，周起始星期 */
    @TableField("week_start_day")
    private Integer weekStartDay;

    /** 首次完成核心档案时间 */
    @TableField("profile_completed_at")
    private LocalDateTime profileCompletedAt;
}
