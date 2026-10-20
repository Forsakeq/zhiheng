-- 知衡完整数据库结构（60表；建议MySQL 8.4 / InnoDB，兼容性由实际验证记录说明）
-- 来源：已确认数据库规划及冻结字段清单；本文件只建结构，不创建默认账号。
-- 首次空库导入：CREATE DATABASE允许已存在，CREATE TABLE故意不使用IF NOT EXISTS。
-- 若已有同名表，请先审阅迁移，不要直接重跑；本文件无DROP/TRUNCATE、不清空旧数据。
-- MySQL DDL会隐式提交，整文件不是原子事务；中途失败应停下诊断，不假装ROLLBACK全部。
-- 所有时间列按UTC写入；created_at/updated_at由应用提供，不用触发器偷偷替代业务版本。
-- 业务接口仍须执行：clock→账号→业务锁、所有权检查、幂等、SEALED不可变、详情恰有一个、
-- AI确认应用、seq提交顺序、异步epoch/授权复核、隐私fence/清除/备份恢复台账。
-- SQL约束不等于实现这些跨行/跨事务规则；本文件不安装服务、模型或手表SDK。
-- 物理落地修订：role_permission.granted_by_user_id仅受控初始化种子允许NULL，
-- 真实后台授权仍须真实actor；固定operation_id用于审计，不创建“默认管理员”占位账号。
-- 字段宽度勘误：knowledge_source.source_kind由规划VARCHAR16扩为VARCHAR24，
-- 以完整保存APP_DOCUMENTATION；不截断枚举值。权限resource_group按真实API补USER/JOB，
-- permission_code使用稳定dot代码（如content.read）；原文冒号仅为举例，不生成虚假默认权限。
-- 同类宽度勘误：ai_message.role由VARCHAR12扩为VARCHAR16，以完整保存SYSTEM_NOTICE。
-- app_configuration.value_json按API的ConfigValue分支包装保存，类型名不等于顶层JSON类型。
-- 外键统一在所有表建立后添加，使用默认RESTRICT；从不关闭foreign_key_checks。
-- 明文数据库访问凭据、模型密钥、外部token、用户样例健康数据均不写入本文件。

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET SESSION time_zone = '+00:00';
SET SESSION sql_mode = 'STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION,NO_ZERO_DATE,NO_ZERO_IN_DATE,ONLY_FULL_GROUP_BY';
CREATE DATABASE IF NOT EXISTS `zhiheng_validation_01` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `zhiheng_validation_01`;

-- 01. app_user / 账号主体 / P0
-- 账号状态、登录标识和数据写入栅栏；不承载生活记录。
CREATE TABLE `app_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `account_no` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '公开账号编号，创建后不复用',
  `password_hash` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '自适应密码哈希，第三方登录账号可空',
  `password_updated_at` DATETIME(3) NULL COMMENT '密码最近更新 UTC 时间',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / LOCKED / DELETING / DELETED',
  `security_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '密码变更、全端退出时递增，令牌需核对',
  `data_write_fence` VARCHAR(24) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / ERASING / ACCOUNT_DELETING',
  `registered_at` DATETIME(3) NOT NULL COMMENT '注册成功时间',
  `last_login_at` DATETIME(3) NULL COMMENT '最近成功登录，低频异步汇总',
  `lock_until` DATETIME(3) NULL COMMENT '临时账号锁定截止',
  `delete_requested_at` DATETIME(3) NULL COMMENT '注销申请受理时间',
  `anonymized_at` DATETIME(3) NULL COMMENT '敏感标识清理完成时间',
  `email_lookup_hmac` BINARY(32) NOT NULL COMMENT '规范化邮箱的域分离 HMAC，用于登录和唯一去重，不存邮箱明文',
  `email_cipher` VARBINARY(2048) NOT NULL COMMENT '邮箱认证加密密文；注销后替换为不可恢复占位',
  `email_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '邮箱密钥引用',
  `email_verified_at` DATETIME(3) NOT NULL COMMENT '首次验证码验证通过时间',
  `registration_policy_version` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '注册时明确同意的服务条款/隐私说明组合版本',
  `registration_policy_accepted_at` DATETIME(3) NOT NULL COMMENT '服务端确认接受时间，不能代替可撤回AI等独立授权',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_app_user_account_no` (`account_no`),
  UNIQUE KEY `uq_app_user_email_lookup_hmac` (`email_lookup_hmac`),
  KEY `ix_app_user_status_delete_requested_at_id` (`status`, `delete_requested_at`, `id`),
  CONSTRAINT `ck_app_user_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_app_user_status_enum` CHECK (CAST(`status` AS BINARY) IN ('ACTIVE','LOCKED','DELETING','DELETED')),
  CONSTRAINT `ck_app_user_security_version_positive` CHECK (`security_version` >= 1),
  CONSTRAINT `ck_app_user_data_write_fence_enum` CHECK (CAST(`data_write_fence` AS BINARY) IN ('OPEN','ERASING','ACCOUNT_DELETING'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='账号主体；P0';

-- 02. user_profile / 个人档案 / P0
-- 保存最小必要的展示与时间设置，不强制收集身体尺寸或病史。
CREATE TABLE `user_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号 FK',
  `display_name` VARCHAR(60) NOT NULL COMMENT '用户昵称，1–60 字',
  `avatar_file_id` BIGINT UNSIGNED NULL COMMENT '头像私有文件 FK',
  `time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'IANA 时区，如 Asia/Shanghai，客户端首次确认',
  `locale` VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'zh-CN' COMMENT '语言区域',
  `adult_confirmed_at` DATETIME(3) NOT NULL COMMENT '成年人确认时间，不保存完整出生日期',
  `onboarding_step` VARCHAR(24) NOT NULL DEFAULT 'GOALS' COMMENT 'GOALS / AVAILABILITY / PREFERENCES / COMPLETED',
  `onboarding_completed_at` DATETIME(3) NULL COMMENT '引导结束时间',
  `bio_cipher` VARBINARY(2048) NULL COMMENT '可选个人备注密文',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '备注加密密钥引用',
  `week_start_day` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1–7，周起始星期',
  `profile_completed_at` DATETIME(3) NULL COMMENT '首次完成核心档案时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_profile_user_id` (`user_id`),
  UNIQUE KEY `uq_user_profile_user_id_id` (`user_id`, `id`),
  KEY `ix_user_profile_user_id_avatar_file_id` (`user_id`, `avatar_file_id`),
  CONSTRAINT `ck_user_profile_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_user_profile_onboarding_step_enum` CHECK (CAST(`onboarding_step` AS BINARY) IN ('GOALS','AVAILABILITY','PREFERENCES','COMPLETED')),
  CONSTRAINT `ck_user_profile_bio_cipher_key` CHECK (`bio_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_user_profile_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`bio_cipher` IS NOT NULL)),
  CONSTRAINT `ck_user_profile_week_start_day_range` CHECK (`week_start_day` BETWEEN 1 AND 7)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='个人档案；P0';

-- 03. user_preference / 生活与交互偏好 / P0
-- 偏好是计划约束输入；是否授权调用 AI 由 consent 控制。
CREATE TABLE `user_preference` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号 FK',
  `preferred_wake_time` TIME NULL COMMENT '期望起床本地时间，时分精度',
  `preferred_bed_time` TIME NULL COMMENT '期望就寝时间',
  `preferred_activity_types_json` JSON NOT NULL COMMENT '活动类型白名单数组，允许空数组',
  `excluded_activity_types_json` JSON NOT NULL COMMENT '不愿推荐的活动数组',
  `daily_task_limit` TINYINT UNSIGNED NOT NULL DEFAULT 4 COMMENT '建议 1–12，程序硬上限 12',
  `default_task_minutes` SMALLINT UNSIGNED NOT NULL DEFAULT 15 COMMENT '手工新任务建议时长，1–240 分钟',
  `preferred_session_minutes` SMALLINT UNSIGNED NULL COMMENT '用户愿意单次投入时长，1–240',
  `plan_tone` VARCHAR(16) NOT NULL DEFAULT 'NEUTRAL' COMMENT 'NEUTRAL / ENCOURAGING / CONCISE',
  `theme_mode` VARCHAR(12) NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM / LIGHT / DARK',
  `reduced_motion` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 / 1，减少动效',
  `font_scale` DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '0.80–2.00，前端仍须适配',
  `quiet_hours_enabled` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否启用免打扰',
  `quiet_start` TIME NULL COMMENT '本地免打扰开始',
  `quiet_end` TIME NULL COMMENT '本地免打扰结束，可跨午夜',
  `preference_schema_version` SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'JSON 与规则解释版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_preference_user_id` (`user_id`),
  UNIQUE KEY `uq_user_preference_user_id_id` (`user_id`, `id`),
  CONSTRAINT `ck_user_preference_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_user_preference_preferred_wake_time_time` CHECK (`preferred_wake_time` >= '00:00:00' AND `preferred_wake_time` < '24:00:00' AND MOD(TIME_TO_SEC(`preferred_wake_time`),60)=0),
  CONSTRAINT `ck_user_preference_preferred_bed_time_time` CHECK (`preferred_bed_time` >= '00:00:00' AND `preferred_bed_time` < '24:00:00' AND MOD(TIME_TO_SEC(`preferred_bed_time`),60)=0),
  CONSTRAINT `ck_user_preference_plan_tone_enum` CHECK (CAST(`plan_tone` AS BINARY) IN ('NEUTRAL','ENCOURAGING','CONCISE')),
  CONSTRAINT `ck_user_preference_theme_mode_enum` CHECK (CAST(`theme_mode` AS BINARY) IN ('SYSTEM','LIGHT','DARK')),
  CONSTRAINT `ck_user_preference_reduced_motion_range` CHECK (`reduced_motion` BETWEEN 0 AND 1),
  CONSTRAINT `ck_user_preference_quiet_hours_enabled_range` CHECK (`quiet_hours_enabled` BETWEEN 0 AND 1),
  CONSTRAINT `ck_user_preference_quiet_start_time` CHECK (`quiet_start` >= '00:00:00' AND `quiet_start` < '24:00:00' AND MOD(TIME_TO_SEC(`quiet_start`),60)=0),
  CONSTRAINT `ck_user_preference_quiet_end_time` CHECK (`quiet_end` >= '00:00:00' AND `quiet_end` < '24:00:00' AND MOD(TIME_TO_SEC(`quiet_end`),60)=0),
  CONSTRAINT `ck_user_preference_preference_schema_version_positive` CHECK (`preference_schema_version` >= 1),
  CONSTRAINT `ck_user_preference_daily_task_limit_range` CHECK (`daily_task_limit` BETWEEN 1 AND 12),
  CONSTRAINT `ck_user_preference_default_task_minutes_range` CHECK (`default_task_minutes` BETWEEN 1 AND 240),
  CONSTRAINT `ck_user_preference_preferred_session_minutes_range` CHECK (`preferred_session_minutes` BETWEEN 1 AND 240),
  CONSTRAINT `ck_user_preference_font_scale_range` CHECK (`font_scale` BETWEEN 0.80 AND 2.00),
  CONSTRAINT `ck_user_preference_quiet_hours` CHECK ((`quiet_start` IS NULL) = (`quiet_end` IS NULL) AND (`quiet_hours_enabled`=0 OR (`quiet_start` IS NOT NULL AND `quiet_end` IS NOT NULL AND `quiet_start`<>`quiet_end`))),
  CONSTRAINT `ck_user_preference_preferred_activity_types_json_shape` CHECK (JSON_TYPE(`preferred_activity_types_json`) IN ('ARRAY')),
  CONSTRAINT `ck_user_preference_excluded_activity_types_json_shape` CHECK (JSON_TYPE(`excluded_activity_types_json`) IN ('ARRAY')),
  CONSTRAINT `ck_user_preference_preferred_activity_types_json_schema` CHECK (JSON_SCHEMA_VALID('{"type":"array","maxItems":6,"uniqueItems":true,"items":{"type":"string","enum":["WALK","RUN","CYCLING","STRETCH","SPORT","OTHER"]}}',`preferred_activity_types_json`) = 1),
  CONSTRAINT `ck_user_preference_excluded_activity_types_json_schema` CHECK (JSON_SCHEMA_VALID('{"type":"array","maxItems":6,"uniqueItems":true,"items":{"type":"string","enum":["WALK","RUN","CYCLING","STRETCH","SPORT","OTHER"]}}',`excluded_activity_types_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='生活与交互偏好；P0';

-- 04. auth_identity / 第三方身份绑定 / P2
-- 为最终确认的平台账号登录保留，不把健康数据授权混作身份授权。
CREATE TABLE `auth_identity` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '绑定账号',
  `provider` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '启用的身份提供方代码',
  `provider_tenant` VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '应用或租户命名空间',
  `subject_hash` BINARY(32) NOT NULL COMMENT 'provider subject 的域分离 HMAC',
  `subject_cipher` VARBINARY(2048) NULL COMMENT '确有重新绑定需要时保存的 subject 密文',
  `union_subject_hash` BINARY(32) NULL COMMENT '提供方跨应用标识 HMAC，不盲目自动合并账号',
  `binding_status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / REVOKED',
  `bound_at` DATETIME(3) NOT NULL COMMENT '首次完成绑定时间',
  `last_authenticated_at` DATETIME(3) NULL COMMENT '最近该方式登录',
  `revoked_at` DATETIME(3) NULL COMMENT '解除绑定时间',
  `authorization_revision` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '绑定凭据代际',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_auth_identity_provider_provider_tenant_subject_hash` (`provider`, `provider_tenant`, `subject_hash`),
  UNIQUE KEY `uq_auth_identity_user_id_id` (`user_id`, `id`),
  KEY `ix_auth_identity_user_id_binding_status_id` (`user_id`, `binding_status`, `id`),
  CONSTRAINT `ck_auth_identity_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_auth_identity_binding_status_enum` CHECK (CAST(`binding_status` AS BINARY) IN ('ACTIVE','REVOKED')),
  CONSTRAINT `ck_auth_identity_authorization_revision_positive` CHECK (`authorization_revision` >= 1),
  CONSTRAINT `ck_auth_identity_subject_cipher_key` CHECK (`subject_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_auth_identity_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`subject_cipher` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='第三方身份绑定；P2';

-- 05. auth_session / 刷新令牌代际与会话链 / P0
-- 保留 refresh token 旋转链，发现旧令牌复用时撤销整个 family。
CREATE TABLE `auth_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '登录账号',
  `installation_id` BIGINT UNSIGNED NOT NULL COMMENT '本机安装记录 FK',
  `family_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一次登录生成的随机 UUID，整链一致',
  `parent_session_id` BIGINT UNSIGNED NULL COMMENT '上一代 session FK',
  `replaced_by_session_id` BIGINT UNSIGNED NULL COMMENT '旋转后的下一代 FK',
  `token_hash` BINARY(32) NOT NULL COMMENT '高熵 refresh token 哈希，不存原 token',
  `security_version` BIGINT UNSIGNED NOT NULL COMMENT '签发时账号安全版本',
  `issued_at` DATETIME(3) NOT NULL COMMENT '本代签发时间',
  `expires_at` DATETIME(3) NOT NULL COMMENT '本代令牌截止',
  `family_expires_at` DATETIME(3) NOT NULL COMMENT '绝对会话截止，不因旋转无限续期',
  `consumed_at` DATETIME(3) NULL COMMENT '首次旋转消费时间',
  `revoked_at` DATETIME(3) NULL COMMENT '撤销生效时间',
  `revoke_reason` VARCHAR(32) NULL COMMENT 'LOGOUT / REUSE / PASSWORD_CHANGED / ACCOUNT_DELETED 等',
  `client_label` VARCHAR(100) NULL COMMENT '安全页面展示的设备名称，客户端文本需清洗',
  `ip_hash` BINARY(32) NULL COMMENT '短期安全排查 IP 的 HMAC，非原 IP',
  `user_agent_digest` BINARY(32) NULL COMMENT '客户端特征摘要，非设备指纹身份',
  `client_audience` VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MOBILE / ADMIN，签发与refresh旋转严格继承',
  `generation` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'family内代际，子代=父代+1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_auth_session_token_hash` (`token_hash`),
  UNIQUE KEY `uq_auth_session_parent_session_id` (`parent_session_id`),
  UNIQUE KEY `uq_auth_session_user_id_id` (`user_id`, `id`),
  UNIQUE KEY `uq_auth_session_user_id_family_id_client_audience_id` (`user_id`, `family_id`, `client_audience`, `id`),
  KEY `ix_auth_session_user_id_family_id_id` (`user_id`, `family_id`, `id`),
  KEY `ix_auth_session_expires_at_id` (`expires_at`, `id`),
  KEY `ix_auth_session_installation_id_revoked_at_id` (`installation_id`, `revoked_at`, `id`),
  KEY `ix_auth_session_user_id_installation_id` (`user_id`, `installation_id`),
  KEY `ix_auth_session_user_id_family_id_client_audience_paren_b642345e` (`user_id`, `family_id`, `client_audience`, `parent_session_id`),
  KEY `ix_auth_session_user_id_family_id_client_audience_repla_0c656a4e` (`user_id`, `family_id`, `client_audience`, `replaced_by_session_id`),
  CONSTRAINT `ck_auth_session_security_version_positive` CHECK (`security_version` >= 1),
  CONSTRAINT `ck_auth_session_client_audience_enum` CHECK (CAST(`client_audience` AS BINARY) IN ('MOBILE','ADMIN')),
  CONSTRAINT `ck_auth_session_generation_positive` CHECK (`generation` >= 1),
  CONSTRAINT `ck_auth_session_expiry` CHECK (`expires_at` > `issued_at` AND `family_expires_at` >= `expires_at`),
  CONSTRAINT `ck_auth_session_consumed_at_pair` CHECK ((`consumed_at` IS NULL) = (`replaced_by_session_id` IS NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='刷新令牌代际与会话链；P0';

-- 06. auth_verification / 一次性身份验证挑战 / P0
-- 注册、找回密码或敏感操作二次核验；渠道未配置时不展示不可用入口。
CREATE TABLE `auth_verification` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `challenge_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机挑战编号',
  `user_id` BIGINT UNSIGNED NULL COMMENT '未注册场景可空',
  `purpose` VARCHAR(24) NOT NULL COMMENT 'REGISTER / RESET_PASSWORD / REAUTH / CONTACT_CHANGE',
  `channel` VARCHAR(16) NOT NULL COMMENT 'P0 固定 EMAIL；其他验证渠道启用时另行迁移/校验，不提供伪短信入口',
  `destination_hash` BINARY(32) NULL COMMENT '目标地址域分离 HMAC',
  `destination_cipher` VARBINARY(2048) NULL COMMENT '确需短期投递时加密地址',
  `proof_hash` BINARY(32) NOT NULL COMMENT '验证码加盐/HMAC，不保存原码',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '短期收件地址密钥引用',
  `expires_at` DATETIME(3) NOT NULL COMMENT '挑战过期，建议验证码 5–10 分钟',
  `attempt_count` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '失败尝试次数',
  `max_attempts` TINYINT UNSIGNED NOT NULL DEFAULT 5 COMMENT '本挑战尝试上限',
  `consumed_at` DATETIME(3) NULL COMMENT '成功消费，单次使用',
  `locked_at` DATETIME(3) NULL COMMENT '超尝试上限时间',
  `request_ip_hash` BINARY(32) NULL COMMENT '短期防滥用摘要',
  `invalidated_at` DATETIME(3) NULL COMMENT '重发/安全撤销使旧挑战作废的时间；验证须为空',
  `client_audience` VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MOBILE / ADMIN；REAUTH凭证只能用于原audience',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_auth_verification_challenge_id` (`challenge_id`),
  UNIQUE KEY `uq_auth_verification_user_id_id` (`user_id`, `id`),
  KEY `ix_auth_verification_destination_hash_purpose_created_at` (`destination_hash`, `purpose`, `created_at`),
  KEY `ix_auth_verification_expires_at_id` (`expires_at`, `id`),
  CONSTRAINT `ck_auth_verification_purpose_enum` CHECK (CAST(`purpose` AS BINARY) IN ('REGISTER','RESET_PASSWORD','REAUTH','CONTACT_CHANGE')),
  CONSTRAINT `ck_auth_verification_channel_enum` CHECK (CAST(`channel` AS BINARY) IN ('EMAIL')),
  CONSTRAINT `ck_auth_verification_client_audience_enum` CHECK (CAST(`client_audience` AS BINARY) IN ('MOBILE','ADMIN')),
  CONSTRAINT `ck_auth_verification_destination_cipher_key` CHECK (`destination_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_auth_verification_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`destination_cipher` IS NOT NULL)),
  CONSTRAINT `ck_auth_verification_max_attempts_range` CHECK (`max_attempts` BETWEEN 1 AND 10),
  CONSTRAINT `ck_auth_verification_attempts` CHECK (`attempt_count` <= `max_attempts`),
  CONSTRAINT `ck_auth_verification_expiry` CHECK (`expires_at` > `created_at`),
  CONSTRAINT `ck_auth_verification_destination_cipher_pair` CHECK ((`destination_cipher` IS NULL) = (`encryption_key_ref` IS NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='一次性身份验证挑战；P0';

-- 07. client_installation / 客户端安装与同步租约 / P0
-- 区分同账号多次安装，存同步进度、初始化租约与实际能力。
CREATE TABLE `client_installation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '安装所属账号',
  `installation_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端生成安装 UUID，重装重新生成',
  `platform` VARCHAR(20) NOT NULL COMMENT 'ANDROID / HARMONYOS / IOS / WEB，按实际支持开放',
  `app_version` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端版本',
  `os_version` VARCHAR(64) NULL COMMENT '系统版本，避免过细设备指纹',
  `device_label` VARCHAR(100) NULL COMMENT '用户可辨识名称',
  `capabilities_json` JSON NOT NULL COMMENT '已验证能力，如 localReminder、watchRelay；非权限授权凭证',
  `last_seen_at` DATETIME(3) NULL COMMENT '最近在线',
  `last_ack_seq` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已完整本地应用并确认的 seq',
  `last_ack_at` DATETIME(3) NULL COMMENT '确认时间',
  `bootstrap_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前全量初始化租约编号',
  `bootstrap_from_seq` BIGINT UNSIGNED NULL COMMENT 'C0，高水位起点',
  `bootstrap_expires_at` DATETIME(3) NULL COMMENT '建议 2 小时租约',
  `bootstrap_epoch` BIGINT UNSIGNED NULL COMMENT '初始化捕获数据代际',
  `bootstrap_progress_json` JSON NULL COMMENT '白名单实体及主键分页进度，不含原始健康内容',
  `notification_permission` VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UNKNOWN / GRANTED / DENIED，由客户端报告，不能证明投递',
  `revoked_at` DATETIME(3) NULL COMMENT '移除本机或停止同步',
  `installation_kind` VARCHAR(12) NOT NULL DEFAULT 'PHONE' COMMENT 'PHONE / WATCH / WEB，WEB 可用于管理端',
  `parent_installation_id` BIGINT UNSIGNED NULL COMMENT 'WATCH 必填且指向同用户 PHONE，其他种类为空',
  `pairing_token_hash` BINARY(32) NULL COMMENT 'P2 一次性配对 token 哈希，消费后清空',
  `pairing_expires_at` DATETIME(3) NULL COMMENT '配对码短期有效截止',
  `device_scope_json` JSON NULL COMMENT 'WATCH 仅最小任务投影/白名单动作，非完整用户权限',
  `device_credential_hash` BINARY(32) NULL COMMENT '手表专用凭据哈希，不复用手机 refresh token',
  `credential_expires_at` DATETIME(3) NULL COMMENT '设备凭据截止',
  `credential_revision` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '解绑/换凭据递增，旧设备命令拒绝',
  `last_delivered_seq` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '服务器已交付最高 seq，ack 不得超过该值',
  `sync_state` VARCHAR(16) NOT NULL DEFAULT 'UNINITIALIZED' COMMENT 'UNINITIALIZED / INITIALIZING / READY',
  `sync_epoch` BIGINT UNSIGNED NULL COMMENT 'READY 时已初始化的数据代际；普通增量/ack与当前clock核对',
  `bootstrap_completed_at` DATETIME(3) NULL COMMENT '全量分页和C0之后增量追平确认时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_client_installation_user_id_installation_key` (`user_id`, `installation_key`),
  UNIQUE KEY `uq_client_installation_user_id_id` (`user_id`, `id`),
  UNIQUE KEY `uq_client_installation_pairing_token_hash` (`pairing_token_hash`),
  UNIQUE KEY `uq_client_installation_device_credential_hash` (`device_credential_hash`),
  KEY `ix_client_installation_user_id_last_seen_at_id` (`user_id`, `last_seen_at`, `id`),
  KEY `ix_client_installation_bootstrap_expires_at_id` (`bootstrap_expires_at`, `id`),
  KEY `ix_client_installation_user_id_parent_installation_id` (`user_id`, `parent_installation_id`),
  CONSTRAINT `ck_client_installation_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_client_installation_platform_enum` CHECK (CAST(`platform` AS BINARY) IN ('ANDROID','HARMONYOS','IOS','WEB')),
  CONSTRAINT `ck_client_installation_bootstrap_epoch_positive` CHECK (`bootstrap_epoch` >= 1),
  CONSTRAINT `ck_client_installation_notification_permission_enum` CHECK (CAST(`notification_permission` AS BINARY) IN ('UNKNOWN','GRANTED','DENIED')),
  CONSTRAINT `ck_client_installation_installation_kind_enum` CHECK (CAST(`installation_kind` AS BINARY) IN ('PHONE','WATCH','WEB')),
  CONSTRAINT `ck_client_installation_credential_revision_positive` CHECK (`credential_revision` >= 1),
  CONSTRAINT `ck_client_installation_sync_state_enum` CHECK (CAST(`sync_state` AS BINARY) IN ('UNINITIALIZED','INITIALIZING','READY')),
  CONSTRAINT `ck_client_installation_sync_epoch_positive` CHECK (`sync_epoch` >= 1),
  CONSTRAINT `ck_client_installation_pairing_token_hash_pair` CHECK ((`pairing_token_hash` IS NULL) = (`pairing_expires_at` IS NULL)),
  CONSTRAINT `ck_client_installation_device_credential_hash_pair` CHECK ((`device_credential_hash` IS NULL) = (`credential_expires_at` IS NULL)),
  CONSTRAINT `ck_client_installation_parent_kind` CHECK ((CAST(`installation_kind` AS BINARY)='WATCH' AND `parent_installation_id` IS NOT NULL) OR (CAST(`installation_kind` AS BINARY) IN ('PHONE','WEB') AND `parent_installation_id` IS NULL)),
  CONSTRAINT `ck_client_installation_web_platform` CHECK ((CAST(`installation_kind` AS BINARY)='WEB') = (CAST(`platform` AS BINARY)='WEB')),
  CONSTRAINT `ck_client_installation_ack_bound` CHECK (`last_ack_seq` <= `last_delivered_seq`),
  CONSTRAINT `ck_client_installation_initializing_lease` CHECK (CAST(`sync_state` AS BINARY)<>'INITIALIZING' OR (`bootstrap_id` IS NOT NULL AND `bootstrap_from_seq` IS NOT NULL AND `bootstrap_expires_at` IS NOT NULL AND `bootstrap_epoch` IS NOT NULL)),
  CONSTRAINT `ck_client_installation_ready_epoch` CHECK (CAST(`sync_state` AS BINARY)<>'READY' OR (`sync_epoch` IS NOT NULL AND `bootstrap_completed_at` IS NOT NULL)),
  CONSTRAINT `ck_client_installation_capabilities_json_shape` CHECK (JSON_TYPE(`capabilities_json`) IN ('OBJECT')),
  CONSTRAINT `ck_client_installation_bootstrap_progress_json_shape` CHECK (JSON_TYPE(`bootstrap_progress_json`) IN ('OBJECT')),
  CONSTRAINT `ck_client_installation_device_scope_json_shape` CHECK (JSON_TYPE(`device_scope_json`) IN ('ARRAY')),
  CONSTRAINT `ck_client_installation_device_scope_json_schema` CHECK (JSON_SCHEMA_VALID('{"type":"array","maxItems":2,"uniqueItems":true,"items":{"type":"string","enum":["task.read","task.action"]}}',`device_scope_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='客户端安装与同步租约；P0';

-- 08. user_consent / 按用途的当前授权 / P0
-- 每账号每用途一行，当前状态与不可变事件分离。
CREATE TABLE `user_consent` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '授权主体',
  `purpose_code` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'AI_PROCESSING / NOTIFICATION / EXTERNAL_HEALTH',
  `status` VARCHAR(16) NOT NULL DEFAULT 'NOT_GRANTED' COMMENT 'NOT_GRANTED / GRANTED / REVOKED',
  `policy_version` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '被展示的说明版本',
  `scope_json` JSON NOT NULL COMMENT '授权能力白名单，不允许通配全部数据',
  `consent_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '每次授权状态或范围变化递增',
  `granted_at` DATETIME(3) NULL COMMENT '最近允许时间',
  `revoked_at` DATETIME(3) NULL COMMENT '最近撤回时间',
  `expires_at` DATETIME(3) NULL COMMENT '如用途限定有效期',
  `installation_id` BIGINT UNSIGNED NULL COMMENT '授权发起设备',
  `last_event_id` BIGINT UNSIGNED NULL COMMENT '最后一条 consent_event',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_consent_user_id_purpose_code` (`user_id`, `purpose_code`),
  UNIQUE KEY `uq_user_consent_user_id_id` (`user_id`, `id`),
  KEY `ix_user_consent_user_id_status_purpose_code` (`user_id`, `status`, `purpose_code`),
  KEY `ix_user_consent_user_id_installation_id` (`user_id`, `installation_id`),
  KEY `ix_user_consent_user_id_id_last_event_id` (`user_id`, `id`, `last_event_id`),
  CONSTRAINT `ck_user_consent_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_user_consent_purpose_code_enum` CHECK (CAST(`purpose_code` AS BINARY) IN ('AI_PROCESSING','NOTIFICATION','EXTERNAL_HEALTH')),
  CONSTRAINT `ck_user_consent_status_enum` CHECK (CAST(`status` AS BINARY) IN ('NOT_GRANTED','GRANTED','REVOKED')),
  CONSTRAINT `ck_user_consent_consent_version_positive` CHECK (`consent_version` >= 1),
  CONSTRAINT `ck_user_consent_scope_json_shape` CHECK (JSON_TYPE(`scope_json`) IN ('OBJECT','ARRAY'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='按用途的当前授权；P0';

-- 09. user_consent_event / 授权变更证据 / P0
-- 追加记录授权/撤回所见说明和动作；不是原始页面录屏。
CREATE TABLE `user_consent_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '授权主体',
  `consent_id` BIGINT UNSIGNED NOT NULL COMMENT '当前授权行 FK',
  `event_type` VARCHAR(16) NOT NULL COMMENT 'GRANT / REVOKE / UPDATE_SCOPE / EXPIRE',
  `consent_version` BIGINT UNSIGNED NOT NULL COMMENT '变化后的授权版本',
  `policy_version` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '当时所见说明版本',
  `scope_json` JSON NOT NULL COMMENT '当时生效范围白名单',
  `occurred_at` DATETIME(3) NOT NULL COMMENT '服务器确认时间',
  `installation_id` BIGINT UNSIGNED NULL COMMENT '动作设备',
  `operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可重试操作号',
  `notice_digest` BINARY(32) NOT NULL COMMENT '被展示授权说明的摘要',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_consent_event_consent_id_consent_version` (`consent_id`, `consent_version`),
  UNIQUE KEY `uq_user_consent_event_user_id_operation_id` (`user_id`, `operation_id`),
  UNIQUE KEY `uq_user_consent_event_user_id_id` (`user_id`, `id`),
  UNIQUE KEY `uq_user_consent_event_user_id_consent_id_id` (`user_id`, `consent_id`, `id`),
  KEY `ix_user_consent_event_user_id_occurred_at_id` (`user_id`, `occurred_at`, `id`),
  KEY `ix_user_consent_event_user_id_installation_id` (`user_id`, `installation_id`),
  CONSTRAINT `ck_user_consent_event_event_type_enum` CHECK (CAST(`event_type` AS BINARY) IN ('GRANT','REVOKE','UPDATE_SCOPE','EXPIRE')),
  CONSTRAINT `ck_user_consent_event_consent_version_positive` CHECK (`consent_version` >= 1),
  CONSTRAINT `ck_user_consent_event_scope_json_shape` CHECK (JSON_TYPE(`scope_json`) IN ('OBJECT','ARRAY'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='授权变更证据；P0';

-- 10. goal_definition / 目标字典 / P0
-- 维护可选择的生活目标及可接受参数，不定义疾病评分。
CREATE TABLE `goal_definition` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `code` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'REGULAR_SLEEP / DAILY_ACTIVITY / LIFE_RECORD / CUSTOM 等稳定码',
  `name` VARCHAR(80) NOT NULL COMMENT '目标名称',
  `description` VARCHAR(500) NOT NULL COMMENT '用户可理解的目标解释',
  `goal_category` VARCHAR(24) NOT NULL COMMENT 'ROUTINE / ACTIVITY / RECORDING / CUSTOM',
  `parameter_schema_json` JSON NOT NULL COMMENT '允许的目标字段、单位、范围和是否可空',
  `suggested_parameters_json` JSON NULL COMMENT '非医疗性质的可编辑示例参数',
  `icon_code` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '前端图标白名单',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示排序',
  `status` VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  `schema_version` SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '参数解释版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_goal_definition_code` (`code`),
  KEY `ix_goal_definition_status_sort_order_id` (`status`, `sort_order`, `id`),
  CONSTRAINT `ck_goal_definition_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_goal_definition_goal_category_enum` CHECK (CAST(`goal_category` AS BINARY) IN ('ROUTINE','ACTIVITY','RECORDING','CUSTOM')),
  CONSTRAINT `ck_goal_definition_status_enum` CHECK (CAST(`status` AS BINARY) IN ('ACTIVE','DISABLED')),
  CONSTRAINT `ck_goal_definition_schema_version_positive` CHECK (`schema_version` >= 1),
  CONSTRAINT `ck_goal_definition_parameter_schema_json_shape` CHECK (JSON_TYPE(`parameter_schema_json`) IN ('OBJECT')),
  CONSTRAINT `ck_goal_definition_suggested_parameters_json_shape` CHECK (JSON_TYPE(`suggested_parameters_json`) IN ('OBJECT'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='目标字典；P0';

-- 11. user_goal / 用户生活目标 / P0
-- 保存用户确认的目标及有效周期，给计划生成提供约束。
CREATE TABLE `user_goal` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `goal_definition_id` BIGINT UNSIGNED NOT NULL COMMENT '目标字典 FK',
  `display_name` VARCHAR(80) NOT NULL COMMENT '该用户的目标名称快照',
  `parameters_json` JSON NOT NULL COMMENT '按目标 schema 校验，不能保存自由医疗指令',
  `parameter_schema_version` SMALLINT UNSIGNED NOT NULL COMMENT '采用的字典解释版本',
  `priority` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1–5，值越大优先级越高',
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / PAUSED / COMPLETED / ARCHIVED',
  `start_local_date` DATE NOT NULL COMMENT '有效开始日',
  `end_local_date` DATE NULL COMMENT '可选目标结束日',
  `goal_time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '设置目标时区',
  `success_criterion_json` JSON NULL COMMENT '用户确认的可测行为口径，不是医疗效果',
  `motivation_cipher` VARBINARY(4096) NULL COMMENT '可选动机备注密文',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  `completed_at` DATETIME(3) NULL COMMENT '用户确认目标完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_goal_user_id_id` (`user_id`, `id`),
  KEY `ix_user_goal_user_id_status_priority_id` (`user_id`, `status`, `priority`, `id`),
  KEY `ix_user_goal_goal_definition_id` (`goal_definition_id`),
  CONSTRAINT `ck_user_goal_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_user_goal_parameter_schema_version_positive` CHECK (`parameter_schema_version` >= 1),
  CONSTRAINT `ck_user_goal_status_enum` CHECK (CAST(`status` AS BINARY) IN ('ACTIVE','PAUSED','COMPLETED','ARCHIVED')),
  CONSTRAINT `ck_user_goal_motivation_cipher_key` CHECK (`motivation_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_user_goal_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`motivation_cipher` IS NOT NULL)),
  CONSTRAINT `ck_user_goal_priority_range` CHECK (`priority` BETWEEN 1 AND 5),
  CONSTRAINT `ck_user_goal_valid_date_range` CHECK (`end_local_date` IS NULL OR `end_local_date` >= `start_local_date`),
  CONSTRAINT `ck_user_goal_parameters_json_shape` CHECK (JSON_TYPE(`parameters_json`) IN ('OBJECT')),
  CONSTRAINT `ck_user_goal_success_criterion_json_shape` CHECK (JSON_TYPE(`success_criterion_json`) IN ('OBJECT'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户生活目标；P0';

-- 12. availability_rule / 每周时间安排规则 / P0
-- 用本地星期和分钟区间表达空闲、忙碌与保留休息。
CREATE TABLE `availability_rule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `label` VARCHAR(80) NOT NULL COMMENT '课程、社团或休息等自定义名称',
  `rule_kind` VARCHAR(16) NOT NULL COMMENT 'AVAILABLE / BUSY / RESERVED_REST',
  `weekday` TINYINT UNSIGNED NOT NULL COMMENT '1–7，ISO 星期',
  `start_minute` SMALLINT UNSIGNED NOT NULL COMMENT '0–1439，本地当日开始分钟',
  `end_minute` SMALLINT UNSIGNED NOT NULL COMMENT '0–1439，本地结束分钟，配合 end_day_offset',
  `time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规则的 IANA 时区',
  `valid_from` DATE NOT NULL COMMENT '本地有效开始日',
  `valid_until` DATE NULL COMMENT '本地有效结束日',
  `priority` SMALLINT NOT NULL DEFAULT 0 COMMENT '同类覆盖优先级',
  `is_enabled` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 / 1',
  `source_type` VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / AI_CONFIRMED / COURSE_IMPORT',
  `source_batch_id` BIGINT UNSIGNED NULL COMMENT 'P2 课程导入批次',
  `end_day_offset` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 当日 / 1 次日；允许跨午夜，不用end<start推断',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_availability_rule_user_id_id` (`user_id`, `id`),
  KEY `ix_availability_rule_user_id_is_enabled_weekday_valid_from_id` (`user_id`, `is_enabled`, `weekday`, `valid_from`, `id`),
  KEY `ix_availability_rule_user_id_source_batch_id` (`user_id`, `source_batch_id`),
  CONSTRAINT `ck_availability_rule_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_availability_rule_rule_kind_enum` CHECK (CAST(`rule_kind` AS BINARY) IN ('AVAILABLE','BUSY','RESERVED_REST')),
  CONSTRAINT `ck_availability_rule_is_enabled_range` CHECK (`is_enabled` BETWEEN 0 AND 1),
  CONSTRAINT `ck_availability_rule_source_type_enum` CHECK (CAST(`source_type` AS BINARY) IN ('MANUAL','AI_CONFIRMED','COURSE_IMPORT')),
  CONSTRAINT `ck_availability_rule_end_day_offset_range` CHECK (`end_day_offset` BETWEEN 0 AND 1),
  CONSTRAINT `ck_availability_rule_weekday_range` CHECK (`weekday` BETWEEN 1 AND 7),
  CONSTRAINT `ck_availability_rule_start_minute_range` CHECK (`start_minute` BETWEEN 0 AND 1439),
  CONSTRAINT `ck_availability_rule_end_minute_range` CHECK (`end_minute` BETWEEN 0 AND 1439),
  CONSTRAINT `ck_availability_rule_valid_date_range` CHECK (`valid_until` IS NULL OR `valid_until` >= `valid_from`),
  CONSTRAINT `ck_availability_rule_duration` CHECK (`end_minute` + 1440*`end_day_offset` - `start_minute` BETWEEN 1 AND 1440)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='每周时间安排规则；P0';

-- 13. availability_exception / 指定日期时间例外 / P0
-- 按用户和起始日期保存整日例外；slots 为受限值对象数组，整日表单用一行 row_version 避免跨区间竞争。
CREATE TABLE `availability_exception` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `exception_date` DATE NOT NULL COMMENT '例外起始业务日',
  `time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '解释所有 slot 的 IANA 时区',
  `mode` VARCHAR(16) NOT NULL DEFAULT 'REPLACE_DAY' COMMENT 'REPLACE_DAY / APPEND',
  `slots_json` JSON NOT NULL COMMENT '受 schema 校验的数组：slotKey、kind(BUSY/AVAILABLE/REST)、startTime/endTime、endDayOffset(0/1)、必要 offset',
  `slots_schema_version` SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '区间协议版本',
  `label` VARCHAR(100) NOT NULL COMMENT '考试日、调课等名称',
  `reason_cipher` VARBINARY(4096) NULL COMMENT '可选原因密文',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原因密钥引用',
  `is_enabled` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '0 / 1',
  `source_type` VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / AI_CONFIRMED / COURSE_IMPORT',
  `source_batch_id` BIGINT UNSIGNED NULL COMMENT 'P2 来源导入批次',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_availability_exception_user_id_exception_date` (`user_id`, `exception_date`),
  UNIQUE KEY `uq_availability_exception_user_id_id` (`user_id`, `id`),
  KEY `ix_availability_exception_user_id_is_enabled_exception_date_id` (`user_id`, `is_enabled`, `exception_date`, `id`),
  KEY `ix_availability_exception_user_id_source_batch_id` (`user_id`, `source_batch_id`),
  CONSTRAINT `ck_availability_exception_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_availability_exception_mode_enum` CHECK (CAST(`mode` AS BINARY) IN ('REPLACE_DAY','APPEND')),
  CONSTRAINT `ck_availability_exception_slots_schema_version_positive` CHECK (`slots_schema_version` >= 1),
  CONSTRAINT `ck_availability_exception_is_enabled_range` CHECK (`is_enabled` BETWEEN 0 AND 1),
  CONSTRAINT `ck_availability_exception_source_type_enum` CHECK (CAST(`source_type` AS BINARY) IN ('MANUAL','AI_CONFIRMED','COURSE_IMPORT')),
  CONSTRAINT `ck_availability_exception_reason_cipher_key` CHECK (`reason_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_availability_exception_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`reason_cipher` IS NOT NULL)),
  CONSTRAINT `ck_availability_exception_slots_json_shape` CHECK (JSON_TYPE(`slots_json`) IN ('ARRAY')),
  CONSTRAINT `ck_availability_exception_slots_json_schema` CHECK (JSON_SCHEMA_VALID('{"type":"array","maxItems":24,"items":{"type":"object","required":["kind","startTime","endTime","endDayOffset"],"properties":{"slotKey":{"type":"string","minLength":1},"kind":{"type":"string","enum":["BUSY","AVAILABLE","REST"]},"startTime":{"type":"string","pattern":"^([01][0-9]|2[0-3]):[0-5][0-9]$"},"endTime":{"type":"string","pattern":"^([01][0-9]|2[0-3]):[0-5][0-9]$"},"endDayOffset":{"type":"integer","minimum":0,"maximum":1},"startOffsetMinutes":{"type":["integer","null"],"minimum":-840,"maximum":840},"endOffsetMinutes":{"type":["integer","null"],"minimum":-840,"maximum":840}}}}',`slots_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='指定日期时间例外；P0';

-- 14. health_record / 生活记录主表 / P0
-- 统一归属、业务日、时间、来源、可见性；实际内容进入四选一详情。
CREATE TABLE `health_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `client_record_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次创建时客户端 UUID，所有来源均需稳定值',
  `record_type` VARCHAR(16) NOT NULL COMMENT 'SLEEP / ACTIVITY / MEAL / ENERGY',
  `started_at` DATETIME(3) NOT NULL COMMENT '发生开始 UTC 时间',
  `ended_at` DATETIME(3) NULL COMMENT 'SLEEP/ACTIVITY 必填，瞬时记录可空',
  `record_local_date` DATE NOT NULL COMMENT '冻结业务日；睡眠取起床所在当地日',
  `record_time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '确认后的 IANA 时区',
  `utc_offset_minutes` SMALLINT NOT NULL COMMENT '发生时相对 UTC 偏移，-840–840',
  `source_timezone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '外部数据声称的原始时区，和确认值分别保留',
  `source_type` VARCHAR(20) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / AI_CONFIRMED / EXTERNAL',
  `source_connection_id` BIGINT UNSIGNED NULL COMMENT 'EXTERNAL 时必填、同用户外部授权',
  `provider_record_key` VARCHAR(190) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '外部稳定记录键，不是随机每次导入',
  `quality_status` VARCHAR(12) NOT NULL DEFAULT 'VALID' COMMENT 'VALID / INVALID；无效不参与统计',
  `invalid_reason` VARCHAR(200) NULL COMMENT '用户或校验给出的可读原因',
  `notes_cipher` MEDIUMBLOB NULL COMMENT '用户备注密文，明文上限 2000 字',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '备注密钥引用',
  `confirmed_at` DATETIME(3) NOT NULL COMMENT '用户确认/已验证导入落库时间',
  `ai_draft_id` BIGINT UNSIGNED NULL COMMENT 'AI_CONFIRMED 时的来源草稿',
  `import_batch_id` BIGINT UNSIGNED NULL COMMENT '外部导入批次',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_health_record_user_id_client_record_id` (`user_id`, `client_record_id`),
  UNIQUE KEY `uq_health_record_user_id_id` (`user_id`, `id`),
  UNIQUE KEY `uq_health_record_user_id_id_record_type` (`user_id`, `id`, `record_type`),
  UNIQUE KEY `uq_health_record_user_id_source_connection_id_provider__c82a31b5` (`user_id`, `source_connection_id`, `provider_record_key`, `record_type`),
  KEY `ix_health_record_user_id_record_local_date_record_type_id` (`user_id`, `record_local_date`, `record_type`, `id`),
  KEY `ix_health_record_user_id_record_type_started_at_id` (`user_id`, `record_type`, `started_at`, `id`),
  KEY `ix_health_record_user_id_ai_draft_id` (`user_id`, `ai_draft_id`),
  KEY `ix_health_record_user_id_import_batch_id` (`user_id`, `import_batch_id`),
  CONSTRAINT `ck_health_record_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_health_record_record_type_enum` CHECK (CAST(`record_type` AS BINARY) IN ('SLEEP','ACTIVITY','MEAL','ENERGY')),
  CONSTRAINT `ck_health_record_source_type_enum` CHECK (CAST(`source_type` AS BINARY) IN ('MANUAL','AI_CONFIRMED','EXTERNAL')),
  CONSTRAINT `ck_health_record_quality_status_enum` CHECK (CAST(`quality_status` AS BINARY) IN ('VALID','INVALID')),
  CONSTRAINT `ck_health_record_notes_cipher_key` CHECK (`notes_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_health_record_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`notes_cipher` IS NOT NULL)),
  CONSTRAINT `ck_health_record_utc_offset_minutes_range` CHECK (`utc_offset_minutes` BETWEEN -840 AND 840),
  CONSTRAINT `ck_health_record_time_range` CHECK (`ended_at` IS NULL OR `ended_at` >= `started_at`),
  CONSTRAINT `ck_health_record_type_time` CHECK ((CAST(`record_type` AS BINARY) IN ('SLEEP','ACTIVITY') AND `ended_at` IS NOT NULL AND `ended_at`>`started_at`) OR (CAST(`record_type` AS BINARY)='ENERGY' AND `ended_at` IS NULL) OR CAST(`record_type` AS BINARY)='MEAL'),
  CONSTRAINT `ck_health_record_source_pair` CHECK ((CAST(`source_type` AS BINARY)='EXTERNAL' AND `source_connection_id` IS NOT NULL AND `provider_record_key` IS NOT NULL) OR (CAST(`source_type` AS BINARY) IN ('MANUAL','AI_CONFIRMED') AND `source_connection_id` IS NULL AND `provider_record_key` IS NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='生活记录主表；P0';

-- 15. sleep_record_detail / 睡眠记录详情 / P0
-- 只描述用户填写或明确可解释来源的入睡与起床，不伪造睡眠分期。
CREATE TABLE `sleep_record_detail` (
  `record_id` BIGINT UNSIGNED NOT NULL COMMENT '父health_record主键；详情无独立版本或软删除列',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '随主记录同事务更新UTC时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '同主记录账号，用组合 FK 防跨账号',
  `detail_type` VARCHAR(16) NOT NULL DEFAULT 'SLEEP' COMMENT 'CHECK 固定 SLEEP，用于父类型 FK',
  `sleep_start_at` DATETIME(3) NOT NULL COMMENT '填写入睡 UTC 时间，等于主表 started_at',
  `wake_at` DATETIME(3) NOT NULL COMMENT '填写起床 UTC 时间，等于主表 ended_at',
  `elapsed_minutes` SMALLINT UNSIGNED NOT NULL COMMENT '两时间间隔分钟，不命名为深睡或实际总睡眠',
  `rested_level` TINYINT UNSIGNED NULL COMMENT '1 很疲惫 / 2 较疲惫 / 3 一般 / 4 较精神 / 5 很精神',
  `sleep_kind` VARCHAR(12) NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN / NAP，用户确认',
  `measurement_method` VARCHAR(24) NOT NULL DEFAULT 'SELF_REPORTED' COMMENT 'SELF_REPORTED / VERIFIED_PROVIDER，后者 P2 核验后启用',
  `interruption_note_cipher` VARBINARY(4096) NULL COMMENT '用户自述中断，非自动推断次数',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '附加备注密钥引用',
  PRIMARY KEY (`record_id`),
  KEY `ix_sleep_record_detail_user_id` (`user_id`),
  KEY `ix_sleep_record_detail_user_id_record_id_detail_type` (`user_id`, `record_id`, `detail_type`),
  CONSTRAINT `ck_sleep_record_detail_detail_type_enum` CHECK (CAST(`detail_type` AS BINARY) IN ('SLEEP')),
  CONSTRAINT `ck_sleep_record_detail_sleep_kind_enum` CHECK (CAST(`sleep_kind` AS BINARY) IN ('MAIN','NAP')),
  CONSTRAINT `ck_sleep_record_detail_measurement_method_enum` CHECK (CAST(`measurement_method` AS BINARY) IN ('SELF_REPORTED','VERIFIED_PROVIDER')),
  CONSTRAINT `ck_sleep_record_detail_interruption_note_cipher_key` CHECK (`interruption_note_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_sleep_record_detail_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`interruption_note_cipher` IS NOT NULL)),
  CONSTRAINT `ck_sleep_record_detail_elapsed_minutes_range` CHECK (`elapsed_minutes` BETWEEN 1 AND 2160),
  CONSTRAINT `ck_sleep_record_detail_rested_level_range` CHECK (`rested_level` BETWEEN 1 AND 5),
  CONSTRAINT `ck_sleep_record_detail_sleep_range` CHECK (`wake_at` > `sleep_start_at` AND TIMESTAMPDIFF(SECOND,`sleep_start_at`,`wake_at`) <= 129600)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='睡眠记录详情；P0';

-- 16. activity_record_detail / 实际活动记录详情 / P0
-- 保存实际发生的活动，与计划时长严格分开。
CREATE TABLE `activity_record_detail` (
  `record_id` BIGINT UNSIGNED NOT NULL COMMENT '父health_record主键；详情无独立版本或软删除列',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '随主记录同事务更新UTC时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '同主记录账号',
  `detail_type` VARCHAR(16) NOT NULL DEFAULT 'ACTIVITY' COMMENT 'CHECK 固定 ACTIVITY',
  `activity_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'WALK / RUN / CYCLING / STRETCH / SPORT / OTHER 等白名单',
  `custom_activity_name` VARCHAR(80) NULL COMMENT 'OTHER 必填，非临床运动处方',
  `actual_duration_minutes` SMALLINT UNSIGNED NOT NULL COMMENT '实际活动 1–1440 分钟',
  `duration_method` VARCHAR(24) NOT NULL DEFAULT 'SELF_REPORTED' COMMENT 'SELF_REPORTED / VERIFIED_PROVIDER',
  `perceived_intensity` VARCHAR(12) NULL COMMENT 'EASY / MODERATE / HARD，用户自述，非诊断',
  `distance_meters` DECIMAL(10,2) NULL COMMENT '用户明确填写或已核验来源，0–1000000',
  `distance_method` VARCHAR(24) NULL COMMENT 'SELF_REPORTED / VERIFIED_PROVIDER',
  `location_label_cipher` VARBINARY(2048) NULL COMMENT '可选粗粒度场地名称；不采精确 GPS',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '场地密钥引用',
  PRIMARY KEY (`record_id`),
  KEY `ix_activity_record_detail_user_id` (`user_id`),
  KEY `ix_activity_record_detail_user_id_record_id_detail_type` (`user_id`, `record_id`, `detail_type`),
  CONSTRAINT `ck_activity_record_detail_detail_type_enum` CHECK (CAST(`detail_type` AS BINARY) IN ('ACTIVITY')),
  CONSTRAINT `ck_activity_record_detail_activity_type_enum` CHECK (CAST(`activity_type` AS BINARY) IN ('WALK','RUN','CYCLING','STRETCH','SPORT','OTHER')),
  CONSTRAINT `ck_activity_record_detail_duration_method_enum` CHECK (CAST(`duration_method` AS BINARY) IN ('SELF_REPORTED','VERIFIED_PROVIDER')),
  CONSTRAINT `ck_activity_record_detail_perceived_intensity_enum` CHECK (CAST(`perceived_intensity` AS BINARY) IN ('EASY','MODERATE','HARD')),
  CONSTRAINT `ck_activity_record_detail_distance_method_enum` CHECK (CAST(`distance_method` AS BINARY) IN ('SELF_REPORTED','VERIFIED_PROVIDER')),
  CONSTRAINT `ck_activity_record_detail_location_label_cipher_key` CHECK (`location_label_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_activity_record_detail_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`location_label_cipher` IS NOT NULL)),
  CONSTRAINT `ck_activity_record_detail_actual_duration_minutes_range` CHECK (`actual_duration_minutes` BETWEEN 1 AND 1440),
  CONSTRAINT `ck_activity_record_detail_distance_meters_range` CHECK (`distance_meters` BETWEEN 0 AND 1000000),
  CONSTRAINT `ck_activity_record_detail_other_name` CHECK (CAST(`activity_type` AS BINARY)<>'OTHER' OR (`custom_activity_name` IS NOT NULL AND CHAR_LENGTH(TRIM(`custom_activity_name`))>=1)),
  CONSTRAINT `ck_activity_record_detail_distance_meters_pair` CHECK ((`distance_meters` IS NULL) = (`distance_method` IS NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='实际活动记录详情；P0';

-- 17. meal_record_detail / 饮食习惯记录详情 / P0
-- 记录餐次和行为描述，不估算短描述无法核实的精准热量。
CREATE TABLE `meal_record_detail` (
  `record_id` BIGINT UNSIGNED NOT NULL COMMENT '父health_record主键；详情无独立版本或软删除列',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '随主记录同事务更新UTC时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '同主记录账号',
  `detail_type` VARCHAR(16) NOT NULL DEFAULT 'MEAL' COMMENT 'CHECK 固定 MEAL',
  `meal_type` VARCHAR(16) NOT NULL COMMENT 'BREAKFAST / LUNCH / DINNER / SNACK / OTHER',
  `description_cipher` MEDIUMBLOB NOT NULL COMMENT '用户餐食描述密文，明文 1–2000 字',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '描述密钥引用',
  `vegetable_present` TINYINT UNSIGNED NULL COMMENT '0 / 1 / 未记录 NULL',
  `fruit_present` TINYINT UNSIGNED NULL COMMENT '0 / 1 / 未记录 NULL',
  `protein_food_present` TINYINT UNSIGNED NULL COMMENT '用户确认是否有蛋白质食物，非营养达标结论',
  `whole_grain_present` TINYINT UNSIGNED NULL COMMENT '0 / 1 / 未记录 NULL',
  `satiety_level` TINYINT UNSIGNED NULL COMMENT '1 未饱 / 2 较少 / 3 适中 / 4 较饱 / 5 很饱，主观',
  `meal_context` VARCHAR(20) NULL COMMENT 'CANTEEN / HOME / TAKEAWAY / RESTAURANT / OTHER',
  PRIMARY KEY (`record_id`),
  KEY `ix_meal_record_detail_user_id` (`user_id`),
  KEY `ix_meal_record_detail_user_id_record_id_detail_type` (`user_id`, `record_id`, `detail_type`),
  CONSTRAINT `ck_meal_record_detail_detail_type_enum` CHECK (CAST(`detail_type` AS BINARY) IN ('MEAL')),
  CONSTRAINT `ck_meal_record_detail_meal_type_enum` CHECK (CAST(`meal_type` AS BINARY) IN ('BREAKFAST','LUNCH','DINNER','SNACK','OTHER')),
  CONSTRAINT `ck_meal_record_detail_vegetable_present_range` CHECK (`vegetable_present` BETWEEN 0 AND 1),
  CONSTRAINT `ck_meal_record_detail_fruit_present_range` CHECK (`fruit_present` BETWEEN 0 AND 1),
  CONSTRAINT `ck_meal_record_detail_protein_food_present_range` CHECK (`protein_food_present` BETWEEN 0 AND 1),
  CONSTRAINT `ck_meal_record_detail_whole_grain_present_range` CHECK (`whole_grain_present` BETWEEN 0 AND 1),
  CONSTRAINT `ck_meal_record_detail_meal_context_enum` CHECK (CAST(`meal_context` AS BINARY) IN ('CANTEEN','HOME','TAKEAWAY','RESTAURANT','OTHER')),
  CONSTRAINT `ck_meal_record_detail_description_cipher_key` CHECK (`description_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_meal_record_detail_satiety_level_range` CHECK (`satiety_level` BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='饮食习惯记录详情；P0';

-- 18. energy_record_detail / 主观精力记录详情 / P0
-- 固定五级文字锚点，用于个人趋势回顾，不能当疾病或心理诊断量表。
CREATE TABLE `energy_record_detail` (
  `record_id` BIGINT UNSIGNED NOT NULL COMMENT '父health_record主键；详情无独立版本或软删除列',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '随主记录同事务更新UTC时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '同主记录账号',
  `detail_type` VARCHAR(16) NOT NULL DEFAULT 'ENERGY' COMMENT 'CHECK 固定 ENERGY',
  `energy_level` TINYINT UNSIGNED NOT NULL COMMENT '1 很低 / 2 较低 / 3 一般 / 4 较好 / 5 很好',
  `scale_version` SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '锚点解释版本',
  `context_code` VARCHAR(20) NULL COMMENT 'WAKE / STUDY / ACTIVITY / EVENING / OTHER',
  `description_cipher` VARBINARY(8192) NULL COMMENT '可选用户文字描述密文',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  `reported_method` VARCHAR(16) NOT NULL DEFAULT 'SELF_REPORT' COMMENT 'SELF_REPORT / AI_CONFIRMED；AI 只能提取用户明确描述',
  PRIMARY KEY (`record_id`),
  KEY `ix_energy_record_detail_user_id` (`user_id`),
  KEY `ix_energy_record_detail_user_id_record_id_detail_type` (`user_id`, `record_id`, `detail_type`),
  CONSTRAINT `ck_energy_record_detail_detail_type_enum` CHECK (CAST(`detail_type` AS BINARY) IN ('ENERGY')),
  CONSTRAINT `ck_energy_record_detail_scale_version_positive` CHECK (`scale_version` >= 1),
  CONSTRAINT `ck_energy_record_detail_context_code_enum` CHECK (CAST(`context_code` AS BINARY) IN ('WAKE','STUDY','ACTIVITY','EVENING','OTHER')),
  CONSTRAINT `ck_energy_record_detail_reported_method_enum` CHECK (CAST(`reported_method` AS BINARY) IN ('SELF_REPORT','AI_CONFIRMED')),
  CONSTRAINT `ck_energy_record_detail_description_cipher_key` CHECK (`description_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_energy_record_detail_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`description_cipher` IS NOT NULL)),
  CONSTRAINT `ck_energy_record_detail_energy_level_range` CHECK (`energy_level` BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='主观精力记录详情；P0';

-- 19. record_tag / 用户记录标签 / P0
-- 同账号复用的自定义行为标签，标签本身可能包含隐私。
CREATE TABLE `record_tag` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `tag_code` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定标签 UUID，不使用显示名做外键',
  `name_cipher` VARBINARY(1024) NOT NULL COMMENT '1–30 字显示名密文',
  `normalized_name_hash` BINARY(32) NOT NULL COMMENT '按用户域 HMAC 归一化标签名，用于去重',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '密钥引用',
  `color_token` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NEUTRAL' COMMENT '预定义颜色码，拒绝任意样式脚本',
  `applicable_record_type` VARCHAR(16) NULL COMMENT '空表示各类型通用',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '用户展示顺序',
  `is_archived` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '归档隐藏但保留历史关联',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_record_tag_user_id_tag_code` (`user_id`, `tag_code`),
  UNIQUE KEY `uq_record_tag_user_id_normalized_name_hash` (`user_id`, `normalized_name_hash`),
  UNIQUE KEY `uq_record_tag_user_id_id` (`user_id`, `id`),
  KEY `ix_record_tag_user_id_is_archived_sort_order_id` (`user_id`, `is_archived`, `sort_order`, `id`),
  CONSTRAINT `ck_record_tag_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_record_tag_applicable_record_type_enum` CHECK (CAST(`applicable_record_type` AS BINARY) IN ('SLEEP','ACTIVITY','MEAL','ENERGY')),
  CONSTRAINT `ck_record_tag_is_archived_range` CHECK (`is_archived` BETWEEN 0 AND 1),
  CONSTRAINT `ck_record_tag_name_cipher_key` CHECK (`name_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户记录标签；P0';

-- 20. record_tag_relation / 记录与标签关系 / P0
-- 结构化多标签关系，避免把可检索标签塞进大 JSON。
CREATE TABLE `record_tag_relation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '同记录与标签账号',
  `record_id` BIGINT UNSIGNED NOT NULL COMMENT '生活记录 FK',
  `tag_id` BIGINT UNSIGNED NOT NULL COMMENT '标签 FK',
  `assigned_source` VARCHAR(20) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / AI_CONFIRMED，用户确认后才关联',
  `operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '此次关联操作',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_record_tag_relation_record_id_tag_id` (`record_id`, `tag_id`),
  UNIQUE KEY `uq_record_tag_relation_user_id_id` (`user_id`, `id`),
  KEY `ix_record_tag_relation_user_id_tag_id_record_id` (`user_id`, `tag_id`, `record_id`),
  KEY `ix_record_tag_relation_user_id_record_id` (`user_id`, `record_id`),
  CONSTRAINT `ck_record_tag_relation_assigned_source_enum` CHECK (CAST(`assigned_source` AS BINARY) IN ('MANUAL','AI_CONFIRMED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='记录与标签关系；P0';

-- 21. record_attachment / 记录附件关系 / P1
-- 附件关联与文件生命周期分离，不直接保存公网 URL。
CREATE TABLE `record_attachment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '同账号',
  `record_id` BIGINT UNSIGNED NOT NULL COMMENT '记录 FK',
  `file_id` BIGINT UNSIGNED NOT NULL COMMENT 'file_object FK',
  `attachment_role` VARCHAR(16) NOT NULL COMMENT 'PHOTO / NOTE_ATTACHMENT',
  `sort_order` SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '同记录内顺序',
  `caption_cipher` VARBINARY(2048) NULL COMMENT '可选说明密文',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '说明密钥引用',
  `operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联操作号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_record_attachment_record_id_file_id` (`record_id`, `file_id`),
  UNIQUE KEY `uq_record_attachment_user_id_id` (`user_id`, `id`),
  KEY `ix_record_attachment_user_id_file_id_record_id` (`user_id`, `file_id`, `record_id`),
  KEY `ix_record_attachment_user_id_record_id` (`user_id`, `record_id`),
  CONSTRAINT `ck_record_attachment_attachment_role_enum` CHECK (CAST(`attachment_role` AS BINARY) IN ('PHOTO','NOTE_ATTACHMENT')),
  CONSTRAINT `ck_record_attachment_caption_cipher_key` CHECK (`caption_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_record_attachment_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`caption_cipher` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='记录附件关系；P1';

-- 22. plan / 计划长期主体 / P0
-- 计划的稳定身份，当前生效版本只有 active_version_id 一个权威来源。
CREATE TABLE `plan` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `plan_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端稳定计划 UUID',
  `name` VARCHAR(100) NOT NULL COMMENT '计划名称',
  `plan_kind` VARCHAR(12) NOT NULL COMMENT 'DAY / WEEK / CUSTOM',
  `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / ACTIVE / PAUSED / ARCHIVED',
  `range_start_date` DATE NOT NULL COMMENT '计划显示起始本地日',
  `range_end_date` DATE NOT NULL COMMENT '结束本地日，建议单次不超过 31 日',
  `plan_time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '计划解释时区',
  `active_version_id` BIGINT UNSIGNED NULL COMMENT '本计划当前 SEALED 版本，精确归属外键',
  `latest_version_no` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '在锁 plan 后递增，仅分配版本号',
  `source_type` VARCHAR(20) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / AI_CONFIRMED / RESTORE',
  `origin_draft_id` BIGINT UNSIGNED NULL COMMENT '初始 AI 草稿',
  `paused_at` DATETIME(3) NULL COMMENT '暂停时间',
  `archived_at` DATETIME(3) NULL COMMENT '归档时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_plan_user_id_plan_key` (`user_id`, `plan_key`),
  UNIQUE KEY `uq_plan_user_id_id` (`user_id`, `id`),
  KEY `ix_plan_user_id_status_range_start_date_id` (`user_id`, `status`, `range_start_date`, `id`),
  KEY `ix_plan_user_id_origin_draft_id` (`user_id`, `origin_draft_id`),
  KEY `ix_plan_user_id_id_active_version_id` (`user_id`, `id`, `active_version_id`),
  CONSTRAINT `ck_plan_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_plan_plan_kind_enum` CHECK (CAST(`plan_kind` AS BINARY) IN ('DAY','WEEK','CUSTOM')),
  CONSTRAINT `ck_plan_status_enum` CHECK (CAST(`status` AS BINARY) IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),
  CONSTRAINT `ck_plan_source_type_enum` CHECK (CAST(`source_type` AS BINARY) IN ('MANUAL','AI_CONFIRMED','RESTORE')),
  CONSTRAINT `ck_plan_valid_date_range` CHECK (`range_end_date` IS NULL OR `range_end_date` >= `range_start_date`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='计划长期主体；P0';

-- 23. plan_version / 计划版本与封存 / P0
-- 草稿可编辑，封存快照不可修改；旧版仍 SEALED，不通过改状态表示失效。
CREATE TABLE `plan_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `plan_id` BIGINT UNSIGNED NOT NULL COMMENT '长期计划 FK',
  `version_no` INT UNSIGNED NOT NULL COMMENT '计划内 1 起递增，与 row_version 不同',
  `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / SEALED / DISCARDED',
  `based_on_version_id` BIGINT UNSIGNED NULL COMMENT '创建时基础版本',
  `restore_from_version_id` BIGINT UNSIGNED NULL COMMENT '从历史复制时的来源版本',
  `source_type` VARCHAR(20) NOT NULL COMMENT 'MANUAL / AI_CONFIRMED / RESTORE',
  `source_draft_id` BIGINT UNSIGNED NULL COMMENT '来源草稿',
  `effective_from` DATETIME(3) NOT NULL COMMENT '本次变更开始影响的 UTC 时点',
  `affected_task_ids_json` JSON NOT NULL COMMENT '明确本次允许修改/移除的稳定任务 ID 字符串列表',
  `change_summary` VARCHAR(500) NOT NULL COMMENT '用户可读的变更摘要',
  `constraint_snapshot_json` JSON NOT NULL COMMENT '最小目标版本、时间规则版本、生成参数快照，不含完整敏感备注',
  `validation_report_json` JSON NULL COMMENT '冲突/越界/缺失校验结果及校验规则版本',
  `sealed_at` DATETIME(3) NULL COMMENT '封存时间',
  `sealed_by_operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '封存幂等操作 UUID',
  `content_digest` BINARY(32) NULL COMMENT '规范化快照内容摘要',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_plan_version_plan_id_version_no` (`plan_id`, `version_no`),
  UNIQUE KEY `uq_plan_version_user_id_id` (`user_id`, `id`),
  UNIQUE KEY `uq_plan_version_user_id_plan_id_id` (`user_id`, `plan_id`, `id`),
  UNIQUE KEY `uq_plan_version_user_id_sealed_by_operation_id` (`user_id`, `sealed_by_operation_id`),
  KEY `ix_plan_version_plan_id_status_version_no` (`plan_id`, `status`, `version_no`),
  KEY `ix_plan_version_user_id_plan_id_based_on_version_id` (`user_id`, `plan_id`, `based_on_version_id`),
  KEY `ix_plan_version_user_id_plan_id_restore_from_version_id` (`user_id`, `plan_id`, `restore_from_version_id`),
  KEY `ix_plan_version_user_id_source_draft_id` (`user_id`, `source_draft_id`),
  CONSTRAINT `ck_plan_version_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_plan_version_version_no_positive` CHECK (`version_no` >= 1),
  CONSTRAINT `ck_plan_version_status_enum` CHECK (CAST(`status` AS BINARY) IN ('DRAFT','SEALED','DISCARDED')),
  CONSTRAINT `ck_plan_version_source_type_enum` CHECK (CAST(`source_type` AS BINARY) IN ('MANUAL','AI_CONFIRMED','RESTORE')),
  CONSTRAINT `ck_plan_version_sealed_metadata` CHECK (CAST(`status` AS BINARY)<>'SEALED' OR (`sealed_at` IS NOT NULL AND `sealed_by_operation_id` IS NOT NULL AND `content_digest` IS NOT NULL)),
  CONSTRAINT `ck_plan_version_affected_task_ids_json_shape` CHECK (JSON_TYPE(`affected_task_ids_json`) IN ('ARRAY')),
  CONSTRAINT `ck_plan_version_constraint_snapshot_json_shape` CHECK (JSON_TYPE(`constraint_snapshot_json`) IN ('OBJECT')),
  CONSTRAINT `ck_plan_version_validation_report_json_shape` CHECK (JSON_TYPE(`validation_report_json`) IN ('OBJECT')),
  CONSTRAINT `ck_plan_version_affected_task_ids_json_schema` CHECK (JSON_SCHEMA_VALID('{"type":"array","maxItems":500,"uniqueItems":true,"items":{"type":"string","pattern":"^[1-9][0-9]{0,19}$"}}',`affected_task_ids_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='计划版本与封存；P0';

-- 24. task / 稳定执行实例 / P0
-- 每个日期一次实际执行一个稳定 id，改名称、时间或版本后仍是同一实例。
CREATE TABLE `task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `plan_id` BIGINT UNSIGNED NOT NULL COMMENT '所属计划',
  `current_plan_version_id` BIGINT UNSIGNED NULL COMMENT '最近更新该实例配置的封存版本',
  `rule_id` BIGINT UNSIGNED NULL COMMENT 'P1 循环逻辑规则',
  `occurrence_key` VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '循环规则内稳定发生键，不使用可变标题',
  `title` VARCHAR(100) NOT NULL COMMENT '任务名称',
  `task_type` VARCHAR(24) NOT NULL COMMENT 'ROUTINE / ACTIVITY / MEAL_HABIT / RECORDING / CUSTOM',
  `scheduled_start_at` DATETIME(3) NOT NULL COMMENT '计划开始 UTC',
  `scheduled_end_at` DATETIME(3) NOT NULL COMMENT '计划结束 UTC',
  `task_local_date` DATE NOT NULL COMMENT '开始所在的已确认业务日',
  `task_time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结解释时区',
  `planned_duration_minutes` SMALLINT UNSIGNED NOT NULL COMMENT '计划时长 1–1440，不是实际活动量',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'DRAFT / PENDING / COMPLETED / SKIPPED / CANCELLED',
  `priority` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1–5',
  `is_fixed` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '固定时间任务调整须明确确认',
  `instruction_cipher` VARBINARY(8192) NULL COMMENT '可选执行提示密文，不存医学处方',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  `completed_at` DATETIME(3) NULL COMMENT '状态完成时确认时间',
  `cancelled_at` DATETIME(3) NULL COMMENT '从当前执行集合移除时间',
  `source_type` VARCHAR(20) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / AI_CONFIRMED / RULE_EXPANDED',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_task_user_id_id` (`user_id`, `id`),
  UNIQUE KEY `uq_task_rule_id_occurrence_key` (`rule_id`, `occurrence_key`),
  UNIQUE KEY `uq_task_user_id_plan_id_id` (`user_id`, `plan_id`, `id`),
  KEY `ix_task_user_id_task_local_date_status_scheduled_start_at_id` (`user_id`, `task_local_date`, `status`, `scheduled_start_at`, `id`),
  KEY `ix_task_plan_id_scheduled_start_at_id` (`plan_id`, `scheduled_start_at`, `id`),
  KEY `ix_task_user_id_status_scheduled_start_at_id` (`user_id`, `status`, `scheduled_start_at`, `id`),
  KEY `ix_task_user_id_plan_id_current_plan_version_id` (`user_id`, `plan_id`, `current_plan_version_id`),
  KEY `ix_task_user_id_plan_id_rule_id` (`user_id`, `plan_id`, `rule_id`),
  CONSTRAINT `ck_task_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_task_task_type_enum` CHECK (CAST(`task_type` AS BINARY) IN ('ROUTINE','ACTIVITY','MEAL_HABIT','RECORDING','CUSTOM')),
  CONSTRAINT `ck_task_status_enum` CHECK (CAST(`status` AS BINARY) IN ('DRAFT','PENDING','COMPLETED','SKIPPED','CANCELLED')),
  CONSTRAINT `ck_task_is_fixed_range` CHECK (`is_fixed` BETWEEN 0 AND 1),
  CONSTRAINT `ck_task_source_type_enum` CHECK (CAST(`source_type` AS BINARY) IN ('MANUAL','AI_CONFIRMED','RULE_EXPANDED')),
  CONSTRAINT `ck_task_instruction_cipher_key` CHECK (`instruction_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_task_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`instruction_cipher` IS NOT NULL)),
  CONSTRAINT `ck_task_planned_duration_minutes_range` CHECK (`planned_duration_minutes` BETWEEN 1 AND 1440),
  CONSTRAINT `ck_task_priority_range` CHECK (`priority` BETWEEN 1 AND 5),
  CONSTRAINT `ck_task_schedule` CHECK (`scheduled_end_at` > `scheduled_start_at` AND `planned_duration_minutes`*60 <= TIMESTAMPDIFF(SECOND,`scheduled_start_at`,`scheduled_end_at`)),
  CONSTRAINT `ck_task_rule_id_pair` CHECK ((`rule_id` IS NULL) = (`occurrence_key` IS NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='稳定执行实例；P0';

-- 25. plan_version_task / 计划版本任务快照 / P0
-- 完整保留每个版本中的任务配置，历史展示不读取可变 task 当前文案。
CREATE TABLE `plan_version_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `version_id` BIGINT UNSIGNED NOT NULL COMMENT '版本 FK',
  `task_id` BIGINT UNSIGNED NULL COMMENT '新增草稿暂空，SEALED 前必须分配稳定实例',
  `draft_task_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '版本内稳定草稿 UUID',
  `sort_order` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '版本展示顺序',
  `title` VARCHAR(100) NOT NULL COMMENT '当时任务名快照',
  `task_type` VARCHAR(24) NOT NULL COMMENT '当时类型',
  `scheduled_start_at` DATETIME(3) NOT NULL COMMENT '当时计划开始 UTC',
  `scheduled_end_at` DATETIME(3) NOT NULL COMMENT '当时计划结束 UTC',
  `planned_duration_minutes` SMALLINT UNSIGNED NOT NULL COMMENT '当时计划分钟',
  `task_time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '当时 IANA 时区',
  `task_local_date` DATE NOT NULL COMMENT '当时业务日',
  `is_fixed` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当时固定时间标记',
  `priority` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '当时优先级',
  `instruction_cipher` VARBINARY(8192) NULL COMMENT '当时提示密文',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  `reminder_snapshot_json` JSON NOT NULL COMMENT '提醒意愿参数快照，无 OS 凭据',
  `change_action` VARCHAR(12) NOT NULL COMMENT 'ADD / KEEP / UPDATE / REMOVE',
  `rule_id` BIGINT UNSIGNED NULL COMMENT '当时逻辑循环模板ID快照；手工单次为空',
  `occurrence_key` VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当时规则内发生键，与rule_id同空同有',
  `plan_id` BIGINT UNSIGNED NOT NULL COMMENT '服务端从version读取的计划ID，不接受客户端可写；用于防同账号跨计划拼接',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_plan_version_task_version_id_draft_task_key` (`version_id`, `draft_task_key`),
  UNIQUE KEY `uq_plan_version_task_version_id_task_id` (`version_id`, `task_id`),
  UNIQUE KEY `uq_plan_version_task_user_id_version_id_task_id_id` (`user_id`, `version_id`, `task_id`, `id`),
  UNIQUE KEY `uq_plan_version_task_version_id_rule_id_occurrence_key` (`version_id`, `rule_id`, `occurrence_key`),
  UNIQUE KEY `uq_plan_version_task_user_id_id` (`user_id`, `id`),
  KEY `ix_plan_version_task_version_id_sort_order_id` (`version_id`, `sort_order`, `id`),
  KEY `ix_plan_version_task_user_id_plan_id_version_id` (`user_id`, `plan_id`, `version_id`),
  KEY `ix_plan_version_task_user_id_plan_id_task_id` (`user_id`, `plan_id`, `task_id`),
  KEY `ix_plan_version_task_user_id_plan_id_rule_id` (`user_id`, `plan_id`, `rule_id`),
  CONSTRAINT `ck_plan_version_task_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_plan_version_task_task_type_enum` CHECK (CAST(`task_type` AS BINARY) IN ('ROUTINE','ACTIVITY','MEAL_HABIT','RECORDING','CUSTOM')),
  CONSTRAINT `ck_plan_version_task_is_fixed_range` CHECK (`is_fixed` BETWEEN 0 AND 1),
  CONSTRAINT `ck_plan_version_task_change_action_enum` CHECK (CAST(`change_action` AS BINARY) IN ('ADD','KEEP','UPDATE','REMOVE')),
  CONSTRAINT `ck_plan_version_task_instruction_cipher_key` CHECK (`instruction_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_plan_version_task_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`instruction_cipher` IS NOT NULL)),
  CONSTRAINT `ck_plan_version_task_planned_duration_minutes_range` CHECK (`planned_duration_minutes` BETWEEN 1 AND 1440),
  CONSTRAINT `ck_plan_version_task_priority_range` CHECK (`priority` BETWEEN 1 AND 5),
  CONSTRAINT `ck_plan_version_task_schedule` CHECK (`scheduled_end_at` > `scheduled_start_at` AND `planned_duration_minutes`*60 <= TIMESTAMPDIFF(SECOND,`scheduled_start_at`,`scheduled_end_at`)),
  CONSTRAINT `ck_plan_version_task_rule_id_pair` CHECK ((`rule_id` IS NULL) = (`occurrence_key` IS NULL)),
  CONSTRAINT `ck_plan_version_task_reminder_snapshot_json_shape` CHECK (JSON_TYPE(`reminder_snapshot_json`) IN ('OBJECT'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='计划版本任务快照；P0';

-- 26. plan_rule / 循环任务逻辑规则 / P1
-- 定义未来任务如何展开，规则不是一条实际任务。
CREATE TABLE `plan_rule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `plan_id` BIGINT UNSIGNED NOT NULL COMMENT '所属长期计划',
  `rule_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规则稳定 UUID',
  `title` VARCHAR(100) NOT NULL COMMENT '模板标题',
  `task_type` VARCHAR(24) NOT NULL COMMENT '模板类型',
  `recurrence_kind` VARCHAR(12) NOT NULL COMMENT 'DAILY / WEEKLY；首版不支持任意 RRULE',
  `weekdays_json` JSON NULL COMMENT 'WEEKLY 时 1–7 唯一数组',
  `start_local_time` TIME NOT NULL COMMENT '当天本地时分',
  `planned_duration_minutes` SMALLINT UNSIGNED NOT NULL COMMENT '1–1440',
  `time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规则时区',
  `valid_from` DATE NOT NULL COMMENT '起始业务日',
  `valid_until` DATE NULL COMMENT '截止业务日',
  `status` VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / PAUSED / ARCHIVED',
  `last_expanded_through` DATE NULL COMMENT '已确认展开到的本地日',
  `template_revision` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '模板修订，不回改过去实例',
  `end_local_time` TIME NOT NULL COMMENT '结束本地时分，结合end_day_offset形成可用窗',
  `end_day_offset` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 当日 / 1 次日',
  `is_fixed` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '展开任务的固定时间意愿',
  `instruction_cipher` VARBINARY(8192) NULL COMMENT '模板说明密文，展开后冻结入快照',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '说明密钥引用',
  `reminder_snapshot_json` JSON NOT NULL COMMENT '模板提醒参数，无平台凭据',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_plan_rule_user_id_rule_key` (`user_id`, `rule_key`),
  UNIQUE KEY `uq_plan_rule_user_id_id` (`user_id`, `id`),
  UNIQUE KEY `uq_plan_rule_user_id_plan_id_id` (`user_id`, `plan_id`, `id`),
  KEY `ix_plan_rule_user_id_status_last_expanded_through_id` (`user_id`, `status`, `last_expanded_through`, `id`),
  CONSTRAINT `ck_plan_rule_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_plan_rule_task_type_enum` CHECK (CAST(`task_type` AS BINARY) IN ('ROUTINE','ACTIVITY','MEAL_HABIT','RECORDING','CUSTOM')),
  CONSTRAINT `ck_plan_rule_recurrence_kind_enum` CHECK (CAST(`recurrence_kind` AS BINARY) IN ('DAILY','WEEKLY')),
  CONSTRAINT `ck_plan_rule_start_local_time_time` CHECK (`start_local_time` >= '00:00:00' AND `start_local_time` < '24:00:00' AND MOD(TIME_TO_SEC(`start_local_time`),60)=0),
  CONSTRAINT `ck_plan_rule_status_enum` CHECK (CAST(`status` AS BINARY) IN ('ACTIVE','PAUSED','ARCHIVED')),
  CONSTRAINT `ck_plan_rule_template_revision_positive` CHECK (`template_revision` >= 1),
  CONSTRAINT `ck_plan_rule_end_local_time_time` CHECK (`end_local_time` >= '00:00:00' AND `end_local_time` < '24:00:00' AND MOD(TIME_TO_SEC(`end_local_time`),60)=0),
  CONSTRAINT `ck_plan_rule_end_day_offset_range` CHECK (`end_day_offset` BETWEEN 0 AND 1),
  CONSTRAINT `ck_plan_rule_is_fixed_range` CHECK (`is_fixed` BETWEEN 0 AND 1),
  CONSTRAINT `ck_plan_rule_instruction_cipher_key` CHECK (`instruction_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_plan_rule_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`instruction_cipher` IS NOT NULL)),
  CONSTRAINT `ck_plan_rule_planned_duration_minutes_range` CHECK (`planned_duration_minutes` BETWEEN 1 AND 1440),
  CONSTRAINT `ck_plan_rule_valid_date_range` CHECK (`valid_until` IS NULL OR `valid_until` >= `valid_from`),
  CONSTRAINT `ck_plan_rule_local_window` CHECK ((TIME_TO_SEC(`end_local_time`)+86400*`end_day_offset`-TIME_TO_SEC(`start_local_time`)) BETWEEN 60 AND 86400 AND `planned_duration_minutes`*60 <= TIME_TO_SEC(`end_local_time`)+86400*`end_day_offset`-TIME_TO_SEC(`start_local_time`)),
  CONSTRAINT `ck_plan_rule_weekly_days` CHECK (CAST(`recurrence_kind` AS BINARY)<>'WEEKLY' OR `weekdays_json` IS NOT NULL),
  CONSTRAINT `ck_plan_rule_weekdays_json_shape` CHECK (JSON_TYPE(`weekdays_json`) IN ('ARRAY')),
  CONSTRAINT `ck_plan_rule_reminder_snapshot_json_shape` CHECK (JSON_TYPE(`reminder_snapshot_json`) IN ('OBJECT')),
  CONSTRAINT `ck_plan_rule_weekdays_json_schema` CHECK (JSON_SCHEMA_VALID('{"type":"array","minItems":1,"maxItems":7,"uniqueItems":true,"items":{"type":"integer","minimum":1,"maximum":7}}',`weekdays_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='循环任务逻辑规则；P1';

-- 27. task_event / 任务执行与调整事件 / P0
-- 追加保存用户动作及必要前后版本，方便解释延期与执行统计。
CREATE TABLE `task_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `task_id` BIGINT UNSIGNED NOT NULL COMMENT '稳定任务 FK',
  `event_type` VARCHAR(24) NOT NULL COMMENT 'CREATE / COMPLETE / SKIP / REOPEN / RESCHEDULE / EDIT / CANCEL',
  `operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端业务操作 UUID',
  `source_type` VARCHAR(16) NOT NULL COMMENT 'MOBILE / WATCH / AI_APPLY / SYSTEM',
  `before_row_version` BIGINT UNSIGNED NOT NULL COMMENT '动作前任务版本',
  `after_row_version` BIGINT UNSIGNED NOT NULL COMMENT '动作后任务版本',
  `occurred_at` DATETIME(3) NOT NULL COMMENT '服务器动作确认 UTC',
  `client_occurred_at` DATETIME(3) NULL COMMENT '客户端声称发生时间，不能作同步序号',
  `old_status` VARCHAR(16) NULL COMMENT '创建为空',
  `new_status` VARCHAR(16) NOT NULL COMMENT '动作后状态',
  `before_schedule_json` JSON NULL COMMENT '改时前本地日/UTC/时区/计划分钟最小快照',
  `after_schedule_json` JSON NULL COMMENT '改时后最小快照',
  `reason_code` VARCHAR(32) NULL COMMENT 'BUSY / NOT_READY / WEATHER / OTHER 等',
  `reason_cipher` VARBINARY(4096) NULL COMMENT '用户原因密文',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  `plan_version_id` BIGINT UNSIGNED NOT NULL COMMENT '内容变更对应新封存版本',
  `plan_version_task_id` BIGINT UNSIGNED NOT NULL COMMENT '触发时不可变配置快照 FK，COMPLETE/SKIP/RESCHEDULE/EDIT 必填',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_task_event_task_id_operation_id_event_type` (`task_id`, `operation_id`, `event_type`),
  UNIQUE KEY `uq_task_event_user_id_id` (`user_id`, `id`),
  KEY `ix_task_event_user_id_occurred_at_id` (`user_id`, `occurred_at`, `id`),
  KEY `ix_task_event_task_id_id` (`task_id`, `id`),
  KEY `ix_task_event_user_id_task_id` (`user_id`, `task_id`),
  KEY `ix_task_event_user_id_plan_version_id_task_id_plan_vers_1a00be2a` (`user_id`, `plan_version_id`, `task_id`, `plan_version_task_id`),
  CONSTRAINT `ck_task_event_event_type_enum` CHECK (CAST(`event_type` AS BINARY) IN ('CREATE','COMPLETE','SKIP','REOPEN','RESCHEDULE','EDIT','CANCEL')),
  CONSTRAINT `ck_task_event_source_type_enum` CHECK (CAST(`source_type` AS BINARY) IN ('MOBILE','WATCH','AI_APPLY','SYSTEM')),
  CONSTRAINT `ck_task_event_after_row_version_positive` CHECK (`after_row_version` >= 1),
  CONSTRAINT `ck_task_event_reason_cipher_key` CHECK (`reason_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_task_event_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`reason_cipher` IS NOT NULL)),
  CONSTRAINT `ck_task_event_row_versions` CHECK (`after_row_version` > `before_row_version`),
  CONSTRAINT `ck_task_event_before_schedule_json_shape` CHECK (JSON_TYPE(`before_schedule_json`) IN ('OBJECT')),
  CONSTRAINT `ck_task_event_after_schedule_json_shape` CHECK (JSON_TYPE(`after_schedule_json`) IN ('OBJECT'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='任务执行与调整事件；P0';

-- 28. task_record_link / 任务与实际记录关联 / P0
-- 让完成任务后补的真实记录可追溯，同时避免重复统计记录。
CREATE TABLE `task_record_link` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '同账号',
  `task_id` BIGINT UNSIGNED NOT NULL COMMENT '任务 FK',
  `record_id` BIGINT UNSIGNED NOT NULL COMMENT '实际记录 FK',
  `link_type` VARCHAR(16) NOT NULL DEFAULT 'USER_CONFIRMED' COMMENT 'USER_CONFIRMED / AI_CONFIRMED',
  `operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联动作 UUID',
  `linked_at` DATETIME(3) NOT NULL COMMENT '确认关联时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_task_record_link_task_id_record_id` (`task_id`, `record_id`),
  UNIQUE KEY `uq_task_record_link_user_id_id` (`user_id`, `id`),
  KEY `ix_task_record_link_user_id_record_id_task_id` (`user_id`, `record_id`, `task_id`),
  KEY `ix_task_record_link_user_id_task_id` (`user_id`, `task_id`),
  CONSTRAINT `ck_task_record_link_link_type_enum` CHECK (CAST(`link_type` AS BINARY) IN ('USER_CONFIRMED','AI_CONFIRMED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='任务与实际记录关联；P0';

-- 29. ai_conversation / 助手会话 / P0
-- 组织用户对话及最小上下文，不把所有健康历史默认发给模型。
CREATE TABLE `ai_conversation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `conversation_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端稳定会话 UUID',
  `title_cipher` VARBINARY(2048) NULL COMMENT '可能包含健康隐私的会话标题密文',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '标题密钥引用',
  `status` VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
  `context_policy` VARCHAR(24) NOT NULL DEFAULT 'MINIMUM_NECESSARY' COMMENT '最小摘要策略代码',
  `last_message_at` DATETIME(3) NULL COMMENT '最近消息时间',
  `last_message_id` BIGINT UNSIGNED NULL COMMENT '会话内最后消息',
  `message_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '可重建计数，非计费依据',
  `pinned_at` DATETIME(3) NULL COMMENT '置顶时间',
  `retention_until` DATETIME(3) NULL COMMENT '用户选定的会话内容保留截止',
  `scene` VARCHAR(12) NOT NULL DEFAULT 'GENERAL' COMMENT 'GENERAL / PLAN / RECORD / REVIEW，只选上下文策略不增加权限',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ai_conversation_user_id_conversation_key` (`user_id`, `conversation_key`),
  UNIQUE KEY `uq_ai_conversation_user_id_id` (`user_id`, `id`),
  KEY `ix_ai_conversation_user_id_status_last_message_at_id` (`user_id`, `status`, `last_message_at`, `id`),
  KEY `ix_ai_conversation_user_id_id_last_message_id` (`user_id`, `id`, `last_message_id`),
  CONSTRAINT `ck_ai_conversation_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_ai_conversation_status_enum` CHECK (CAST(`status` AS BINARY) IN ('ACTIVE','ARCHIVED')),
  CONSTRAINT `ck_ai_conversation_scene_enum` CHECK (CAST(`scene` AS BINARY) IN ('GENERAL','PLAN','RECORD','REVIEW')),
  CONSTRAINT `ck_ai_conversation_title_cipher_key` CHECK (`title_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_ai_conversation_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`title_cipher` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='助手会话；P0';

-- 30. ai_message / 会话消息与操作卡片 / P0
-- 消息原文加密，UI 卡片引用草稿稳定 ID，不在聊天里直接执行命令。
CREATE TABLE `ai_message` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `conversation_id` BIGINT UNSIGNED NOT NULL COMMENT '会话 FK',
  `client_message_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '消息请求 UUID，去重',
  `role` VARCHAR(16) NOT NULL COMMENT 'USER / ASSISTANT / SYSTEM_NOTICE；SQL落地勘误：VARCHAR16可容纳SYSTEM_NOTICE（13字符）',
  `message_type` VARCHAR(24) NOT NULL COMMENT 'TEXT / CLARIFICATION / PLAN_CARD / RECORD_CARD / SOURCE_CARD / ERROR_NOTICE',
  `content_cipher` MEDIUMBLOB NULL COMMENT '正文密文，输入建议上限 6000 字',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  `delivery_status` VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED' COMMENT 'ACCEPTED / GENERATING / READY / FAILED / CANCELLED',
  `reply_to_message_id` BIGINT UNSIGNED NULL COMMENT '同会话上游消息',
  `ai_run_id` BIGINT UNSIGNED NULL COMMENT '该助手输出的运行记录',
  `draft_id` BIGINT UNSIGNED NULL COMMENT '操作卡片指向草稿',
  `finish_reason` VARCHAR(32) NULL COMMENT 'STOP / LENGTH / FILTERED / CANCELLED / PROVIDER_ERROR',
  `content_digest` BINARY(32) NULL COMMENT '消息内容完整性校验，非跨用户文本去重',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ai_message_conversation_id_client_message_id` (`conversation_id`, `client_message_id`),
  UNIQUE KEY `uq_ai_message_user_id_id` (`user_id`, `id`),
  UNIQUE KEY `uq_ai_message_user_id_conversation_id_id` (`user_id`, `conversation_id`, `id`),
  KEY `ix_ai_message_conversation_id_id` (`conversation_id`, `id`),
  KEY `ix_ai_message_user_id_conversation_id_reply_to_message_id` (`user_id`, `conversation_id`, `reply_to_message_id`),
  KEY `ix_ai_message_user_id_ai_run_id` (`user_id`, `ai_run_id`),
  KEY `ix_ai_message_user_id_draft_id` (`user_id`, `draft_id`),
  CONSTRAINT `ck_ai_message_role_enum` CHECK (CAST(`role` AS BINARY) IN ('USER','ASSISTANT','SYSTEM_NOTICE')),
  CONSTRAINT `ck_ai_message_message_type_enum` CHECK (CAST(`message_type` AS BINARY) IN ('TEXT','CLARIFICATION','PLAN_CARD','RECORD_CARD','SOURCE_CARD','ERROR_NOTICE')),
  CONSTRAINT `ck_ai_message_delivery_status_enum` CHECK (CAST(`delivery_status` AS BINARY) IN ('ACCEPTED','GENERATING','READY','FAILED','CANCELLED')),
  CONSTRAINT `ck_ai_message_finish_reason_enum` CHECK (CAST(`finish_reason` AS BINARY) IN ('STOP','LENGTH','FILTERED','CANCELLED','PROVIDER_ERROR')),
  CONSTRAINT `ck_ai_message_content_cipher_key` CHECK (`content_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_ai_message_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`content_cipher` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='会话消息与操作卡片；P0';

-- 31. ai_run / AI 运行与异步控制 / P0
-- 管理调用、预算、重试与 epoch/授权门槛；运行成功不代表草稿已应用。
CREATE TABLE `ai_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `run_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一次用户意图运行 UUID',
  `kind` VARCHAR(24) NOT NULL COMMENT 'PLAN_GENERATE / PLAN_ADJUST / RECORD_EXTRACT / CHAT / WEEKLY_REVIEW / VOICE_TRANSCRIBE / COURSE_IMPORT',
  `conversation_id` BIGINT UNSIGNED NULL COMMENT '会话 FK',
  `input_message_id` BIGINT UNSIGNED NULL COMMENT '输入消息 FK',
  `status` VARCHAR(20) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED / RUNNING / SUCCEEDED / FAILED / CANCEL_REQUESTED / CANCELLED',
  `provider_code` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '模型提供方适配代码',
  `model_name` VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际调用模型名，不存 API 密钥',
  `prompt_template_version` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '系统提示模板版本',
  `input_snapshot_cipher` MEDIUMBLOB NULL COMMENT '确需复核的最小输入快照密文，支持短保留',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  `captured_dataset_epoch` BIGINT UNSIGNED NOT NULL COMMENT '排队时健康数据代际',
  `captured_consent_version` BIGINT UNSIGNED NOT NULL COMMENT 'AI_PROCESSING 授权版本',
  `attempt_count` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '运行尝试次数，设置硬上限',
  `started_at` DATETIME(3) NULL COMMENT '开始时间',
  `finished_at` DATETIME(3) NULL COMMENT '终态时间',
  `timeout_at` DATETIME(3) NOT NULL COMMENT '超时截止',
  `input_tokens` INT UNSIGNED NULL COMMENT '提供方返回的计数，未知 NULL',
  `output_tokens` INT UNSIGNED NULL COMMENT '提供方计数',
  `error_code` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '脱敏结构化失败码，不存含提示词的整段异常',
  `provider_request_id` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '排障追踪号，按期限清理',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ai_run_user_id_run_key` (`user_id`, `run_key`),
  UNIQUE KEY `uq_ai_run_user_id_id` (`user_id`, `id`),
  KEY `ix_ai_run_status_timeout_at_id` (`status`, `timeout_at`, `id`),
  KEY `ix_ai_run_user_id_created_at_id` (`user_id`, `created_at`, `id`),
  KEY `ix_ai_run_user_id_conversation_id` (`user_id`, `conversation_id`),
  KEY `ix_ai_run_user_id_input_message_id` (`user_id`, `input_message_id`),
  CONSTRAINT `ck_ai_run_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_ai_run_kind_enum` CHECK (CAST(`kind` AS BINARY) IN ('PLAN_GENERATE','PLAN_ADJUST','RECORD_EXTRACT','CHAT','WEEKLY_REVIEW','VOICE_TRANSCRIBE','COURSE_IMPORT')),
  CONSTRAINT `ck_ai_run_status_enum` CHECK (CAST(`status` AS BINARY) IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCEL_REQUESTED','CANCELLED')),
  CONSTRAINT `ck_ai_run_captured_dataset_epoch_positive` CHECK (`captured_dataset_epoch` >= 1),
  CONSTRAINT `ck_ai_run_captured_consent_version_positive` CHECK (`captured_consent_version` >= 1),
  CONSTRAINT `ck_ai_run_input_snapshot_cipher_key` CHECK (`input_snapshot_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_ai_run_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`input_snapshot_cipher` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='AI 运行与异步控制；P0';

-- 32. ai_draft / 可应用 AI 草稿 / P0
-- 草稿是建议，不是已生效计划或已保存生活记录。
CREATE TABLE `ai_draft` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `draft_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '草稿稳定 UUID',
  `kind` VARCHAR(20) NOT NULL COMMENT 'PLAN_CREATE / PLAN_ADJUST / RECORD_EXTRACT',
  `status` VARCHAR(16) NOT NULL DEFAULT 'NEEDS_INPUT' COMMENT 'NEEDS_INPUT / READY / APPLIED / EXPIRED / CANCELLED / FAILED',
  `source_run_id` BIGINT UNSIGNED NOT NULL COMMENT '来源 AI 运行',
  `conversation_id` BIGINT UNSIGNED NULL COMMENT '所属会话',
  `current_revision_no` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '当前 revision 指针',
  `validated_revision_no` INT UNSIGNED NULL COMMENT '通过业务校验的修订号',
  `base_plan_id` BIGINT UNSIGNED NULL COMMENT '调整针对的计划',
  `base_plan_version_id` BIGINT UNSIGNED NULL COMMENT '生成时现行版本',
  `captured_dataset_epoch` BIGINT UNSIGNED NOT NULL COMMENT '草稿所依赖的数据代际',
  `expires_at` DATETIME(3) NOT NULL COMMENT '草稿可应用截止',
  `applied_at` DATETIME(3) NULL COMMENT '成功应用时间',
  `applied_resource_type` VARCHAR(24) NULL COMMENT 'PLAN / PLAN_VERSION / HEALTH_RECORD',
  `applied_resource_id` BIGINT UNSIGNED NULL COMMENT '应用结果 ID，按 type 白名单校验',
  `applied_operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '唯一应用操作号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ai_draft_user_id_draft_key` (`user_id`, `draft_key`),
  UNIQUE KEY `uq_ai_draft_user_id_id` (`user_id`, `id`),
  UNIQUE KEY `uq_ai_draft_user_id_applied_operation_id` (`user_id`, `applied_operation_id`),
  KEY `ix_ai_draft_user_id_status_expires_at_id` (`user_id`, `status`, `expires_at`, `id`),
  KEY `ix_ai_draft_user_id_source_run_id` (`user_id`, `source_run_id`),
  KEY `ix_ai_draft_user_id_conversation_id` (`user_id`, `conversation_id`),
  KEY `ix_ai_draft_user_id_base_plan_id` (`user_id`, `base_plan_id`),
  KEY `ix_ai_draft_user_id_base_plan_id_base_plan_version_id` (`user_id`, `base_plan_id`, `base_plan_version_id`),
  CONSTRAINT `ck_ai_draft_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_ai_draft_kind_enum` CHECK (CAST(`kind` AS BINARY) IN ('PLAN_CREATE','PLAN_ADJUST','RECORD_EXTRACT')),
  CONSTRAINT `ck_ai_draft_status_enum` CHECK (CAST(`status` AS BINARY) IN ('NEEDS_INPUT','READY','APPLIED','EXPIRED','CANCELLED','FAILED')),
  CONSTRAINT `ck_ai_draft_current_revision_no_positive` CHECK (`current_revision_no` >= 1),
  CONSTRAINT `ck_ai_draft_validated_revision_no_positive` CHECK (`validated_revision_no` >= 1),
  CONSTRAINT `ck_ai_draft_captured_dataset_epoch_positive` CHECK (`captured_dataset_epoch` >= 1),
  CONSTRAINT `ck_ai_draft_applied_resource_type_enum` CHECK (CAST(`applied_resource_type` AS BINARY) IN ('PLAN','PLAN_VERSION','HEALTH_RECORD')),
  CONSTRAINT `ck_ai_draft_revision_order` CHECK (`validated_revision_no` IS NULL OR `validated_revision_no` <= `current_revision_no`),
  CONSTRAINT `ck_ai_draft_applied_receipt` CHECK ((CAST(`status` AS BINARY)='APPLIED' AND `applied_at` IS NOT NULL AND `applied_resource_type` IS NOT NULL AND `applied_resource_id` IS NOT NULL AND `applied_operation_id` IS NOT NULL) OR (CAST(`status` AS BINARY)<>'APPLIED' AND `applied_at` IS NULL AND `applied_resource_type` IS NULL AND `applied_resource_id` IS NULL AND `applied_operation_id` IS NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='可应用 AI 草稿；P0';

-- 33. ai_draft_revision / 草稿不可变修订 / P0
-- 保留用户编辑前后版本及其校验结果，便于预览差异。
CREATE TABLE `ai_draft_revision` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `draft_id` BIGINT UNSIGNED NOT NULL COMMENT '草稿 FK',
  `revision_no` INT UNSIGNED NOT NULL COMMENT '草稿内 1 起连续递增',
  `source_revision` INT UNSIGNED NULL COMMENT '用户编辑基于的上一修订',
  `edit_source` VARCHAR(16) NOT NULL COMMENT 'MODEL / USER / REVALIDATION',
  `payload_schema_version` SMALLINT UNSIGNED NOT NULL COMMENT '结构化动作协议版本',
  `payload_json` JSON NOT NULL COMMENT '加密 envelope（alg/keyRef/nonce/ciphertext），解密后按 kind schema 校验；不明文落健康描述',
  `base_snapshot_json` JSON NOT NULL COMMENT '最小资源 ID/rowVersion/计划 activeVersion/epoch，禁止复制无关历史',
  `validation_status` VARCHAR(16) NOT NULL DEFAULT 'NOT_VALIDATED' COMMENT 'NOT_VALIDATED / NEEDS_INPUT / VALID / INVALID',
  `validation_report_json` JSON NOT NULL COMMENT '字段码、规则码、缺失列表、冲突 ID、校验版本，不保存敏感原文',
  `validated_at` DATETIME(3) NULL COMMENT '该修订校验结束时间',
  `payload_digest` BINARY(32) NOT NULL COMMENT '规范化解密内容的用户域 HMAC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ai_draft_revision_draft_id_revision_no` (`draft_id`, `revision_no`),
  UNIQUE KEY `uq_ai_draft_revision_user_id_id` (`user_id`, `id`),
  KEY `ix_ai_draft_revision_user_id_draft_id` (`user_id`, `draft_id`),
  CONSTRAINT `ck_ai_draft_revision_revision_no_positive` CHECK (`revision_no` >= 1),
  CONSTRAINT `ck_ai_draft_revision_source_revision_positive` CHECK (`source_revision` >= 1),
  CONSTRAINT `ck_ai_draft_revision_edit_source_enum` CHECK (CAST(`edit_source` AS BINARY) IN ('MODEL','USER','REVALIDATION')),
  CONSTRAINT `ck_ai_draft_revision_payload_schema_version_positive` CHECK (`payload_schema_version` >= 1),
  CONSTRAINT `ck_ai_draft_revision_validation_status_enum` CHECK (CAST(`validation_status` AS BINARY) IN ('NOT_VALIDATED','NEEDS_INPUT','VALID','INVALID')),
  CONSTRAINT `ck_ai_draft_revision_source_revision` CHECK (`source_revision` IS NULL OR `source_revision` < `revision_no`),
  CONSTRAINT `ck_ai_draft_revision_payload_json_shape` CHECK (JSON_TYPE(`payload_json`) IN ('OBJECT')),
  CONSTRAINT `ck_ai_draft_revision_base_snapshot_json_shape` CHECK (JSON_TYPE(`base_snapshot_json`) IN ('OBJECT')),
  CONSTRAINT `ck_ai_draft_revision_validation_report_json_shape` CHECK (JSON_TYPE(`validation_report_json`) IN ('OBJECT')),
  CONSTRAINT `ck_ai_draft_revision_payload_json_encrypted_envelope` CHECK (JSON_SCHEMA_VALID('{"type":"object","required":["alg","keyRef","nonce","ciphertext"],"properties":{"alg":{"type":"string","minLength":1},"keyRef":{"type":"string","minLength":1},"nonce":{"type":"string","minLength":1},"ciphertext":{"type":"string","minLength":1}}}',`payload_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='草稿不可变修订；P0';

-- 34. ai_message_citation / 消息可核验资料引用 / P1
-- 将回答中编号落实到服务器实际检索到的发布版本与真实来源。
CREATE TABLE `ai_message_citation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '消息所属账号',
  `message_id` BIGINT UNSIGNED NOT NULL COMMENT '助手消息',
  `citation_no` SMALLINT UNSIGNED NOT NULL COMMENT '回答内从 1 起的引用编号',
  `article_version_id` BIGINT UNSIGNED NOT NULL COMMENT '当时发布的知识版本',
  `knowledge_source_id` BIGINT UNSIGNED NOT NULL COMMENT '真实来源',
  `section_anchor` VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '资料版本内段落锚点',
  `claim_excerpt_cipher` VARBINARY(4096) NULL COMMENT '确需复核时保存的短论述密文，受引用长度限制',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  `retrieved_at` DATETIME(3) NOT NULL COMMENT '检索时间',
  `retrieval_method` VARCHAR(16) NOT NULL DEFAULT 'KEYWORD' COMMENT 'KEYWORD / TAG / MANUAL；未建向量检索不填 VECTOR',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ai_message_citation_message_id_citation_no` (`message_id`, `citation_no`),
  UNIQUE KEY `uq_ai_message_citation_user_id_id` (`user_id`, `id`),
  KEY `ix_ai_message_citation_user_id_message_id_id` (`user_id`, `message_id`, `id`),
  KEY `ix_ai_message_citation_article_version_id_knowledge_source_id` (`article_version_id`, `knowledge_source_id`),
  CONSTRAINT `ck_ai_message_citation_citation_no_positive` CHECK (`citation_no` >= 1),
  CONSTRAINT `ck_ai_message_citation_retrieval_method_enum` CHECK (CAST(`retrieval_method` AS BINARY) IN ('KEYWORD','TAG','MANUAL')),
  CONSTRAINT `ck_ai_message_citation_claim_excerpt_cipher_key` CHECK (`claim_excerpt_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_ai_message_citation_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`claim_excerpt_cipher` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='消息可核验资料引用；P1';

-- 35. knowledge_article / 科普知识长期条目 / P0
-- 公共资料目录与当前发布版本指针，不含用户原文。
CREATE TABLE `knowledge_article` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `article_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定条目代码',
  `slug` VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '公开路径标识，唯一不复用',
  `category` VARCHAR(24) NOT NULL COMMENT 'ROUTINE / ACTIVITY / MEAL_HABIT / RECORDING / APP_HELP',
  `status` VARCHAR(12) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / ACTIVE / RETIRED',
  `active_version_id` BIGINT UNSIGNED NULL COMMENT '当前已发布版本',
  `latest_version_no` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '编辑版本号分配',
  `search_title` VARCHAR(150) NOT NULL COMMENT '公开检索标题，跟发布版本同步更新',
  `search_keywords` VARCHAR(500) NOT NULL COMMENT '审核后的关键词，供关键词检索',
  `tag_codes_json` JSON NOT NULL COMMENT '固定受控标签数组',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '列表顺序',
  `retired_at` DATETIME(3) NULL COMMENT '停止默认推荐时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_knowledge_article_article_code` (`article_code`),
  UNIQUE KEY `uq_knowledge_article_slug` (`slug`),
  KEY `ix_knowledge_article_status_category_sort_order_id` (`status`, `category`, `sort_order`, `id`),
  KEY `ix_knowledge_article_id_active_version_id` (`id`, `active_version_id`),
  CONSTRAINT `ck_knowledge_article_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_knowledge_article_category_enum` CHECK (CAST(`category` AS BINARY) IN ('ROUTINE','ACTIVITY','MEAL_HABIT','RECORDING','APP_HELP')),
  CONSTRAINT `ck_knowledge_article_status_enum` CHECK (CAST(`status` AS BINARY) IN ('DRAFT','ACTIVE','RETIRED')),
  CONSTRAINT `ck_knowledge_article_tag_codes_json_shape` CHECK (JSON_TYPE(`tag_codes_json`) IN ('ARRAY')),
  CONSTRAINT `ck_knowledge_article_tag_codes_json_schema` CHECK (JSON_SCHEMA_VALID('{"type":"array","maxItems":10,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":30}}',`tag_codes_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='科普知识长期条目；P0';

-- 36. knowledge_article_version / 知识审核与发布快照 / P0
-- 正文、适用边界、来源和审核信息有明确版本。
CREATE TABLE `knowledge_article_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `article_id` BIGINT UNSIGNED NOT NULL COMMENT '长期条目 FK',
  `version_no` INT UNSIGNED NOT NULL COMMENT '条目内递增版本',
  `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / IN_REVIEW / PUBLISHED / REJECTED / RETIRED',
  `title` VARCHAR(150) NOT NULL COMMENT '版本标题',
  `summary` VARCHAR(1000) NOT NULL COMMENT '摘要',
  `body_markdown` MEDIUMTEXT NOT NULL COMMENT '安全渲染的审核正文；禁止危险 HTML',
  `applicable_population` VARCHAR(500) NOT NULL COMMENT '例如成年普通大学生，不扩展为临床建议',
  `limitations` TEXT NOT NULL COMMENT '适用限制、资料不足、何时寻求专业意见',
  `source_checked_at` DATETIME(3) NULL COMMENT '人工核对来源的时间',
  `review_due_at` DATETIME(3) NULL COMMENT '下次检查日期',
  `author_user_id` BIGINT UNSIGNED NULL COMMENT '内容编辑账号，不公开个人资料',
  `reviewer_user_id` BIGINT UNSIGNED NULL COMMENT '审核账号，按权限记录',
  `review_comment` VARCHAR(1000) NULL COMMENT '审核意见，不包含用户健康信息',
  `published_at` DATETIME(3) NULL COMMENT '首次发布',
  `content_digest` BINARY(32) NOT NULL COMMENT '规范化内容摘要',
  `tags_json` JSON NOT NULL COMMENT '发布时受控标签数组快照，供检索投影；不是用户记录标签',
  `content_date` DATE NULL COMMENT '该科普版本标明的内容日期，未知为空不等于来源发布日期',
  `rights_note` VARCHAR(2000) NOT NULL COMMENT '本版本整合/引用的权利和使用依据说明',
  `review_decision` VARCHAR(16) NULL COMMENT 'APPROVED / REJECTED；审核元数据，不新增APPROVED生命周期状态',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_knowledge_article_version_article_id_version_no` (`article_id`, `version_no`),
  UNIQUE KEY `uq_knowledge_article_version_article_id_id` (`article_id`, `id`),
  KEY `ix_knowledge_article_version_status_review_due_at_id` (`status`, `review_due_at`, `id`),
  KEY `ix_knowledge_article_version_author_user_id` (`author_user_id`),
  KEY `ix_knowledge_article_version_reviewer_user_id` (`reviewer_user_id`),
  CONSTRAINT `ck_knowledge_article_version_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_knowledge_article_version_version_no_positive` CHECK (`version_no` >= 1),
  CONSTRAINT `ck_knowledge_article_version_status_enum` CHECK (CAST(`status` AS BINARY) IN ('DRAFT','IN_REVIEW','PUBLISHED','REJECTED','RETIRED')),
  CONSTRAINT `ck_knowledge_article_version_review_decision_enum` CHECK (CAST(`review_decision` AS BINARY) IN ('APPROVED','REJECTED')),
  CONSTRAINT `ck_knowledge_article_version_published_time` CHECK (CAST(`status` AS BINARY)<>'PUBLISHED' OR `published_at` IS NOT NULL),
  CONSTRAINT `ck_knowledge_article_version_different_reviewer` CHECK (`reviewer_user_id` IS NULL OR `author_user_id` IS NULL OR `reviewer_user_id`<>`author_user_id`),
  CONSTRAINT `ck_knowledge_article_version_tags_json_shape` CHECK (JSON_TYPE(`tags_json`) IN ('ARRAY')),
  CONSTRAINT `ck_knowledge_article_version_tags_json_schema` CHECK (JSON_SCHEMA_VALID('{"type":"array","maxItems":10,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":30}}',`tags_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='知识审核与发布快照；P0';

-- 37. knowledge_source / 外部权威来源登记 / P0
-- 来源链接、授权和核验记录；不假定登记即代表已经验证。
CREATE TABLE `knowledge_source` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `source_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定资料编号',
  `title` VARCHAR(300) NOT NULL COMMENT '文献或网页标题',
  `publisher` VARCHAR(200) NOT NULL COMMENT '发布机构',
  `canonical_url` VARCHAR(2048) NOT NULL COMMENT '规范化 HTTPS 来源链接，不指向搜索结果页',
  `url_hash` BINARY(32) NOT NULL COMMENT '规范化 URL 摘要',
  `source_kind` VARCHAR(24) NOT NULL COMMENT 'OFFICIAL_WEB / GUIDELINE / PAPER / APP_DOCUMENTATION；SQL落地勘误：VARCHAR24可容纳APP_DOCUMENTATION（17字符）',
  `published_date` DATE NULL COMMENT '原资料发布日期，未知为空',
  `accessed_at` DATETIME(3) NOT NULL COMMENT '最近访问核验时间',
  `verification_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / VERIFIED / UNAVAILABLE / REJECTED',
  `usage_basis` VARCHAR(24) NOT NULL COMMENT 'LINK_ONLY / PERMITTED_EXCERPT / LICENSED / PUBLIC_DOMAIN，需记录依据',
  `license_note` VARCHAR(1000) NULL COMMENT '授权/引用限制简述，非法律结论',
  `source_language` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'zh-CN' COMMENT '资料语言',
  `content_fingerprint` BINARY(32) NULL COMMENT '允许获取时保存版本摘要，用于变化提醒',
  `last_check_error` VARCHAR(200) NULL COMMENT '脱敏访问错误，不直接解释为内容无效',
  `verification_note` VARCHAR(1000) NULL COMMENT '人工核对结论/支持范围，不保存用户内容；与访问错误分开',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_knowledge_source_source_code` (`source_code`),
  UNIQUE KEY `uq_knowledge_source_url_hash` (`url_hash`),
  KEY `ix_knowledge_source_verification_status_accessed_at_id` (`verification_status`, `accessed_at`, `id`),
  CONSTRAINT `ck_knowledge_source_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_knowledge_source_source_kind_enum` CHECK (CAST(`source_kind` AS BINARY) IN ('OFFICIAL_WEB','GUIDELINE','PAPER','APP_DOCUMENTATION')),
  CONSTRAINT `ck_knowledge_source_verification_status_enum` CHECK (CAST(`verification_status` AS BINARY) IN ('PENDING','VERIFIED','UNAVAILABLE','REJECTED')),
  CONSTRAINT `ck_knowledge_source_usage_basis_enum` CHECK (CAST(`usage_basis` AS BINARY) IN ('LINK_ONLY','PERMITTED_EXCERPT','LICENSED','PUBLIC_DOMAIN'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='外部权威来源登记；P0';

-- 38. knowledge_version_source / 知识版本与来源关系 / P0
-- 引用支持关系属于内容版本，不是长期条目的可变清单。
CREATE TABLE `knowledge_version_source` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `article_version_id` BIGINT UNSIGNED NOT NULL COMMENT '知识版本',
  `knowledge_source_id` BIGINT UNSIGNED NOT NULL COMMENT '来源',
  `reference_no` SMALLINT UNSIGNED NOT NULL COMMENT '该版本内参考编号',
  `support_scope` VARCHAR(500) NOT NULL COMMENT '该来源支撑的主题/段落，避免笼统背书',
  `source_locator` VARCHAR(300) NULL COMMENT '原资料章节、页码或锚点',
  `checked_at` DATETIME(3) NOT NULL COMMENT '关联人工核对时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_knowledge_version_source_article_version_id_knowledg_f85a55cd` (`article_version_id`, `knowledge_source_id`),
  UNIQUE KEY `uq_knowledge_version_source_article_version_id_reference_no` (`article_version_id`, `reference_no`),
  KEY `ix_knowledge_version_source_knowledge_source_id` (`knowledge_source_id`),
  CONSTRAINT `ck_knowledge_version_source_reference_no_positive` CHECK (`reference_no` >= 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='知识版本与来源关系；P0';

-- 39. daily_stat / 可重建每日统计 / P0
-- 保存口径化缓存和覆盖，不替代原始记录，也不生成健康分数。
CREATE TABLE `daily_stat` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `stat_local_date` DATE NOT NULL COMMENT '统计业务日',
  `stat_time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '采用的日历解释，历史记录按冻结业务日归集',
  `metric_version` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '如 DAILY_V1，口径变更必须升级',
  `data_as_of_seq` BIGINT UNSIGNED NOT NULL COMMENT '计算使用的已提交数据高水位',
  `dataset_epoch` BIGINT UNSIGNED NOT NULL COMMENT '本次统计对应的数据代际',
  `status` VARCHAR(12) NOT NULL DEFAULT 'STALE' COMMENT 'FRESH / STALE / BUILDING / FAILED',
  `computed_at` DATETIME(3) NULL COMMENT '计算完成 UTC',
  `valid_record_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '有效原始记录数量，计数 0 是已观测无记录',
  `coverage_json` JSON NOT NULL COMMENT '各类型 validCount/hasData/排除原因；缺测不转为测量值 0',
  `main_sleep_elapsed_minutes` SMALLINT UNSIGNED NULL COMMENT '唯一明确主睡眠区间分钟；冲突时为空并标记',
  `nap_elapsed_minutes` SMALLINT UNSIGNED NULL COMMENT '不重叠小睡区间并集分钟，有记录才有值',
  `actual_activity_minutes` INT UNSIGNED NULL COMMENT '有效实际活动区间口径；缺记录 NULL',
  `meal_record_count` SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '餐次记录条数，不能推断吃了几顿',
  `energy_sample_count` SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '精力自评样本数',
  `energy_level_sum` INT UNSIGNED NULL COMMENT '有效等级和，仅用于明确标注的自评分汇总',
  `task_denominator` SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'PENDING+COMPLETED+SKIPPED 的执行集合',
  `task_completed_count` SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已完成实例数',
  `task_skipped_count` SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '跳过实例数，仍在分母',
  `quality_flags_json` JSON NOT NULL COMMENT '重叠、缺失、来源或时区不确定等白名单标记',
  `source_time_zones_json` JSON NOT NULL COMMENT '该业务日参与数据的原始时区去重列表，只解释来源，不重切业务日',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_daily_stat_user_id_stat_local_date_metric_version` (`user_id`, `stat_local_date`, `metric_version`),
  UNIQUE KEY `uq_daily_stat_user_id_id` (`user_id`, `id`),
  KEY `ix_daily_stat_user_id_status_stat_local_date_id` (`user_id`, `status`, `stat_local_date`, `id`),
  CONSTRAINT `ck_daily_stat_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_daily_stat_dataset_epoch_positive` CHECK (`dataset_epoch` >= 1),
  CONSTRAINT `ck_daily_stat_status_enum` CHECK (CAST(`status` AS BINARY) IN ('FRESH','STALE','BUILDING','FAILED')),
  CONSTRAINT `ck_daily_stat_task_counts` CHECK (`task_completed_count` + `task_skipped_count` <= `task_denominator`),
  CONSTRAINT `ck_daily_stat_energy_sum` CHECK ((`energy_sample_count`=0 AND `energy_level_sum` IS NULL) OR (`energy_sample_count`>0 AND `energy_level_sum` IS NOT NULL AND `energy_level_sum` BETWEEN `energy_sample_count` AND 5*`energy_sample_count`)),
  CONSTRAINT `ck_daily_stat_coverage_json_shape` CHECK (JSON_TYPE(`coverage_json`) IN ('OBJECT')),
  CONSTRAINT `ck_daily_stat_quality_flags_json_shape` CHECK (JSON_TYPE(`quality_flags_json`) IN ('ARRAY')),
  CONSTRAINT `ck_daily_stat_source_time_zones_json_shape` CHECK (JSON_TYPE(`source_time_zones_json`) IN ('ARRAY')),
  CONSTRAINT `ck_daily_stat_source_time_zones_json_schema` CHECK (JSON_SCHEMA_VALID('{"type":"array","uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":64}}',`source_time_zones_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='可重建每日统计；P0';

-- 40. weekly_review / 每周回顾快照 / P1
-- 保存明确覆盖范围和证据的回顾，不把语言推断当事实。
CREATE TABLE `weekly_review` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `week_start_date` DATE NOT NULL COMMENT '回顾起始业务日',
  `week_end_date` DATE NOT NULL COMMENT '起始后 6 日',
  `review_time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '生成时区',
  `revision_no` INT UNSIGNED NOT NULL COMMENT '该周回顾修订，旧修订保留',
  `status` VARCHAR(16) NOT NULL DEFAULT 'GENERATING' COMMENT 'GENERATING / READY / STALE / FAILED / ARCHIVED',
  `metric_version` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '统计口径版本',
  `data_as_of_seq` BIGINT UNSIGNED NOT NULL COMMENT '所基于个人数据序列',
  `dataset_epoch` BIGINT UNSIGNED NOT NULL COMMENT '数据代际',
  `coverage_json` JSON NOT NULL COMMENT '7 天各类型覆盖，未记录与零行为区分',
  `summary_cipher` MEDIUMBLOB NULL COMMENT '人工可读回顾文字密文',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  `suggestions_json` JSON NULL COMMENT '加密 envelope 的可选行为建议，不能自动应用目标',
  `ai_run_id` BIGINT UNSIGNED NULL COMMENT 'AI 生成链路',
  `generated_at` DATETIME(3) NULL COMMENT '生成完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_weekly_review_user_id_week_start_date_metric_version_654da5b5` (`user_id`, `week_start_date`, `metric_version`, `revision_no`),
  UNIQUE KEY `uq_weekly_review_user_id_id` (`user_id`, `id`),
  KEY `ix_weekly_review_user_id_week_start_date_revision_no` (`user_id`, `week_start_date`, `revision_no`),
  KEY `ix_weekly_review_user_id_ai_run_id` (`user_id`, `ai_run_id`),
  CONSTRAINT `ck_weekly_review_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_weekly_review_revision_no_positive` CHECK (`revision_no` >= 1),
  CONSTRAINT `ck_weekly_review_status_enum` CHECK (CAST(`status` AS BINARY) IN ('GENERATING','READY','STALE','FAILED','ARCHIVED')),
  CONSTRAINT `ck_weekly_review_dataset_epoch_positive` CHECK (`dataset_epoch` >= 1),
  CONSTRAINT `ck_weekly_review_summary_cipher_key` CHECK (`summary_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_weekly_review_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`summary_cipher` IS NOT NULL)),
  CONSTRAINT `ck_weekly_review_week_range` CHECK (`week_end_date` = DATE_ADD(`week_start_date`, INTERVAL 6 DAY)),
  CONSTRAINT `ck_weekly_review_coverage_json_shape` CHECK (JSON_TYPE(`coverage_json`) IN ('OBJECT')),
  CONSTRAINT `ck_weekly_review_suggestions_json_shape` CHECK (JSON_TYPE(`suggestions_json`) IN ('OBJECT')),
  CONSTRAINT `ck_weekly_review_suggestions_json_encrypted_envelope` CHECK (JSON_SCHEMA_VALID('{"type":"object","required":["alg","keyRef","nonce","ciphertext"],"properties":{"alg":{"type":"string","minLength":1},"keyRef":{"type":"string","minLength":1},"nonce":{"type":"string","minLength":1},"ciphertext":{"type":"string","minLength":1}}}',`suggestions_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='每周回顾快照；P1';

-- 41. weekly_review_evidence / 周报结论证据 / P1
-- 一行只能指向一种证据，避免多列同时有值造成解释歧义。
CREATE TABLE `weekly_review_evidence` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `review_id` BIGINT UNSIGNED NOT NULL COMMENT '周报 FK',
  `claim_key` VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '结论稳定编号，如 activity.total',
  `evidence_type` VARCHAR(16) NOT NULL COMMENT 'DAILY_STAT / RECORD / TASK_EVENT',
  `daily_stat_id` BIGINT UNSIGNED NULL COMMENT '统计证据',
  `record_id` BIGINT UNSIGNED NULL COMMENT '原始生活记录证据',
  `task_event_id` BIGINT UNSIGNED NULL COMMENT '执行事件证据',
  `evidence_row_version` BIGINT UNSIGNED NULL COMMENT 'CM 证据的版本；CA 事件可空',
  `evidence_as_of_seq` BIGINT UNSIGNED NOT NULL COMMENT '读取证据时序列',
  `value_snapshot_json` JSON NOT NULL COMMENT '该结论所需的最小数字/日期快照，不含原始备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_weekly_review_evidence_review_id_claim_key_daily_stat_id` (`review_id`, `claim_key`, `daily_stat_id`),
  UNIQUE KEY `uq_weekly_review_evidence_review_id_claim_key_record_id` (`review_id`, `claim_key`, `record_id`),
  UNIQUE KEY `uq_weekly_review_evidence_review_id_claim_key_task_event_id` (`review_id`, `claim_key`, `task_event_id`),
  UNIQUE KEY `uq_weekly_review_evidence_user_id_id` (`user_id`, `id`),
  KEY `ix_weekly_review_evidence_review_id_claim_key_id` (`review_id`, `claim_key`, `id`),
  KEY `ix_weekly_review_evidence_user_id_review_id` (`user_id`, `review_id`),
  KEY `ix_weekly_review_evidence_user_id_daily_stat_id` (`user_id`, `daily_stat_id`),
  KEY `ix_weekly_review_evidence_user_id_record_id` (`user_id`, `record_id`),
  KEY `ix_weekly_review_evidence_user_id_task_event_id` (`user_id`, `task_event_id`),
  CONSTRAINT `ck_weekly_review_evidence_evidence_type_enum` CHECK (CAST(`evidence_type` AS BINARY) IN ('DAILY_STAT','RECORD','TASK_EVENT')),
  CONSTRAINT `ck_weekly_review_evidence_evidence_row_version_positive` CHECK (`evidence_row_version` >= 1),
  CONSTRAINT `ck_weekly_review_evidence_exactly_one` CHECK ((CAST(`evidence_type` AS BINARY)='DAILY_STAT' AND `daily_stat_id` IS NOT NULL AND `record_id` IS NULL AND `task_event_id` IS NULL) OR (CAST(`evidence_type` AS BINARY)='RECORD' AND `record_id` IS NOT NULL AND `daily_stat_id` IS NULL AND `task_event_id` IS NULL) OR (CAST(`evidence_type` AS BINARY)='TASK_EVENT' AND `task_event_id` IS NOT NULL AND `daily_stat_id` IS NULL AND `record_id` IS NULL)),
  CONSTRAINT `ck_weekly_review_evidence_value_snapshot_json_shape` CHECK (JSON_TYPE(`value_snapshot_json`) IN ('OBJECT'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='周报结论证据；P1';

-- 42. reminder_rule / 提醒意愿与规则 / P0
-- 首版存提醒意愿与能力提示，不等于手机系统已能实际投递。
CREATE TABLE `reminder_rule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `rule_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '提醒规则稳定 UUID',
  `task_id` BIGINT UNSIGNED NULL COMMENT '任务提醒指向实例；全局记录提醒为空',
  `rule_type` VARCHAR(20) NOT NULL COMMENT 'TASK_START / RECORD_CHECKIN',
  `channel` VARCHAR(16) NOT NULL COMMENT 'LOCAL_SYSTEM / IN_APP；按验证后的能力启用',
  `target_installation_id` BIGINT UNSIGNED NULL COMMENT '目标安装，空表示受支持的当前手机策略',
  `trigger_kind` VARCHAR(16) NOT NULL COMMENT 'OFFSET / DAILY_LOCAL',
  `offset_minutes` SMALLINT NULL COMMENT '任务开始前或准时分钟，-1440–0；首版不支持延后提醒',
  `local_fire_time` TIME NULL COMMENT '日常记录提醒本地时分',
  `time_zone` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '解释时间的 IANA 时区',
  `is_enabled` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '用户是否启用',
  `respect_quiet_hours` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否采用免打扰策略',
  `quiet_policy` VARCHAR(16) NOT NULL DEFAULT 'SUPPRESS' COMMENT 'SUPPRESS / NEXT_ALLOWED；须显式配置',
  `valid_from` DATE NOT NULL COMMENT '开始日期',
  `valid_until` DATE NULL COMMENT '截止日期',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_reminder_rule_user_id_rule_key` (`user_id`, `rule_key`),
  UNIQUE KEY `uq_reminder_rule_user_id_id` (`user_id`, `id`),
  KEY `ix_reminder_rule_user_id_is_enabled_id` (`user_id`, `is_enabled`, `id`),
  KEY `ix_reminder_rule_task_id_is_enabled_id` (`task_id`, `is_enabled`, `id`),
  KEY `ix_reminder_rule_user_id_task_id` (`user_id`, `task_id`),
  KEY `ix_reminder_rule_user_id_target_installation_id` (`user_id`, `target_installation_id`),
  CONSTRAINT `ck_reminder_rule_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_reminder_rule_rule_type_enum` CHECK (CAST(`rule_type` AS BINARY) IN ('TASK_START','RECORD_CHECKIN')),
  CONSTRAINT `ck_reminder_rule_channel_enum` CHECK (CAST(`channel` AS BINARY) IN ('LOCAL_SYSTEM','IN_APP')),
  CONSTRAINT `ck_reminder_rule_trigger_kind_enum` CHECK (CAST(`trigger_kind` AS BINARY) IN ('OFFSET','DAILY_LOCAL')),
  CONSTRAINT `ck_reminder_rule_local_fire_time_time` CHECK (`local_fire_time` >= '00:00:00' AND `local_fire_time` < '24:00:00' AND MOD(TIME_TO_SEC(`local_fire_time`),60)=0),
  CONSTRAINT `ck_reminder_rule_is_enabled_range` CHECK (`is_enabled` BETWEEN 0 AND 1),
  CONSTRAINT `ck_reminder_rule_respect_quiet_hours_range` CHECK (`respect_quiet_hours` BETWEEN 0 AND 1),
  CONSTRAINT `ck_reminder_rule_quiet_policy_enum` CHECK (CAST(`quiet_policy` AS BINARY) IN ('SUPPRESS','NEXT_ALLOWED')),
  CONSTRAINT `ck_reminder_rule_offset_minutes_range` CHECK (`offset_minutes` BETWEEN -1440 AND 0),
  CONSTRAINT `ck_reminder_rule_valid_date_range` CHECK (`valid_until` IS NULL OR `valid_until` >= `valid_from`),
  CONSTRAINT `ck_reminder_rule_trigger_fields` CHECK ((CAST(`trigger_kind` AS BINARY)='OFFSET' AND CAST(`rule_type` AS BINARY)='TASK_START' AND `task_id` IS NOT NULL AND `offset_minutes` IS NOT NULL AND `local_fire_time` IS NULL) OR (CAST(`trigger_kind` AS BINARY)='DAILY_LOCAL' AND CAST(`rule_type` AS BINARY)='RECORD_CHECKIN' AND `task_id` IS NULL AND `local_fire_time` IS NOT NULL AND `offset_minutes` IS NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='提醒意愿与规则；P0';

-- 43. reminder_delivery / 一次系统提醒调度/投递尝试 / P1
-- 区分注册成功、实际投递与无法观测，保留可撤销句柄。
CREATE TABLE `reminder_delivery` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `rule_id` BIGINT UNSIGNED NOT NULL COMMENT '提醒规则',
  `task_id` BIGINT UNSIGNED NULL COMMENT '提醒所对应任务',
  `task_row_version` BIGINT UNSIGNED NULL COMMENT '注册时任务版本',
  `installation_id` BIGINT UNSIGNED NOT NULL COMMENT '目标设备',
  `channel` VARCHAR(16) NOT NULL COMMENT 'LOCAL_SYSTEM / IN_APP',
  `fire_at` DATETIME(3) NOT NULL COMMENT '计算后的 UTC 触发时间',
  `dedup_key` BINARY(32) NOT NULL COMMENT 'taskId+taskVersion+ruleVersion+channel+installation+fireAt 的规范摘要',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / REGISTERED / DELIVERED / UNKNOWN / FAILED / CANCEL_REQUESTED / CANCELLED / EXPIRED',
  `system_handle_cipher` VARBINARY(2048) NULL COMMENT '平台返回的撤销句柄密文，不在日志泄露',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  `registered_at` DATETIME(3) NULL COMMENT '系统确认注册，不代表已经展示',
  `delivered_at` DATETIME(3) NULL COMMENT '仅有可信系统回调时填写',
  `cancelled_at` DATETIME(3) NULL COMMENT '撤销确认时间',
  `attempt_count` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '有限重试次数',
  `last_error_code` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '权限/数量限制/能力不支持等结构码',
  `expires_at` DATETIME(3) NOT NULL COMMENT '过期后禁止补投陈旧任务提醒',
  `captured_dataset_epoch` BIGINT UNSIGNED NOT NULL COMMENT '调度时健康数据代际',
  `captured_consent_version` BIGINT UNSIGNED NOT NULL COMMENT '调度时通知授权代际',
  `rule_row_version` BIGINT UNSIGNED NOT NULL COMMENT '本次调度采用的提醒规则版本，参与 dedup_key',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_reminder_delivery_user_id_dedup_key` (`user_id`, `dedup_key`),
  UNIQUE KEY `uq_reminder_delivery_user_id_id` (`user_id`, `id`),
  KEY `ix_reminder_delivery_status_fire_at_id` (`status`, `fire_at`, `id`),
  KEY `ix_reminder_delivery_installation_id_status_fire_at_id` (`installation_id`, `status`, `fire_at`, `id`),
  KEY `ix_reminder_delivery_task_id_task_row_version_id` (`task_id`, `task_row_version`, `id`),
  KEY `ix_reminder_delivery_user_id_rule_id` (`user_id`, `rule_id`),
  KEY `ix_reminder_delivery_user_id_task_id` (`user_id`, `task_id`),
  KEY `ix_reminder_delivery_user_id_installation_id` (`user_id`, `installation_id`),
  CONSTRAINT `ck_reminder_delivery_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_reminder_delivery_task_row_version_positive` CHECK (`task_row_version` >= 1),
  CONSTRAINT `ck_reminder_delivery_channel_enum` CHECK (CAST(`channel` AS BINARY) IN ('LOCAL_SYSTEM','IN_APP')),
  CONSTRAINT `ck_reminder_delivery_status_enum` CHECK (CAST(`status` AS BINARY) IN ('PENDING','REGISTERED','DELIVERED','UNKNOWN','FAILED','CANCEL_REQUESTED','CANCELLED','EXPIRED')),
  CONSTRAINT `ck_reminder_delivery_captured_dataset_epoch_positive` CHECK (`captured_dataset_epoch` >= 1),
  CONSTRAINT `ck_reminder_delivery_captured_consent_version_positive` CHECK (`captured_consent_version` >= 1),
  CONSTRAINT `ck_reminder_delivery_rule_row_version_positive` CHECK (`rule_row_version` >= 1),
  CONSTRAINT `ck_reminder_delivery_system_handle_cipher_key` CHECK (`system_handle_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_reminder_delivery_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`system_handle_cipher` IS NOT NULL)),
  CONSTRAINT `ck_reminder_delivery_task_version_pair` CHECK ((`task_id` IS NULL) = (`task_row_version` IS NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='一次系统提醒调度/投递尝试；P1';

-- 44. notification_inbox / 应用内通知 / P1
-- 保存可读的系统进度/失败提示，不默认推送健康敏感原文。
CREATE TABLE `notification_inbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '收件账号',
  `notification_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等通知 UUID',
  `category` VARCHAR(24) NOT NULL COMMENT 'PLAN / SYNC / PRIVACY / REMINDER / REVIEW / SYSTEM',
  `title` VARCHAR(100) NOT NULL COMMENT '脱敏标题',
  `body_cipher` VARBINARY(8192) NULL COMMENT '确需个性化正文时加密',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '密钥引用',
  `target_type` VARCHAR(24) NULL COMMENT '白名单 PLAN / TASK / PRIVACY_REQUEST 等',
  `target_id` BIGINT UNSIGNED NULL COMMENT '目标 ID，服务验证归属与可见性',
  `priority` VARCHAR(12) NOT NULL DEFAULT 'NORMAL' COMMENT 'LOW / NORMAL / HIGH；非医疗紧急程度',
  `read_at` DATETIME(3) NULL COMMENT '首次查看时间',
  `expires_at` DATETIME(3) NULL COMMENT '不再展示截止',
  `dedup_key` VARCHAR(190) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同一事件同一用户只生成一条',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_notification_inbox_user_id_notification_key` (`user_id`, `notification_key`),
  UNIQUE KEY `uq_notification_inbox_user_id_dedup_key` (`user_id`, `dedup_key`),
  UNIQUE KEY `uq_notification_inbox_user_id_id` (`user_id`, `id`),
  KEY `ix_notification_inbox_user_id_read_at_created_at_id` (`user_id`, `read_at`, `created_at`, `id`),
  CONSTRAINT `ck_notification_inbox_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_notification_inbox_category_enum` CHECK (CAST(`category` AS BINARY) IN ('PLAN','SYNC','PRIVACY','REMINDER','REVIEW','SYSTEM')),
  CONSTRAINT `ck_notification_inbox_target_type_enum` CHECK (CAST(`target_type` AS BINARY) IN ('PLAN','TASK','PRIVACY_REQUEST','REVIEW')),
  CONSTRAINT `ck_notification_inbox_priority_enum` CHECK (CAST(`priority` AS BINARY) IN ('LOW','NORMAL','HIGH')),
  CONSTRAINT `ck_notification_inbox_body_cipher_key` CHECK (`body_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_notification_inbox_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`body_cipher` IS NOT NULL)),
  CONSTRAINT `ck_notification_inbox_target_pair` CHECK ((`target_type` IS NULL) = (`target_id` IS NULL))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='应用内通知；P1';

-- 45. outbox_event / 事务事件待办 / P0
-- 把业务已提交事件可靠交给统计/提醒/同步等作业；单库可用轮询消费。
CREATE TABLE `outbox_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NULL COMMENT '个人事件必填，全局内容维护可空',
  `event_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全局事件 UUID',
  `event_type` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'STAT_INVALIDATED / REMINDER_CANCEL / AI_RUN_QUEUED / PRIVACY_REQUESTED 等白名单',
  `aggregate_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属实体类型',
  `aggregate_id` BIGINT UNSIGNED NOT NULL COMMENT '实体 ID，删除事件不依赖被删实体 FK',
  `aggregate_row_version` BIGINT UNSIGNED NULL COMMENT '事件所见行版本',
  `payload_schema_version` SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '事件协议版本',
  `payload_json` JSON NOT NULL COMMENT '最小 ID/日期/动作；敏感内容仅加密 envelope，禁止完整对话',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / CLAIMED / DONE / RETRY / DEAD / CANCELLED',
  `available_at` DATETIME(3) NOT NULL COMMENT '下次可消费时间',
  `attempt_count` SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '尝试次数',
  `lease_owner` VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'worker 实例标识',
  `lease_until` DATETIME(3) NULL COMMENT '占用租约，崩溃后可重新领取',
  `processed_at` DATETIME(3) NULL COMMENT '完成时间',
  `last_error_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '脱敏错误码',
  `captured_dataset_epoch` BIGINT UNSIGNED NULL COMMENT '健康相关事件必填',
  `captured_consent_version` BIGINT UNSIGNED NULL COMMENT '依赖 AI/通知授权时必填',
  `consent_purpose` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '授权版本对应用途',
  `authorization_revision` BIGINT UNSIGNED NULL COMMENT '依赖外部连接时捕获连接授权修订',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_outbox_event_event_key` (`event_key`),
  UNIQUE KEY `uq_outbox_event_user_id_id` (`user_id`, `id`),
  KEY `ix_outbox_event_status_available_at_id` (`status`, `available_at`, `id`),
  KEY `ix_outbox_event_lease_until_id` (`lease_until`, `id`),
  KEY `ix_outbox_event_user_id_created_at_id` (`user_id`, `created_at`, `id`),
  CONSTRAINT `ck_outbox_event_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_outbox_event_aggregate_row_version_positive` CHECK (`aggregate_row_version` >= 1),
  CONSTRAINT `ck_outbox_event_payload_schema_version_positive` CHECK (`payload_schema_version` >= 1),
  CONSTRAINT `ck_outbox_event_status_enum` CHECK (CAST(`status` AS BINARY) IN ('PENDING','CLAIMED','DONE','RETRY','DEAD','CANCELLED')),
  CONSTRAINT `ck_outbox_event_captured_dataset_epoch_positive` CHECK (`captured_dataset_epoch` >= 1),
  CONSTRAINT `ck_outbox_event_captured_consent_version_positive` CHECK (`captured_consent_version` >= 1),
  CONSTRAINT `ck_outbox_event_authorization_revision_positive` CHECK (`authorization_revision` >= 1),
  CONSTRAINT `ck_outbox_event_claim_lease` CHECK (CAST(`status` AS BINARY)<>'CLAIMED' OR (`lease_owner` IS NOT NULL AND `lease_until` IS NOT NULL)),
  CONSTRAINT `ck_outbox_event_payload_json_shape` CHECK (JSON_TYPE(`payload_json`) IN ('OBJECT'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='事务事件待办；P0';

-- 46. file_object / 私有文件与保留策略 / P0
-- 统一附件、导出、头像和隐私清单；访问经鉴权短签名而非永久公网链接。
CREATE TABLE `file_object` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '文件所属账号',
  `file_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '公开引用 UUID',
  `purpose` VARCHAR(24) NOT NULL COMMENT 'AVATAR / RECORD_ATTACHMENT / EXPORT / PRIVACY_MANIFEST / COURSE_IMAGE / VOICE_TEMP / FEEDBACK_ATTACHMENT',
  `storage_provider` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'LOCAL_PRIVATE / S3_COMPATIBLE 等启用适配器',
  `bucket_code` VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '逻辑私有容器，不向客户端直接公开配置',
  `object_key` VARCHAR(500) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机存储键，无原文件名/手机号',
  `original_name_cipher` VARBINARY(2048) NULL COMMENT '确需展示时加密原文件名',
  `mime_type` VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '由服务器探测、白名单校验',
  `byte_size` BIGINT UNSIGNED NULL COMMENT '实际大小',
  `sha256` BINARY(32) NULL COMMENT '完整性校验，不做跨用户可观测秒传',
  `status` VARCHAR(16) NOT NULL DEFAULT 'UPLOADING' COMMENT 'UPLOADING / SCANNING / CLEAN / REJECTED / DELETING / DELETED',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对象内容信封加密密钥引用',
  `upload_expires_at` DATETIME(3) NULL COMMENT '上传凭证截止',
  `completed_at` DATETIME(3) NULL COMMENT '服务器确认上传完整时间',
  `retention_until` DATETIME(3) NULL COMMENT '普通保留截止',
  `restricted_hold_until` DATETIME(3) NULL COMMENT '隐私清单/删除证明受限保留窗口，不能由普通文件清除删除',
  `deleted_from_storage_at` DATETIME(3) NULL COMMENT '对象真实清除确认',
  `upload_token_hash` BINARY(32) NULL COMMENT '上传完成一次性token的哈希，仅创建意图首次返回原token',
  `upload_token_consumed_at` DATETIME(3) NULL COMMENT '完成校验后一次性消费时间',
  `expected_byte_size` BIGINT UNSIGNED NOT NULL COMMENT '上传意图声明大小，服务生成文件时填实际大小',
  `expected_sha256` BINARY(32) NOT NULL COMMENT '声明/预期摘要，完成时必须与服务器实际摘要一致',
  `declared_mime_type` VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '意图声明类型，不作为实际安全类型证据',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_file_object_user_id_file_key` (`user_id`, `file_key`),
  UNIQUE KEY `uq_file_object_user_id_id` (`user_id`, `id`),
  UNIQUE KEY `uq_file_object_storage_provider_bucket_code_object_key` (`storage_provider`, `bucket_code`, `object_key`),
  UNIQUE KEY `uq_file_object_upload_token_hash` (`upload_token_hash`),
  KEY `ix_file_object_status_retention_until_id` (`status`, `retention_until`, `id`),
  KEY `ix_file_object_user_id_purpose_created_at_id` (`user_id`, `purpose`, `created_at`, `id`),
  CONSTRAINT `ck_file_object_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_file_object_purpose_enum` CHECK (CAST(`purpose` AS BINARY) IN ('AVATAR','RECORD_ATTACHMENT','EXPORT','PRIVACY_MANIFEST','COURSE_IMAGE','VOICE_TEMP','FEEDBACK_ATTACHMENT')),
  CONSTRAINT `ck_file_object_status_enum` CHECK (CAST(`status` AS BINARY) IN ('UPLOADING','SCANNING','CLEAN','REJECTED','DELETING','DELETED')),
  CONSTRAINT `ck_file_object_original_name_cipher_key` CHECK (`original_name_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_file_object_clean_metadata` CHECK (CAST(`status` AS BINARY)<>'CLEAN' OR (`byte_size` IS NOT NULL AND `sha256` IS NOT NULL AND `mime_type` IS NOT NULL AND `completed_at` IS NOT NULL)),
  CONSTRAINT `ck_file_object_expected_size` CHECK (`expected_byte_size` >= 1),
  CONSTRAINT `ck_file_object_actual_size` CHECK (`byte_size` IS NULL OR `byte_size` >= 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='私有文件与保留策略；P0';

-- 47. privacy_request / 导出、删除预览与后台工单 / P0
-- 将用户确认的冻结范围与执行工单保存在同一条，不重新跑变化中的过滤条件。
CREATE TABLE `privacy_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '请求账号；完成注销保留最小内部主体键',
  `request_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求 UUID',
  `request_type` VARCHAR(24) NOT NULL COMMENT 'EXPORT / DELETE_RECORDS / ERASE_HEALTH_DATA / DELETE_ACCOUNT',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PREVIEW_READY' COMMENT 'PREVIEW_READY / QUEUED / PROCESSING / COMPLETED / PARTIAL_FAILED / FAILED / CANCELLED / EXPIRED',
  `scope_filter_json` JSON NOT NULL COMMENT '脱敏选择条件和模块白名单',
  `scope_manifest_file_id` BIGINT UNSIGNED NOT NULL COMMENT '冻结目标清单文件，导出也冻结范围',
  `scope_digest` BINARY(32) NOT NULL COMMENT '规范化目标清单摘要，确认时核对',
  `scope_summary_json` JSON NOT NULL COMMENT '按模块行数/附件数/影响概述，无原始内容',
  `captured_dataset_epoch` BIGINT UNSIGNED NOT NULL COMMENT '预览数据代际',
  `preview_data_seq` BIGINT UNSIGNED NOT NULL COMMENT '预览时已提交高水位；全量擦除确认须相等',
  `preview_expires_at` DATETIME(3) NOT NULL COMMENT '建议 15 分钟',
  `confirmed_at` DATETIME(3) NULL COMMENT '明确确认时间',
  `confirmation_operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '确认动作幂等 UUID',
  `started_at` DATETIME(3) NULL COMMENT '执行开始',
  `completed_at` DATETIME(3) NULL COMMENT '完成时间',
  `progress_json` JSON NOT NULL COMMENT '分模块批次检查点/成功失败计数，不能靠显示百分比判成功',
  `result_file_id` BIGINT UNSIGNED NULL COMMENT '导出文件，同用户用途 EXPORT',
  `receipt_token_hash` BINARY(32) NULL COMMENT '注销后仅查询工单状态的高熵回执 token 哈希',
  `receipt_expires_at` DATETIME(3) NULL COMMENT '回执失效时间',
  `error_code` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '脱敏失败码',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_privacy_request_user_id_request_key` (`user_id`, `request_key`),
  UNIQUE KEY `uq_privacy_request_user_id_confirmation_operation_id` (`user_id`, `confirmation_operation_id`),
  UNIQUE KEY `uq_privacy_request_receipt_token_hash` (`receipt_token_hash`),
  UNIQUE KEY `uq_privacy_request_user_id_id` (`user_id`, `id`),
  KEY `ix_privacy_request_status_created_at_id` (`status`, `created_at`, `id`),
  KEY `ix_privacy_request_user_id_scope_manifest_file_id` (`user_id`, `scope_manifest_file_id`),
  KEY `ix_privacy_request_user_id_result_file_id` (`user_id`, `result_file_id`),
  CONSTRAINT `ck_privacy_request_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_privacy_request_request_type_enum` CHECK (CAST(`request_type` AS BINARY) IN ('EXPORT','DELETE_RECORDS','ERASE_HEALTH_DATA','DELETE_ACCOUNT')),
  CONSTRAINT `ck_privacy_request_status_enum` CHECK (CAST(`status` AS BINARY) IN ('PREVIEW_READY','QUEUED','PROCESSING','COMPLETED','PARTIAL_FAILED','FAILED','CANCELLED','EXPIRED')),
  CONSTRAINT `ck_privacy_request_captured_dataset_epoch_positive` CHECK (`captured_dataset_epoch` >= 1),
  CONSTRAINT `ck_privacy_request_scope_filter_json_shape` CHECK (JSON_TYPE(`scope_filter_json`) IN ('OBJECT')),
  CONSTRAINT `ck_privacy_request_scope_summary_json_shape` CHECK (JSON_TYPE(`scope_summary_json`) IN ('OBJECT')),
  CONSTRAINT `ck_privacy_request_progress_json_shape` CHECK (JSON_TYPE(`progress_json`) IN ('OBJECT'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='导出、删除预览与后台工单；P0';

-- 48. audit_log / 最小安全审计 / P0
-- 记录谁对什么做了哪类敏感动作，禁止复制健康原文、密码和模型请求。
CREATE TABLE `audit_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '操作账号，系统作业可空',
  `subject_user_id` BIGINT UNSIGNED NULL COMMENT '受影响账号，后台内容操作可空',
  `actor_type` VARCHAR(16) NOT NULL COMMENT 'USER / ADMIN / WORKER / ANONYMOUS',
  `action_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'LOGIN_FAILURE / DATA_EXPORT / CONSENT_REVOKE / CONTENT_PUBLISH 等',
  `resource_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '资源白名单',
  `resource_id` BIGINT UNSIGNED NULL COMMENT '最小内部 ID，不做弱权限查询入口',
  `request_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务请求追踪 UUID',
  `operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '业务幂等编号',
  `outcome` VARCHAR(12) NOT NULL COMMENT 'SUCCESS / DENIED / FAILED',
  `reason_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '规则/错误编号',
  `metadata_json` JSON NOT NULL COMMENT '只允许字段名、版本、计数和安全码，不含内容原文',
  `ip_hash` BINARY(32) NULL COMMENT '短保留域分离 HMAC',
  `retention_until` DATETIME(3) NOT NULL COMMENT '按事件类型配置保留期',
  PRIMARY KEY (`id`),
  KEY `ix_audit_log_subject_user_id_created_at_id` (`subject_user_id`, `created_at`, `id`),
  KEY `ix_audit_log_actor_user_id_created_at_id` (`actor_user_id`, `created_at`, `id`),
  KEY `ix_audit_log_action_code_created_at_id` (`action_code`, `created_at`, `id`),
  KEY `ix_audit_log_retention_until_id` (`retention_until`, `id`),
  CONSTRAINT `ck_audit_log_actor_type_enum` CHECK (CAST(`actor_type` AS BINARY) IN ('USER','ADMIN','WORKER','ANONYMOUS')),
  CONSTRAINT `ck_audit_log_outcome_enum` CHECK (CAST(`outcome` AS BINARY) IN ('SUCCESS','DENIED','FAILED')),
  CONSTRAINT `ck_audit_log_metadata_json_shape` CHECK (JSON_TYPE(`metadata_json`) IN ('OBJECT'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='最小安全审计；P0';

-- 49. feedback_ticket / 用户反馈工单 / P1
-- 支持功能问题和同步异常反馈，不收集不必要病史。
CREATE TABLE `feedback_ticket` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '提交人',
  `ticket_no` CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '公开工单编号',
  `category` VARCHAR(20) NOT NULL COMMENT 'BUG / SUGGESTION / SYNC / PRIVACY / AI_QUALITY',
  `subject` VARCHAR(120) NOT NULL COMMENT '提醒用户避免填敏感原文的标题',
  `description_cipher` MEDIUMBLOB NOT NULL COMMENT '问题描述密文',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '密钥引用',
  `status` VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / IN_PROGRESS / WAITING_USER / RESOLVED / CLOSED',
  `installation_id` BIGINT UNSIGNED NULL COMMENT '经用户确认附带的安装信息',
  `diagnostic_reference` VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '脱敏 requestId，不自动附完整日志',
  `assigned_to_user_id` BIGINT UNSIGNED NULL COMMENT '处理人员账号，需工单权限',
  `resolution_cipher` MEDIUMBLOB NULL COMMENT '处理结果密文，不通过此表给医疗建议',
  `resolved_at` DATETIME(3) NULL COMMENT '解决时间',
  `closed_at` DATETIME(3) NULL COMMENT '关闭时间',
  `attachment_file_ids_json` JSON NOT NULL COMMENT '最多3个同账号CLEAN/FEEDBACK_ATTACHMENT私有文件ID字符串，允许[]',
  `contact_cipher` VARBINARY(2048) NULL COMMENT '用户自愿补充的联系信息密文，不自动复制账号邮箱',
  `diagnostics_json` JSON NULL COMMENT '经用户确认的脱敏版本/requestId/错误码白名单，不接收整份日志',
  `replied_at` DATETIME(3) NULL COMMENT '最近一次可见回复时间，与resolved_at不同',
  `internal_note_cipher` VARBINARY(8192) NULL COMMENT '后台受限处理备注密文，不返回用户接口',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_feedback_ticket_ticket_no` (`ticket_no`),
  UNIQUE KEY `uq_feedback_ticket_user_id_id` (`user_id`, `id`),
  KEY `ix_feedback_ticket_user_id_status_created_at_id` (`user_id`, `status`, `created_at`, `id`),
  KEY `ix_feedback_ticket_status_assigned_to_user_id_created_at_id` (`status`, `assigned_to_user_id`, `created_at`, `id`),
  KEY `ix_feedback_ticket_user_id_installation_id` (`user_id`, `installation_id`),
  KEY `ix_feedback_ticket_assigned_to_user_id` (`assigned_to_user_id`),
  CONSTRAINT `ck_feedback_ticket_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_feedback_ticket_category_enum` CHECK (CAST(`category` AS BINARY) IN ('BUG','SUGGESTION','SYNC','PRIVACY','AI_QUALITY')),
  CONSTRAINT `ck_feedback_ticket_status_enum` CHECK (CAST(`status` AS BINARY) IN ('OPEN','IN_PROGRESS','WAITING_USER','RESOLVED','CLOSED')),
  CONSTRAINT `ck_feedback_ticket_description_cipher_key` CHECK (`description_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_feedback_ticket_resolution_cipher_key` CHECK (`resolution_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_feedback_ticket_contact_cipher_key` CHECK (`contact_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_feedback_ticket_internal_note_cipher_key` CHECK (`internal_note_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_feedback_ticket_attachment_file_ids_json_shape` CHECK (JSON_TYPE(`attachment_file_ids_json`) IN ('ARRAY')),
  CONSTRAINT `ck_feedback_ticket_diagnostics_json_shape` CHECK (JSON_TYPE(`diagnostics_json`) IN ('OBJECT')),
  CONSTRAINT `ck_feedback_ticket_attachment_file_ids_json_schema` CHECK (JSON_SCHEMA_VALID('{"type":"array","maxItems":3,"uniqueItems":true,"items":{"type":"string","pattern":"^[1-9][0-9]{0,19}$"}}',`attachment_file_ids_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户反馈工单；P1';

-- 50. app_configuration / 可审计配置 / P0
-- 维护功能开关、限制、文本版本和保留参数，不在数据库存模型密钥。
CREATE TABLE `app_configuration` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `config_key` VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定配置代码',
  `environment` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'DEV / TEST / PROD',
  `scope` VARCHAR(16) NOT NULL DEFAULT 'SERVER' COMMENT 'PUBLIC / SERVER，PUBLIC 才允许返回手机',
  `value_type` VARCHAR(12) NOT NULL COMMENT 'BOOLEAN / NUMBER / STRING / OBJECT',
  `value_json` JSON NOT NULL COMMENT '按 schema 校验的值，禁止密钥/token/连接密码',
  `schema_json` JSON NOT NULL COMMENT '配置范围和结构约束',
  `description` VARCHAR(500) NOT NULL COMMENT '对用户/运维的用途说明',
  `effective_at` DATETIME(3) NOT NULL COMMENT '生效时点',
  `changed_by_user_id` BIGINT UNSIGNED NULL COMMENT '配置修改人',
  `change_reason` VARCHAR(500) NOT NULL COMMENT '变更原因',
  `is_enabled` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '禁用后回退到受控缺省值',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_app_configuration_environment_config_key` (`environment`, `config_key`),
  KEY `ix_app_configuration_environment_scope_is_enabled_id` (`environment`, `scope`, `is_enabled`, `id`),
  KEY `ix_app_configuration_changed_by_user_id` (`changed_by_user_id`),
  CONSTRAINT `ck_app_configuration_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_app_configuration_environment_enum` CHECK (CAST(`environment` AS BINARY) IN ('DEV','TEST','PROD')),
  CONSTRAINT `ck_app_configuration_scope_enum` CHECK (CAST(`scope` AS BINARY) IN ('PUBLIC','SERVER')),
  CONSTRAINT `ck_app_configuration_value_type_enum` CHECK (CAST(`value_type` AS BINARY) IN ('BOOLEAN','NUMBER','STRING','OBJECT')),
  CONSTRAINT `ck_app_configuration_is_enabled_range` CHECK (`is_enabled` BETWEEN 0 AND 1),
  CONSTRAINT `ck_app_configuration_schema_json_shape` CHECK (JSON_TYPE(`schema_json`) IN ('OBJECT')),
  CONSTRAINT `ck_app_configuration_value_json_shape` CHECK (JSON_TYPE(`value_json`) IN ('OBJECT')),
  CONSTRAINT `ck_app_configuration_value_wrapper` CHECK ((CAST(`value_type` AS BINARY)='BOOLEAN' AND JSON_SCHEMA_VALID('{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}',`value_json`)=1) OR (CAST(`value_type` AS BINARY)='NUMBER' AND JSON_SCHEMA_VALID('{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"number"}},"additionalProperties":false}',`value_json`)=1) OR (CAST(`value_type` AS BINARY)='STRING' AND JSON_SCHEMA_VALID('{"type":"object","required":["stringValue"],"properties":{"stringValue":{"type":"string","minLength":1,"maxLength":2000}},"additionalProperties":false}',`value_json`)=1) OR (CAST(`value_type` AS BINARY)='OBJECT' AND JSON_SCHEMA_VALID('{"anyOf":[{"type":"object","required":["featureCode","enabled","allowedPlatforms"],"properties":{"featureCode":{"type":"string","minLength":1,"maxLength":64},"enabled":{"type":"boolean"},"allowedPlatforms":{"type":"array","maxItems":4,"uniqueItems":true,"items":{"type":"string","enum":["ANDROID","HARMONYOS","IOS","WEB"]}},"minAppVersion":{"type":["string","null"],"maxLength":32},"rolloutPercent":{"type":"integer","minimum":0,"maximum":100}},"additionalProperties":false},{"type":"object","required":["items"],"properties":{"items":{"type":"array","maxItems":100,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":100}}},"additionalProperties":false}]}',`value_json`)=1))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='可审计配置；P0';

-- 51. user_sync_clock / 每用户已提交同步时钟 / P0
-- 业务事务写入的统一串行入口，避免全局自增 ID 漏同步。
CREATE TABLE `user_sync_clock` (
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号 FK，注册时创建且健康擦除不删除',
  `committed_seq` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已经提交的最大该用户变更序列',
  `retained_from_seq` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '当前还能拉到的最小事件序号；无事件为 committed_seq+1',
  `dataset_epoch` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '全量健康擦除后递增，永不回退/重置',
  `updated_at` DATETIME(3) NOT NULL COMMENT '最近成功提交时间',
  PRIMARY KEY (`user_id`),
  CONSTRAINT `ck_user_sync_clock_dataset_epoch_positive` CHECK (`dataset_epoch` >= 1),
  CONSTRAINT `ck_user_sync_clock_retention` CHECK (`retained_from_seq` >= 1 AND `retained_from_seq` <= `committed_seq` + 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='每用户已提交同步时钟；P0';

-- 52. sync_change / 用户增量与删除墓碑 / P0
-- 给离线客户端拉取的最小同步投影，不是通用业务审计。
CREATE TABLE `sync_change` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `change_seq` BIGINT UNSIGNED NOT NULL COMMENT '该用户严格按提交顺序分配',
  `dataset_epoch` BIGINT UNSIGNED NOT NULL COMMENT '所属数据代际',
  `entity_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同步白名单实体类型',
  `entity_id` BIGINT UNSIGNED NOT NULL COMMENT '对象 ID；删除墓碑不做实体物理 FK',
  `entity_row_version` BIGINT UNSIGNED NOT NULL COMMENT '客户端比较版本，关系集合跟父版本',
  `operation` VARCHAR(8) NOT NULL COMMENT 'UPSERT / DELETE',
  `changed_at` DATETIME(3) NOT NULL COMMENT '服务器事务时间，非游标',
  `payload_schema_version` SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '同步投影协议版本',
  `payload_json` JSON NOT NULL COMMENT '最小加密投影或 DTO；DELETE 仅 ID/版本/epoch，不保原内容',
  `operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '关联业务命令 UUID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sync_change_user_id_change_seq` (`user_id`, `change_seq`),
  UNIQUE KEY `uq_sync_change_user_id_id` (`user_id`, `id`),
  KEY `ix_sync_change_user_id_dataset_epoch_change_seq` (`user_id`, `dataset_epoch`, `change_seq`),
  KEY `ix_sync_change_user_id_entity_type_entity_id_change_seq` (`user_id`, `entity_type`, `entity_id`, `change_seq`),
  KEY `ix_sync_change_created_at_id` (`created_at`, `id`),
  CONSTRAINT `ck_sync_change_dataset_epoch_positive` CHECK (`dataset_epoch` >= 1),
  CONSTRAINT `ck_sync_change_entity_row_version_positive` CHECK (`entity_row_version` >= 1),
  CONSTRAINT `ck_sync_change_operation_enum` CHECK (CAST(`operation` AS BINARY) IN ('UPSERT','DELETE')),
  CONSTRAINT `ck_sync_change_payload_schema_version_positive` CHECK (`payload_schema_version` >= 1),
  CONSTRAINT `ck_sync_change_change_seq_positive` CHECK (`change_seq` >= 1),
  CONSTRAINT `ck_sync_change_payload_json_shape` CHECK (JSON_TYPE(`payload_json`) IN ('OBJECT'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户增量与删除墓碑；P0';

-- 53. sync_operation / 跨 REST/离线命令幂等回执 / P0
-- 相同业务命令重试只执行一次，不把原始敏感响应保存成回执。
CREATE TABLE `sync_operation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `client_operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'REST Idempotency-Key 与离线 operationId 同键',
  `operation_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'CREATE_RECORD / APPLY_PLAN_DRAFT / COMPLETE_TASK 等动作码',
  `request_hash` BINARY(32) NOT NULL COMMENT '规范化 operationCode+业务 payload+epoch 的 HMAC',
  `dataset_epoch` BIGINT UNSIGNED NOT NULL COMMENT '该命令的数据代际',
  `status` VARCHAR(16) NOT NULL COMMENT 'PROCESSING / SUCCEEDED；业务回滚时行也回滚',
  `resource_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '主要结果类型',
  `resource_id` BIGINT UNSIGNED NULL COMMENT '主要结果 ID',
  `resource_row_version` BIGINT UNSIGNED NULL COMMENT '结果版本',
  `last_change_seq` BIGINT UNSIGNED NULL COMMENT '该操作最后分配 seq',
  `result_json` JSON NULL COMMENT '仅资源 ID/版本/seq/状态等 MutationResult 最小回执',
  `completed_at` DATETIME(3) NULL COMMENT '同事务完成时间',
  `expires_at` DATETIME(3) NOT NULL COMMENT '建议保留 120 天',
  `installation_id` BIGINT UNSIGNED NULL COMMENT '命令发起安装',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sync_operation_user_id_client_operation_id` (`user_id`, `client_operation_id`),
  UNIQUE KEY `uq_sync_operation_user_id_id` (`user_id`, `id`),
  KEY `ix_sync_operation_expires_at_id` (`expires_at`, `id`),
  KEY `ix_sync_operation_user_id_installation_id` (`user_id`, `installation_id`),
  CONSTRAINT `ck_sync_operation_dataset_epoch_positive` CHECK (`dataset_epoch` >= 1),
  CONSTRAINT `ck_sync_operation_status_enum` CHECK (CAST(`status` AS BINARY) IN ('PROCESSING','SUCCEEDED')),
  CONSTRAINT `ck_sync_operation_resource_row_version_positive` CHECK (`resource_row_version` >= 1),
  CONSTRAINT `ck_sync_operation_last_change_seq_positive` CHECK (`last_change_seq` IS NULL OR `last_change_seq` >= 1),
  CONSTRAINT `ck_sync_operation_result_json_shape` CHECK (JSON_TYPE(`result_json`) IN ('OBJECT'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='跨 REST/离线命令幂等回执；P0';

-- 54. device_task_receipt / 手表任务动作回执 / P2
-- 保留设备声称的动作和接受结果，服务端 task 才是真相。
CREATE TABLE `device_task_receipt` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '设备绑定账号',
  `installation_id` BIGINT UNSIGNED NOT NULL COMMENT 'WATCH 安装 FK',
  `task_id` BIGINT UNSIGNED NOT NULL COMMENT '目标稳定任务',
  `device_operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '设备命令 UUID',
  `action` VARCHAR(16) NOT NULL COMMENT 'COMPLETE / SKIP，首阶段仅白名单',
  `expected_task_row_version` BIGINT UNSIGNED NOT NULL COMMENT '设备展示时任务版本',
  `captured_dataset_epoch` BIGINT UNSIGNED NOT NULL COMMENT '设备拿到任务的数据代际',
  `credential_revision` BIGINT UNSIGNED NOT NULL COMMENT '设备凭据代际',
  `device_occurred_at` DATETIME(3) NULL COMMENT '设备本地时钟换算，可信度有限',
  `server_received_at` DATETIME(3) NOT NULL COMMENT '服务器接收时刻',
  `status` VARCHAR(16) NOT NULL COMMENT 'ACCEPTED / REPLAYED / REJECTED',
  `rejection_code` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'VERSION_CONFLICT / CREDENTIAL_REVOKED / EPOCH_CHANGED 等',
  `applied_operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '统一业务幂等服务接受的 UUID',
  `result_task_row_version` BIGINT UNSIGNED NULL COMMENT '成功后的任务版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_device_task_receipt_installation_id_device_operation_id` (`installation_id`, `device_operation_id`),
  UNIQUE KEY `uq_device_task_receipt_user_id_id` (`user_id`, `id`),
  KEY `ix_device_task_receipt_user_id_server_received_at_id` (`user_id`, `server_received_at`, `id`),
  KEY `ix_device_task_receipt_user_id_installation_id` (`user_id`, `installation_id`),
  KEY `ix_device_task_receipt_user_id_task_id` (`user_id`, `task_id`),
  CONSTRAINT `ck_device_task_receipt_action_enum` CHECK (CAST(`action` AS BINARY) IN ('COMPLETE','SKIP')),
  CONSTRAINT `ck_device_task_receipt_expected_task_row_version_positive` CHECK (`expected_task_row_version` >= 1),
  CONSTRAINT `ck_device_task_receipt_captured_dataset_epoch_positive` CHECK (`captured_dataset_epoch` >= 1),
  CONSTRAINT `ck_device_task_receipt_credential_revision_positive` CHECK (`credential_revision` >= 1),
  CONSTRAINT `ck_device_task_receipt_status_enum` CHECK (CAST(`status` AS BINARY) IN ('ACCEPTED','REPLAYED','REJECTED')),
  CONSTRAINT `ck_device_task_receipt_result_task_row_version_positive` CHECK (`result_task_row_version` >= 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='手表任务动作回执；P2';

-- 55. external_data_connection / 外部数据授权连接 / P2
-- 独立于第三方登录，管理实际审批后的运动健康数据范围及撤权。
CREATE TABLE `external_data_connection` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `provider_code` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已验证提供方代码',
  `connection_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本次连接 UUID',
  `provider_subject_hash` BINARY(32) NULL COMMENT '提供方用户标识 HMAC',
  `status` VARCHAR(20) NOT NULL DEFAULT 'AUTHORIZING' COMMENT 'AUTHORIZING / ACTIVE / EXPIRED / REVOKED / ERROR',
  `state_hash` BINARY(32) NULL COMMENT '一次性 OAuth state 的 hash，回调事务消费',
  `authorization_expires_at` DATETIME(3) NULL COMMENT '本次 state/授权交互截止',
  `scopes_json` JSON NOT NULL COMMENT '真实核准 scope 白名单，不宣称能读未授权字段',
  `authorization_revision` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '授权、token 和撤权代际，旧任务必须核验',
  `consent_version` BIGINT UNSIGNED NOT NULL COMMENT 'EXTERNAL_HEALTH 同意版本',
  `access_token_cipher` MEDIUMBLOB NULL COMMENT '提供方令牌密文',
  `refresh_token_cipher` MEDIUMBLOB NULL COMMENT '如提供方允许刷新则保存密文',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '外部令牌密钥引用',
  `token_expires_at` DATETIME(3) NULL COMMENT '令牌截止',
  `authorized_at` DATETIME(3) NULL COMMENT '授权成功时间',
  `revoked_at` DATETIME(3) NULL COMMENT '本地停止读取时刻，不等待远程撤销成功',
  `last_sync_at` DATETIME(3) NULL COMMENT '最近成功导入时间',
  `provider_cursor_cipher` MEDIUMBLOB NULL COMMENT '必要的外部增量游标密文',
  `last_error_code` VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '脱敏错误',
  `active_provider` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' AND `deleted_at` IS NULL THEN `provider_code` ELSE NULL END) STORED COMMENT 'GENERATED ALWAYS AS (CASE WHEN status=''ACTIVE'' AND deleted_at IS NULL THEN provider_code ELSE NULL END) STORED',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_external_data_connection_user_id_connection_key` (`user_id`, `connection_key`),
  UNIQUE KEY `uq_external_data_connection_user_id_id` (`user_id`, `id`),
  UNIQUE KEY `uq_external_data_connection_state_hash` (`state_hash`),
  UNIQUE KEY `uq_external_data_connection_user_id_active_provider` (`user_id`, `active_provider`),
  KEY `ix_external_data_connection_user_id_provider_code_status_id` (`user_id`, `provider_code`, `status`, `id`),
  KEY `ix_external_data_connection_status_token_expires_at_id` (`status`, `token_expires_at`, `id`),
  CONSTRAINT `ck_external_data_connection_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_external_data_connection_status_enum` CHECK (CAST(`status` AS BINARY) IN ('AUTHORIZING','ACTIVE','EXPIRED','REVOKED','ERROR')),
  CONSTRAINT `ck_external_data_connection_authorization_revision_positive` CHECK (`authorization_revision` >= 1),
  CONSTRAINT `ck_external_data_connection_consent_version_positive` CHECK (`consent_version` >= 1),
  CONSTRAINT `ck_external_data_connection_access_token_cipher_key` CHECK (`access_token_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_external_data_connection_refresh_token_cipher_key` CHECK (`refresh_token_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_external_data_connection_provider_cursor_cipher_key` CHECK (`provider_cursor_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_external_data_connection_key_has_cipher` CHECK (`encryption_key_ref` IS NULL OR (`access_token_cipher` IS NOT NULL OR `refresh_token_cipher` IS NOT NULL OR `provider_cursor_cipher` IS NOT NULL)),
  CONSTRAINT `ck_external_data_connection_scopes_json_shape` CHECK (JSON_TYPE(`scopes_json`) IN ('ARRAY'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='外部数据授权连接；P2';

-- 56. import_batch / 导入任务与校验摘要 / P2
-- 管理外部健康数据、语音、课表图片导入；识别产物先待用户确认。
CREATE TABLE `import_batch` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '账号',
  `batch_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '导入 UUID',
  `kind` VARCHAR(24) NOT NULL COMMENT 'EXTERNAL_RECORDS / COURSE_IMAGE / VOICE_RECORD',
  `source_connection_id` BIGINT UNSIGNED NULL COMMENT '外部记录连接',
  `source_file_id` BIGINT UNSIGNED NULL COMMENT '图片/音频临时文件，同用途授权',
  `status` VARCHAR(20) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED / RUNNING / AWAITING_CONFIRM / SUCCEEDED / PARTIAL_FAILED / FAILED / CANCELLED',
  `captured_dataset_epoch` BIGINT UNSIGNED NOT NULL COMMENT '排队时数据代际',
  `captured_consent_version` BIGINT UNSIGNED NOT NULL COMMENT '对应授权用途版本',
  `authorization_revision` BIGINT UNSIGNED NULL COMMENT '外部连接捕获代际',
  `input_cursor_cipher` MEDIUMBLOB NULL COMMENT '此次读取起点密文',
  `result_cursor_cipher` MEDIUMBLOB NULL COMMENT '成功读取的终点，失败不冒进外部游标',
  `encryption_key_ref` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '游标/候选内容密钥引用',
  `candidate_payload_json` JSON NULL COMMENT '候选课程/记录的加密 envelope，未确认不写事实表',
  `total_count` INT UNSIGNED NULL COMMENT '解析候选数，未知 NULL',
  `imported_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '真实成功条数',
  `duplicate_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '外部稳定键去重数',
  `rejected_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '校验失败数',
  `started_at` DATETIME(3) NULL COMMENT '开始时间',
  `finished_at` DATETIME(3) NULL COMMENT '终态时间',
  `error_summary_json` JSON NULL COMMENT '行号/字段码/错误码，不保敏感行原文',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_import_batch_user_id_batch_key` (`user_id`, `batch_key`),
  UNIQUE KEY `uq_import_batch_user_id_id` (`user_id`, `id`),
  KEY `ix_import_batch_status_created_at_id` (`status`, `created_at`, `id`),
  KEY `ix_import_batch_user_id_source_connection_id` (`user_id`, `source_connection_id`),
  KEY `ix_import_batch_user_id_source_file_id` (`user_id`, `source_file_id`),
  CONSTRAINT `ck_import_batch_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_import_batch_kind_enum` CHECK (CAST(`kind` AS BINARY) IN ('EXTERNAL_RECORDS','COURSE_IMAGE','VOICE_RECORD')),
  CONSTRAINT `ck_import_batch_status_enum` CHECK (CAST(`status` AS BINARY) IN ('QUEUED','RUNNING','AWAITING_CONFIRM','SUCCEEDED','PARTIAL_FAILED','FAILED','CANCELLED')),
  CONSTRAINT `ck_import_batch_captured_dataset_epoch_positive` CHECK (`captured_dataset_epoch` >= 1),
  CONSTRAINT `ck_import_batch_captured_consent_version_positive` CHECK (`captured_consent_version` >= 1),
  CONSTRAINT `ck_import_batch_authorization_revision_positive` CHECK (`authorization_revision` >= 1),
  CONSTRAINT `ck_import_batch_input_cursor_cipher_key` CHECK (`input_cursor_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_import_batch_result_cursor_cipher_key` CHECK (`result_cursor_cipher` IS NULL OR `encryption_key_ref` IS NOT NULL),
  CONSTRAINT `ck_import_batch_counts` CHECK (`total_count` IS NULL OR `imported_count` + `duplicate_count` + `rejected_count` <= `total_count`),
  CONSTRAINT `ck_import_batch_source_kind` CHECK ((CAST(`kind` AS BINARY)='EXTERNAL_RECORDS' AND `source_connection_id` IS NOT NULL AND `authorization_revision` IS NOT NULL AND `source_file_id` IS NULL) OR (CAST(`kind` AS BINARY) IN ('COURSE_IMAGE','VOICE_RECORD') AND `source_file_id` IS NOT NULL AND `source_connection_id` IS NULL)),
  CONSTRAINT `ck_import_batch_candidate_payload_json_shape` CHECK (JSON_TYPE(`candidate_payload_json`) IN ('OBJECT')),
  CONSTRAINT `ck_import_batch_error_summary_json_shape` CHECK (JSON_TYPE(`error_summary_json`) IN ('OBJECT','ARRAY')),
  CONSTRAINT `ck_import_batch_candidate_payload_json_encrypted_envelope` CHECK (JSON_SCHEMA_VALID('{"type":"object","required":["alg","keyRef","nonce","ciphertext"],"properties":{"alg":{"type":"string","minLength":1},"keyRef":{"type":"string","minLength":1},"nonce":{"type":"string","minLength":1},"ciphertext":{"type":"string","minLength":1}}}',`candidate_payload_json`) = 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='导入任务与校验摘要；P2';

-- 57. sys_role / 后台角色 / P1
-- 角色只表达管理职责，默认不包含用户健康内容读取。
CREATE TABLE `sys_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `role_code` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'CONTENT_EDITOR / CONTENT_REVIEWER / SUPPORT / SYSTEM_ADMIN 等',
  `name` VARCHAR(80) NOT NULL COMMENT '展示名称',
  `description` VARCHAR(500) NOT NULL COMMENT '权限用途',
  `status` VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  `is_builtin` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '内建角色不能直接删名改义',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '后台排序',
  `permission_revision` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '权限集合变化递增',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sys_role_role_code` (`role_code`),
  KEY `ix_sys_role_status_sort_order_id` (`status`, `sort_order`, `id`),
  CONSTRAINT `ck_sys_role_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_sys_role_status_enum` CHECK (CAST(`status` AS BINARY) IN ('ACTIVE','DISABLED')),
  CONSTRAINT `ck_sys_role_is_builtin_range` CHECK (`is_builtin` BETWEEN 0 AND 1),
  CONSTRAINT `ck_sys_role_permission_revision_positive` CHECK (`permission_revision` >= 1)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='后台角色；P1';

-- 58. sys_permission / 后台权限目录 / P1
-- 由服务端路由和操作码映射，不能仅靠前端隐藏按钮。
CREATE TABLE `sys_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `permission_code` VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'knowledge:edit、knowledge:review、feedback:handle 等',
  `name` VARCHAR(100) NOT NULL COMMENT '权限说明',
  `resource_group` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'KNOWLEDGE / CONFIG / AUDIT / FEEDBACK / ROLE',
  `action` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'READ / CREATE / UPDATE / REVIEW / PUBLISH / ASSIGN',
  `description` VARCHAR(500) NOT NULL COMMENT '边界说明',
  `is_sensitive` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '敏感管理动作二次鉴权/审计',
  `status` VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sys_permission_permission_code` (`permission_code`),
  KEY `ix_sys_permission_resource_group_status_id` (`resource_group`, `status`, `id`),
  CONSTRAINT `ck_sys_permission_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_sys_permission_resource_group_enum` CHECK (CAST(`resource_group` AS BINARY) IN ('KNOWLEDGE','CONFIG','AUDIT','FEEDBACK','ROLE','USER','JOB')),
  CONSTRAINT `ck_sys_permission_action_enum` CHECK (CAST(`action` AS BINARY) IN ('READ','CREATE','UPDATE','REVIEW','PUBLISH','ASSIGN')),
  CONSTRAINT `ck_sys_permission_is_sensitive_range` CHECK (`is_sensitive` BETWEEN 0 AND 1),
  CONSTRAINT `ck_sys_permission_status_enum` CHECK (CAST(`status` AS BINARY) IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='后台权限目录；P1';

-- 59. user_role_assignment / 账号角色分配 / P1
-- 后台身份授权与普通账号状态、第三方身份分开。
CREATE TABLE `user_role_assignment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `updated_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC更新时间；不自动替代业务版本',
  `row_version` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁版本；每次合法变更递增',
  `deleted_at` DATETIME(3) NULL COMMENT '普通软删除UTC时间；隐私清除按工单执行',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '被授予账号',
  `role_id` BIGINT UNSIGNED NOT NULL COMMENT '角色',
  `status` VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / REVOKED',
  `valid_from` DATETIME(3) NOT NULL COMMENT '授权生效时间',
  `valid_until` DATETIME(3) NULL COMMENT '到期时间',
  `granted_by_user_id` BIGINT UNSIGNED NOT NULL COMMENT '授权操作者',
  `grant_reason` VARCHAR(500) NOT NULL COMMENT '授予原因',
  `revoked_by_user_id` BIGINT UNSIGNED NULL COMMENT '撤销操作者',
  `revoked_at` DATETIME(3) NULL COMMENT '撤销时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_role_assignment_user_id_role_id` (`user_id`, `role_id`),
  UNIQUE KEY `uq_user_role_assignment_user_id_id` (`user_id`, `id`),
  KEY `ix_user_role_assignment_user_id_status_valid_until_id` (`user_id`, `status`, `valid_until`, `id`),
  KEY `ix_user_role_assignment_role_id_status_id` (`role_id`, `status`, `id`),
  KEY `ix_user_role_assignment_granted_by_user_id` (`granted_by_user_id`),
  KEY `ix_user_role_assignment_revoked_by_user_id` (`revoked_by_user_id`),
  CONSTRAINT `ck_user_role_assignment_row_version_positive` CHECK (`row_version` >= 1),
  CONSTRAINT `ck_user_role_assignment_status_enum` CHECK (CAST(`status` AS BINARY) IN ('ACTIVE','REVOKED')),
  CONSTRAINT `ck_user_role_assignment_valid_date_range` CHECK (`valid_until` IS NULL OR `valid_until` >= `valid_from`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='账号角色分配；P1';

-- 60. role_permission / 角色权限关系 / P1
-- 明确角色权限集合，变更审计在 audit_log，不额外拆同义日志。
CREATE TABLE `role_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键；API使用十进制字符串',
  `created_at` DATETIME(3) NOT NULL COMMENT '服务端写入UTC创建时间',
  `role_id` BIGINT UNSIGNED NOT NULL COMMENT '角色 FK',
  `permission_id` BIGINT UNSIGNED NOT NULL COMMENT '权限 FK',
  `granted_by_user_id` BIGINT UNSIGNED NULL COMMENT '修改权限操作者；SQL落地修订：仅受控初始化种子允许NULL，真实后台授权仍必须真实操作者',
  `operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '批次操作 UUID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_role_permission_role_id_permission_id` (`role_id`, `permission_id`),
  KEY `ix_role_permission_permission_id_role_id` (`permission_id`, `role_id`),
  KEY `ix_role_permission_granted_by_user_id` (`granted_by_user_id`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='角色权限关系；P1';

-- 所有表和精确唯一父键已创建，最后统一建立外键（不关闭检查）。
ALTER TABLE `user_profile` ADD CONSTRAINT `fk_user_profile_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `user_preference` ADD CONSTRAINT `fk_user_preference_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `auth_identity` ADD CONSTRAINT `fk_auth_identity_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `auth_session` ADD CONSTRAINT `fk_auth_session_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `auth_verification` ADD CONSTRAINT `fk_auth_verification_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `client_installation` ADD CONSTRAINT `fk_client_installation_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `user_consent` ADD CONSTRAINT `fk_user_consent_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `user_consent_event` ADD CONSTRAINT `fk_user_consent_event_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `user_goal` ADD CONSTRAINT `fk_user_goal_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `availability_rule` ADD CONSTRAINT `fk_availability_rule_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `availability_exception` ADD CONSTRAINT `fk_availability_exception_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `health_record` ADD CONSTRAINT `fk_health_record_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `sleep_record_detail` ADD CONSTRAINT `fk_sleep_record_detail_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `activity_record_detail` ADD CONSTRAINT `fk_activity_record_detail_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `meal_record_detail` ADD CONSTRAINT `fk_meal_record_detail_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `energy_record_detail` ADD CONSTRAINT `fk_energy_record_detail_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `record_tag` ADD CONSTRAINT `fk_record_tag_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `record_tag_relation` ADD CONSTRAINT `fk_record_tag_relation_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `record_attachment` ADD CONSTRAINT `fk_record_attachment_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `plan` ADD CONSTRAINT `fk_plan_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `plan_version` ADD CONSTRAINT `fk_plan_version_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `task` ADD CONSTRAINT `fk_task_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `plan_version_task` ADD CONSTRAINT `fk_plan_version_task_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `plan_rule` ADD CONSTRAINT `fk_plan_rule_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `task_event` ADD CONSTRAINT `fk_task_event_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `task_record_link` ADD CONSTRAINT `fk_task_record_link_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `ai_conversation` ADD CONSTRAINT `fk_ai_conversation_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `ai_message` ADD CONSTRAINT `fk_ai_message_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `ai_run` ADD CONSTRAINT `fk_ai_run_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `ai_draft` ADD CONSTRAINT `fk_ai_draft_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `ai_draft_revision` ADD CONSTRAINT `fk_ai_draft_revision_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `ai_message_citation` ADD CONSTRAINT `fk_ai_message_citation_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `daily_stat` ADD CONSTRAINT `fk_daily_stat_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `weekly_review` ADD CONSTRAINT `fk_weekly_review_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `weekly_review_evidence` ADD CONSTRAINT `fk_weekly_review_evidence_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `reminder_rule` ADD CONSTRAINT `fk_reminder_rule_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `reminder_delivery` ADD CONSTRAINT `fk_reminder_delivery_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `notification_inbox` ADD CONSTRAINT `fk_notification_inbox_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `outbox_event` ADD CONSTRAINT `fk_outbox_event_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `file_object` ADD CONSTRAINT `fk_file_object_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `privacy_request` ADD CONSTRAINT `fk_privacy_request_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `feedback_ticket` ADD CONSTRAINT `fk_feedback_ticket_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `user_sync_clock` ADD CONSTRAINT `fk_user_sync_clock_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `sync_change` ADD CONSTRAINT `fk_sync_change_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `sync_operation` ADD CONSTRAINT `fk_sync_operation_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `device_task_receipt` ADD CONSTRAINT `fk_device_task_receipt_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `external_data_connection` ADD CONSTRAINT `fk_external_data_connection_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `import_batch` ADD CONSTRAINT `fk_import_batch_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `user_role_assignment` ADD CONSTRAINT `fk_user_role_assignment_user_id` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `user_profile` ADD CONSTRAINT `fk_user_profile_user_id_avatar_file_id` FOREIGN KEY (`user_id`, `avatar_file_id`) REFERENCES `file_object` (`user_id`, `id`);
ALTER TABLE `auth_session` ADD CONSTRAINT `fk_auth_session_user_id_installation_id` FOREIGN KEY (`user_id`, `installation_id`) REFERENCES `client_installation` (`user_id`, `id`);
ALTER TABLE `auth_session` ADD CONSTRAINT `fk_auth_session_user_id_family_id_client_audience_paren_576ca194` FOREIGN KEY (`user_id`, `family_id`, `client_audience`, `parent_session_id`) REFERENCES `auth_session` (`user_id`, `family_id`, `client_audience`, `id`);
ALTER TABLE `auth_session` ADD CONSTRAINT `fk_auth_session_user_id_family_id_client_audience_repla_aef1a192` FOREIGN KEY (`user_id`, `family_id`, `client_audience`, `replaced_by_session_id`) REFERENCES `auth_session` (`user_id`, `family_id`, `client_audience`, `id`);
ALTER TABLE `client_installation` ADD CONSTRAINT `fk_client_installation_user_id_parent_installation_id` FOREIGN KEY (`user_id`, `parent_installation_id`) REFERENCES `client_installation` (`user_id`, `id`);
ALTER TABLE `user_consent` ADD CONSTRAINT `fk_user_consent_user_id_installation_id` FOREIGN KEY (`user_id`, `installation_id`) REFERENCES `client_installation` (`user_id`, `id`);
ALTER TABLE `user_consent` ADD CONSTRAINT `fk_user_consent_user_id_id_last_event_id` FOREIGN KEY (`user_id`, `id`, `last_event_id`) REFERENCES `user_consent_event` (`user_id`, `consent_id`, `id`);
ALTER TABLE `user_consent_event` ADD CONSTRAINT `fk_user_consent_event_user_id_consent_id` FOREIGN KEY (`user_id`, `consent_id`) REFERENCES `user_consent` (`user_id`, `id`);
ALTER TABLE `user_consent_event` ADD CONSTRAINT `fk_user_consent_event_user_id_installation_id` FOREIGN KEY (`user_id`, `installation_id`) REFERENCES `client_installation` (`user_id`, `id`);
ALTER TABLE `user_goal` ADD CONSTRAINT `fk_user_goal_goal_definition_id` FOREIGN KEY (`goal_definition_id`) REFERENCES `goal_definition` (`id`);
ALTER TABLE `availability_rule` ADD CONSTRAINT `fk_availability_rule_user_id_source_batch_id` FOREIGN KEY (`user_id`, `source_batch_id`) REFERENCES `import_batch` (`user_id`, `id`);
ALTER TABLE `availability_exception` ADD CONSTRAINT `fk_availability_exception_user_id_source_batch_id` FOREIGN KEY (`user_id`, `source_batch_id`) REFERENCES `import_batch` (`user_id`, `id`);
ALTER TABLE `health_record` ADD CONSTRAINT `fk_health_record_user_id_source_connection_id` FOREIGN KEY (`user_id`, `source_connection_id`) REFERENCES `external_data_connection` (`user_id`, `id`);
ALTER TABLE `health_record` ADD CONSTRAINT `fk_health_record_user_id_ai_draft_id` FOREIGN KEY (`user_id`, `ai_draft_id`) REFERENCES `ai_draft` (`user_id`, `id`);
ALTER TABLE `health_record` ADD CONSTRAINT `fk_health_record_user_id_import_batch_id` FOREIGN KEY (`user_id`, `import_batch_id`) REFERENCES `import_batch` (`user_id`, `id`);
ALTER TABLE `sleep_record_detail` ADD CONSTRAINT `fk_sleep_record_detail_user_id_record_id_detail_type` FOREIGN KEY (`user_id`, `record_id`, `detail_type`) REFERENCES `health_record` (`user_id`, `id`, `record_type`);
ALTER TABLE `activity_record_detail` ADD CONSTRAINT `fk_activity_record_detail_user_id_record_id_detail_type` FOREIGN KEY (`user_id`, `record_id`, `detail_type`) REFERENCES `health_record` (`user_id`, `id`, `record_type`);
ALTER TABLE `meal_record_detail` ADD CONSTRAINT `fk_meal_record_detail_user_id_record_id_detail_type` FOREIGN KEY (`user_id`, `record_id`, `detail_type`) REFERENCES `health_record` (`user_id`, `id`, `record_type`);
ALTER TABLE `energy_record_detail` ADD CONSTRAINT `fk_energy_record_detail_user_id_record_id_detail_type` FOREIGN KEY (`user_id`, `record_id`, `detail_type`) REFERENCES `health_record` (`user_id`, `id`, `record_type`);
ALTER TABLE `record_tag_relation` ADD CONSTRAINT `fk_record_tag_relation_user_id_record_id` FOREIGN KEY (`user_id`, `record_id`) REFERENCES `health_record` (`user_id`, `id`);
ALTER TABLE `record_tag_relation` ADD CONSTRAINT `fk_record_tag_relation_user_id_tag_id` FOREIGN KEY (`user_id`, `tag_id`) REFERENCES `record_tag` (`user_id`, `id`);
ALTER TABLE `record_attachment` ADD CONSTRAINT `fk_record_attachment_user_id_record_id` FOREIGN KEY (`user_id`, `record_id`) REFERENCES `health_record` (`user_id`, `id`);
ALTER TABLE `record_attachment` ADD CONSTRAINT `fk_record_attachment_user_id_file_id` FOREIGN KEY (`user_id`, `file_id`) REFERENCES `file_object` (`user_id`, `id`);
ALTER TABLE `plan` ADD CONSTRAINT `fk_plan_user_id_origin_draft_id` FOREIGN KEY (`user_id`, `origin_draft_id`) REFERENCES `ai_draft` (`user_id`, `id`);
ALTER TABLE `plan` ADD CONSTRAINT `fk_plan_user_id_id_active_version_id` FOREIGN KEY (`user_id`, `id`, `active_version_id`) REFERENCES `plan_version` (`user_id`, `plan_id`, `id`);
ALTER TABLE `plan_version` ADD CONSTRAINT `fk_plan_version_user_id_plan_id` FOREIGN KEY (`user_id`, `plan_id`) REFERENCES `plan` (`user_id`, `id`);
ALTER TABLE `plan_version` ADD CONSTRAINT `fk_plan_version_user_id_plan_id_based_on_version_id` FOREIGN KEY (`user_id`, `plan_id`, `based_on_version_id`) REFERENCES `plan_version` (`user_id`, `plan_id`, `id`);
ALTER TABLE `plan_version` ADD CONSTRAINT `fk_plan_version_user_id_plan_id_restore_from_version_id` FOREIGN KEY (`user_id`, `plan_id`, `restore_from_version_id`) REFERENCES `plan_version` (`user_id`, `plan_id`, `id`);
ALTER TABLE `plan_version` ADD CONSTRAINT `fk_plan_version_user_id_source_draft_id` FOREIGN KEY (`user_id`, `source_draft_id`) REFERENCES `ai_draft` (`user_id`, `id`);
ALTER TABLE `task` ADD CONSTRAINT `fk_task_user_id_plan_id` FOREIGN KEY (`user_id`, `plan_id`) REFERENCES `plan` (`user_id`, `id`);
ALTER TABLE `task` ADD CONSTRAINT `fk_task_user_id_plan_id_current_plan_version_id` FOREIGN KEY (`user_id`, `plan_id`, `current_plan_version_id`) REFERENCES `plan_version` (`user_id`, `plan_id`, `id`);
ALTER TABLE `task` ADD CONSTRAINT `fk_task_user_id_plan_id_rule_id` FOREIGN KEY (`user_id`, `plan_id`, `rule_id`) REFERENCES `plan_rule` (`user_id`, `plan_id`, `id`);
ALTER TABLE `plan_version_task` ADD CONSTRAINT `fk_plan_version_task_user_id_plan_id_version_id` FOREIGN KEY (`user_id`, `plan_id`, `version_id`) REFERENCES `plan_version` (`user_id`, `plan_id`, `id`);
ALTER TABLE `plan_version_task` ADD CONSTRAINT `fk_plan_version_task_user_id_plan_id_task_id` FOREIGN KEY (`user_id`, `plan_id`, `task_id`) REFERENCES `task` (`user_id`, `plan_id`, `id`);
ALTER TABLE `plan_version_task` ADD CONSTRAINT `fk_plan_version_task_user_id_plan_id_rule_id` FOREIGN KEY (`user_id`, `plan_id`, `rule_id`) REFERENCES `plan_rule` (`user_id`, `plan_id`, `id`);
ALTER TABLE `plan_rule` ADD CONSTRAINT `fk_plan_rule_user_id_plan_id` FOREIGN KEY (`user_id`, `plan_id`) REFERENCES `plan` (`user_id`, `id`);
ALTER TABLE `task_event` ADD CONSTRAINT `fk_task_event_user_id_task_id` FOREIGN KEY (`user_id`, `task_id`) REFERENCES `task` (`user_id`, `id`);
ALTER TABLE `task_event` ADD CONSTRAINT `fk_task_event_user_id_plan_version_id_task_id_plan_vers_9a161c75` FOREIGN KEY (`user_id`, `plan_version_id`, `task_id`, `plan_version_task_id`) REFERENCES `plan_version_task` (`user_id`, `version_id`, `task_id`, `id`);
ALTER TABLE `task_record_link` ADD CONSTRAINT `fk_task_record_link_user_id_task_id` FOREIGN KEY (`user_id`, `task_id`) REFERENCES `task` (`user_id`, `id`);
ALTER TABLE `task_record_link` ADD CONSTRAINT `fk_task_record_link_user_id_record_id` FOREIGN KEY (`user_id`, `record_id`) REFERENCES `health_record` (`user_id`, `id`);
ALTER TABLE `ai_conversation` ADD CONSTRAINT `fk_ai_conversation_user_id_id_last_message_id` FOREIGN KEY (`user_id`, `id`, `last_message_id`) REFERENCES `ai_message` (`user_id`, `conversation_id`, `id`);
ALTER TABLE `ai_message` ADD CONSTRAINT `fk_ai_message_user_id_conversation_id` FOREIGN KEY (`user_id`, `conversation_id`) REFERENCES `ai_conversation` (`user_id`, `id`);
ALTER TABLE `ai_message` ADD CONSTRAINT `fk_ai_message_user_id_conversation_id_reply_to_message_id` FOREIGN KEY (`user_id`, `conversation_id`, `reply_to_message_id`) REFERENCES `ai_message` (`user_id`, `conversation_id`, `id`);
ALTER TABLE `ai_message` ADD CONSTRAINT `fk_ai_message_user_id_ai_run_id` FOREIGN KEY (`user_id`, `ai_run_id`) REFERENCES `ai_run` (`user_id`, `id`);
ALTER TABLE `ai_message` ADD CONSTRAINT `fk_ai_message_user_id_draft_id` FOREIGN KEY (`user_id`, `draft_id`) REFERENCES `ai_draft` (`user_id`, `id`);
ALTER TABLE `ai_run` ADD CONSTRAINT `fk_ai_run_user_id_conversation_id` FOREIGN KEY (`user_id`, `conversation_id`) REFERENCES `ai_conversation` (`user_id`, `id`);
ALTER TABLE `ai_run` ADD CONSTRAINT `fk_ai_run_user_id_input_message_id` FOREIGN KEY (`user_id`, `input_message_id`) REFERENCES `ai_message` (`user_id`, `id`);
ALTER TABLE `ai_draft` ADD CONSTRAINT `fk_ai_draft_user_id_source_run_id` FOREIGN KEY (`user_id`, `source_run_id`) REFERENCES `ai_run` (`user_id`, `id`);
ALTER TABLE `ai_draft` ADD CONSTRAINT `fk_ai_draft_user_id_conversation_id` FOREIGN KEY (`user_id`, `conversation_id`) REFERENCES `ai_conversation` (`user_id`, `id`);
ALTER TABLE `ai_draft` ADD CONSTRAINT `fk_ai_draft_user_id_base_plan_id` FOREIGN KEY (`user_id`, `base_plan_id`) REFERENCES `plan` (`user_id`, `id`);
ALTER TABLE `ai_draft` ADD CONSTRAINT `fk_ai_draft_user_id_base_plan_id_base_plan_version_id` FOREIGN KEY (`user_id`, `base_plan_id`, `base_plan_version_id`) REFERENCES `plan_version` (`user_id`, `plan_id`, `id`);
ALTER TABLE `ai_draft_revision` ADD CONSTRAINT `fk_ai_draft_revision_user_id_draft_id` FOREIGN KEY (`user_id`, `draft_id`) REFERENCES `ai_draft` (`user_id`, `id`);
ALTER TABLE `ai_message_citation` ADD CONSTRAINT `fk_ai_message_citation_user_id_message_id` FOREIGN KEY (`user_id`, `message_id`) REFERENCES `ai_message` (`user_id`, `id`);
ALTER TABLE `ai_message_citation` ADD CONSTRAINT `fk_ai_message_citation_article_version_id_knowledge_source_id` FOREIGN KEY (`article_version_id`, `knowledge_source_id`) REFERENCES `knowledge_version_source` (`article_version_id`, `knowledge_source_id`);
ALTER TABLE `knowledge_article` ADD CONSTRAINT `fk_knowledge_article_id_active_version_id` FOREIGN KEY (`id`, `active_version_id`) REFERENCES `knowledge_article_version` (`article_id`, `id`);
ALTER TABLE `knowledge_article_version` ADD CONSTRAINT `fk_knowledge_article_version_article_id` FOREIGN KEY (`article_id`) REFERENCES `knowledge_article` (`id`);
ALTER TABLE `knowledge_article_version` ADD CONSTRAINT `fk_knowledge_article_version_author_user_id` FOREIGN KEY (`author_user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `knowledge_article_version` ADD CONSTRAINT `fk_knowledge_article_version_reviewer_user_id` FOREIGN KEY (`reviewer_user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `knowledge_version_source` ADD CONSTRAINT `fk_knowledge_version_source_article_version_id` FOREIGN KEY (`article_version_id`) REFERENCES `knowledge_article_version` (`id`);
ALTER TABLE `knowledge_version_source` ADD CONSTRAINT `fk_knowledge_version_source_knowledge_source_id` FOREIGN KEY (`knowledge_source_id`) REFERENCES `knowledge_source` (`id`);
ALTER TABLE `weekly_review` ADD CONSTRAINT `fk_weekly_review_user_id_ai_run_id` FOREIGN KEY (`user_id`, `ai_run_id`) REFERENCES `ai_run` (`user_id`, `id`);
ALTER TABLE `weekly_review_evidence` ADD CONSTRAINT `fk_weekly_review_evidence_user_id_review_id` FOREIGN KEY (`user_id`, `review_id`) REFERENCES `weekly_review` (`user_id`, `id`);
ALTER TABLE `weekly_review_evidence` ADD CONSTRAINT `fk_weekly_review_evidence_user_id_daily_stat_id` FOREIGN KEY (`user_id`, `daily_stat_id`) REFERENCES `daily_stat` (`user_id`, `id`);
ALTER TABLE `weekly_review_evidence` ADD CONSTRAINT `fk_weekly_review_evidence_user_id_record_id` FOREIGN KEY (`user_id`, `record_id`) REFERENCES `health_record` (`user_id`, `id`);
ALTER TABLE `weekly_review_evidence` ADD CONSTRAINT `fk_weekly_review_evidence_user_id_task_event_id` FOREIGN KEY (`user_id`, `task_event_id`) REFERENCES `task_event` (`user_id`, `id`);
ALTER TABLE `reminder_rule` ADD CONSTRAINT `fk_reminder_rule_user_id_task_id` FOREIGN KEY (`user_id`, `task_id`) REFERENCES `task` (`user_id`, `id`);
ALTER TABLE `reminder_rule` ADD CONSTRAINT `fk_reminder_rule_user_id_target_installation_id` FOREIGN KEY (`user_id`, `target_installation_id`) REFERENCES `client_installation` (`user_id`, `id`);
ALTER TABLE `reminder_delivery` ADD CONSTRAINT `fk_reminder_delivery_user_id_rule_id` FOREIGN KEY (`user_id`, `rule_id`) REFERENCES `reminder_rule` (`user_id`, `id`);
ALTER TABLE `reminder_delivery` ADD CONSTRAINT `fk_reminder_delivery_user_id_task_id` FOREIGN KEY (`user_id`, `task_id`) REFERENCES `task` (`user_id`, `id`);
ALTER TABLE `reminder_delivery` ADD CONSTRAINT `fk_reminder_delivery_user_id_installation_id` FOREIGN KEY (`user_id`, `installation_id`) REFERENCES `client_installation` (`user_id`, `id`);
ALTER TABLE `privacy_request` ADD CONSTRAINT `fk_privacy_request_user_id_scope_manifest_file_id` FOREIGN KEY (`user_id`, `scope_manifest_file_id`) REFERENCES `file_object` (`user_id`, `id`);
ALTER TABLE `privacy_request` ADD CONSTRAINT `fk_privacy_request_user_id_result_file_id` FOREIGN KEY (`user_id`, `result_file_id`) REFERENCES `file_object` (`user_id`, `id`);
ALTER TABLE `feedback_ticket` ADD CONSTRAINT `fk_feedback_ticket_user_id_installation_id` FOREIGN KEY (`user_id`, `installation_id`) REFERENCES `client_installation` (`user_id`, `id`);
ALTER TABLE `feedback_ticket` ADD CONSTRAINT `fk_feedback_ticket_assigned_to_user_id` FOREIGN KEY (`assigned_to_user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `app_configuration` ADD CONSTRAINT `fk_app_configuration_changed_by_user_id` FOREIGN KEY (`changed_by_user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `sync_operation` ADD CONSTRAINT `fk_sync_operation_user_id_installation_id` FOREIGN KEY (`user_id`, `installation_id`) REFERENCES `client_installation` (`user_id`, `id`);
ALTER TABLE `device_task_receipt` ADD CONSTRAINT `fk_device_task_receipt_user_id_installation_id` FOREIGN KEY (`user_id`, `installation_id`) REFERENCES `client_installation` (`user_id`, `id`);
ALTER TABLE `device_task_receipt` ADD CONSTRAINT `fk_device_task_receipt_user_id_task_id` FOREIGN KEY (`user_id`, `task_id`) REFERENCES `task` (`user_id`, `id`);
ALTER TABLE `import_batch` ADD CONSTRAINT `fk_import_batch_user_id_source_connection_id` FOREIGN KEY (`user_id`, `source_connection_id`) REFERENCES `external_data_connection` (`user_id`, `id`);
ALTER TABLE `import_batch` ADD CONSTRAINT `fk_import_batch_user_id_source_file_id` FOREIGN KEY (`user_id`, `source_file_id`) REFERENCES `file_object` (`user_id`, `id`);
ALTER TABLE `user_role_assignment` ADD CONSTRAINT `fk_user_role_assignment_role_id` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`);
ALTER TABLE `user_role_assignment` ADD CONSTRAINT `fk_user_role_assignment_granted_by_user_id` FOREIGN KEY (`granted_by_user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `user_role_assignment` ADD CONSTRAINT `fk_user_role_assignment_revoked_by_user_id` FOREIGN KEY (`revoked_by_user_id`) REFERENCES `app_user` (`id`);
ALTER TABLE `role_permission` ADD CONSTRAINT `fk_role_permission_role_id` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`);
ALTER TABLE `role_permission` ADD CONSTRAINT `fk_role_permission_permission_id` FOREIGN KEY (`permission_id`) REFERENCES `sys_permission` (`id`);
ALTER TABLE `role_permission` ADD CONSTRAINT `fk_role_permission_granted_by_user_id` FOREIGN KEY (`granted_by_user_id`) REFERENCES `app_user` (`id`);


-- 知衡：必要初始化种子（由完整建库脚本在60表与约束之后执行）。
-- 仅用于全新空库；普通INSERT，唯一键冲突必须报错，不使用INSERT IGNORE/REPLACE。
-- 不创建app_user、默认管理员密码、业务健康记录或假知识资料。
-- 知识库为空，待人工核验真实资料/引用权利后，经正式版本与审核流程导入。
-- 落地修订：role_permission.granted_by_user_id允许NULL，仅表示受控迁移种子。
-- 正常后台授权仍必须真实操作者，服务层必须拒绝匿名授权；固定SYSTEM_ADMIN行作为并发守卫。
-- 默认配置为工程起点，不代表已发布隐私政策、实际设备能力或对外服务承诺。
SET @zh_seed_utc = UTC_TIMESTAMP(3);
START TRANSACTION;

INSERT INTO goal_definition
  (code,name,description,goal_category,parameter_schema_json,suggested_parameters_json,icon_code,sort_order,status,schema_version,created_at,updated_at,row_version,deleted_at)
VALUES
  ('REGULAR_SLEEP','建立规律作息','记录并安排自己的休息时间，目标由用户自行确认。','ROUTINE','{"type":"object","properties":{"targetValue":{"type":["number","null"],"minimum":1,"maximum":7},"unit":{"enum":["DAYS_PER_WEEK",null]}},"required":["targetValue","unit"],"additionalProperties":false,"oneOf":[{"properties":{"targetValue":{"type":"null"},"unit":{"type":"null"}}},{"properties":{"targetValue":{"type":"number","minimum":1,"maximum":7},"unit":{"enum":["DAYS_PER_WEEK"]}}}]}',NULL,NULL,10,'ACTIVE',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('DAILY_ACTIVITY','安排日常活动','在可用时间内安排用户选择的日常身体活动，不给出医疗运动处方。','ACTIVITY','{"type":"object","properties":{"targetValue":{"type":["number","null"],"minimum":1,"maximum":240},"unit":{"enum":["MINUTES",null]}},"required":["targetValue","unit"],"additionalProperties":false,"oneOf":[{"properties":{"targetValue":{"type":"null"},"unit":{"type":"null"}}},{"properties":{"targetValue":{"type":"number","minimum":1,"maximum":240},"unit":{"enum":["MINUTES"]}}}]}',NULL,NULL,20,'ACTIVE',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('LIFE_RECORD','保持生活记录','按用户选择的频率记录睡眠、活动、饮食习惯或主观精力。','RECORDING','{"type":"object","properties":{"targetValue":{"type":["number","null"],"minimum":1,"maximum":7},"unit":{"enum":["DAYS_PER_WEEK",null]}},"required":["targetValue","unit"],"additionalProperties":false,"oneOf":[{"properties":{"targetValue":{"type":"null"},"unit":{"type":"null"}}},{"properties":{"targetValue":{"type":"number","minimum":1,"maximum":7},"unit":{"enum":["DAYS_PER_WEEK"]}}}]}',NULL,NULL,30,'ACTIVE',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('MEAL_RECORD','记录饮食习惯','记录餐次与用户确认的饮食习惯，不推算精确热量或营养达标结论。','RECORDING','{"type":"object","properties":{"targetValue":{"type":["number","null"],"minimum":1,"maximum":21},"unit":{"enum":["TIMES_PER_WEEK",null]}},"required":["targetValue","unit"],"additionalProperties":false,"oneOf":[{"properties":{"targetValue":{"type":"null"},"unit":{"type":"null"}}},{"properties":{"targetValue":{"type":"number","minimum":1,"maximum":21},"unit":{"enum":["TIMES_PER_WEEK"]}}}]}',NULL,NULL,40,'ACTIVE',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('CUSTOM','自定义生活目标','自定义可观察的日常行为目标；不用于诊断、药量、治疗或疾病风险评分。','CUSTOM','{"type":"object","properties":{"targetValue":{"type":["number","null"],"minimum":0,"maximum":1000000},"unit":{"enum":["CUSTOM",null]}},"required":["targetValue","unit"],"additionalProperties":false,"oneOf":[{"properties":{"targetValue":{"type":"null"},"unit":{"type":"null"}}},{"properties":{"targetValue":{"type":"number","minimum":0,"maximum":1000000},"unit":{"enum":["CUSTOM"]}}}]}',NULL,NULL,50,'ACTIVE',1,@zh_seed_utc,@zh_seed_utc,1,NULL);

INSERT INTO sys_permission
  (permission_code,name,resource_group,action,description,is_sensitive,status,created_at,updated_at,row_version,deleted_at)
VALUES
  ('content.read','查看知识内容','KNOWLEDGE','READ','查看内容草稿、审核状态和公开来源，不包含用户对话。',0,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('content.edit','编辑知识内容','KNOWLEDGE','UPDATE','创建及修改未发布内容，提交审核；不能自我审核或直接发布。',0,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('content.review','审核知识内容','KNOWLEDGE','REVIEW','独立审核内容及来源，编辑人与审核人必须分离。',1,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('content.publish','发布知识内容','KNOWLEDGE','PUBLISH','仅发布审核通过版本或退役文章，不能跳过审核。',1,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('user.read','查看账号安全摘要','USER','READ','仅脱敏账号、状态与角色信息，不含健康记录和AI原文。',1,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('user.security','管理账号安全状态','USER','UPDATE','锁定或解锁账号、撤销会话；不得取消隐私删除栅栏。',1,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('rbac.read','查看后台角色权限','ROLE','READ','查看角色、权限目录和有效授权，不读取健康数据。',0,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('rbac.manage','管理后台角色授权','ROLE','ASSIGN','在操作者可授予范围内管理角色与权限，保护最后有效管理员。',1,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('config.read','查看非密钥配置','CONFIG','READ','查看配置和掩码状态，不返回密钥、令牌及连接密码。',0,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('config.manage','管理受控配置','CONFIG','UPDATE','按key schema修改配置；不支持任意SQL、路径或秘密值。',1,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('audit.read','查看最小审计记录','AUDIT','READ','查看操作码、资源ID和结果，不返回健康原文、邮箱明文或凭据。',1,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('job.read','查看后台作业摘要','JOB','READ','仅查看作业状态和错误码，不返回prompt、输入payload或外部令牌。',0,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('job.retry','重试允许的outbox作业','JOB','UPDATE','仅重试允许且epoch、授权仍有效的失败事件；不复活已删除数据。',1,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feedback.read','查看反馈工单','FEEDBACK','READ','只读取用户主动提交的反馈及必要附件，不连带开放其健康记录。',0,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feedback.manage','处理反馈工单','FEEDBACK','UPDATE','分配、回复和关闭反馈，变更留审计；不得默认采集健康原文。',0,'ACTIVE',@zh_seed_utc,@zh_seed_utc,1,NULL);

INSERT INTO sys_role
  (role_code,name,description,status,is_builtin,sort_order,permission_revision,created_at,updated_at,row_version,deleted_at)
VALUES
  ('CONTENT_EDITOR','内容编辑','编辑及提交内容，禁止审核自己的修改，不具备发布权限。','ACTIVE',1,10,1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('CONTENT_REVIEWER','内容审核','核对内容与来源；必须遵守编辑审核分离，不直接修改正文。','ACTIVE',1,20,1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('CONTENT_PUBLISHER','内容发布','发布审核通过版本或退役文章，不跳过审核条件。','ACTIVE',1,30,1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('SUPPORT','反馈支持','只处理用户主动反馈，不具备账号安全或健康原文通行权限。','ACTIVE',1,40,1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('OPERATIONS','作业运维','查看作业摘要及最小审计，按幂等与授权边界重试允许事件。','ACTIVE',1,50,1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('SYSTEM_ADMIN','系统管理员','管理账号安全、RBAC和配置；固定守卫行，不绕过健康数据归属。','ACTIVE',1,60,1,@zh_seed_utc,@zh_seed_utc,1,NULL);

-- 通过稳定code关联，不依赖自增ID；不创建任何user_role_assignment。
INSERT INTO role_permission (role_id,permission_id,granted_by_user_id,operation_id,created_at)
SELECT r.id,p.id,NULL,'c74f7e1b-63bb-4ee4-a586-1d2d72a98160',@zh_seed_utc
FROM (
  SELECT 'CONTENT_EDITOR' AS role_code,'content.read' AS permission_code
  UNION ALL SELECT 'CONTENT_EDITOR','content.edit'
  UNION ALL SELECT 'CONTENT_REVIEWER','content.read'
  UNION ALL SELECT 'CONTENT_REVIEWER','content.review'
  UNION ALL SELECT 'CONTENT_PUBLISHER','content.read'
  UNION ALL SELECT 'CONTENT_PUBLISHER','content.publish'
  UNION ALL SELECT 'SUPPORT','feedback.read'
  UNION ALL SELECT 'SUPPORT','feedback.manage'
  UNION ALL SELECT 'OPERATIONS','job.read'
  UNION ALL SELECT 'OPERATIONS','job.retry'
  UNION ALL SELECT 'OPERATIONS','audit.read'
  UNION ALL SELECT 'SYSTEM_ADMIN','user.read'
  UNION ALL SELECT 'SYSTEM_ADMIN','user.security'
  UNION ALL SELECT 'SYSTEM_ADMIN','rbac.read'
  UNION ALL SELECT 'SYSTEM_ADMIN','rbac.manage'
  UNION ALL SELECT 'SYSTEM_ADMIN','config.read'
  UNION ALL SELECT 'SYSTEM_ADMIN','config.manage'
  UNION ALL SELECT 'SYSTEM_ADMIN','audit.read'
) AS m
JOIN sys_role AS r ON r.role_code=m.role_code
JOIN sys_permission AS p ON p.permission_code=m.permission_code;

-- 配置value_json采用API ConfigValue带类型包装；schema_json约束该包装。
-- 只能PUBLIC投影返回给手机；SERVER配置不整行暴露。无密钥/密码/默认政策文案。
INSERT INTO app_configuration
  (config_key,environment,scope,value_type,value_json,schema_json,description,effective_at,changed_by_user_id,change_reason,is_enabled,created_at,updated_at,row_version,deleted_at)
VALUES
  ('feature.ai_plan','DEV','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','AI计划草稿：默认关闭；模型、政策、授权、预算和校验通过后受控开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.ai_record','DEV','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1自然语言记录：默认关闭，始终需用户核对后应用。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.ai_chat','DEV','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1知识问答：默认关闭，需已核资料与引用校验。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.weekly_review','DEV','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1每周回顾：默认关闭，需真实统计覆盖与证据。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.local_reminder','DEV','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1系统提醒：默认关闭，目标设备权限、锁屏、重启及可观测能力实测后开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.watch_task','DEV','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2手表任务：默认关闭，未确定SDK和目标设备时不得返回已接入。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.external_health','DEV','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2外部运动健康数据：默认关闭，须取得审批与用户单独授权。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.voice','DEV','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2语音：默认关闭，须验证麦克风、文件和处理授权。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.course_import','DEV','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2课程图片导入：默认关闭，识别结果只能先生成待确认草稿。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.external_identity','DEV','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2第三方身份：默认关闭；不将第三方登录授权混作健康数据授权。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.record_attachment','DEV','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1记录附件：默认关闭，先完成私有存储、扫描、配额和删除链路验证。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.feedback','DEV','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1反馈：默认关闭，启用前落实支持权限和最小采集。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.admin_console','DEV','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1管理后台：默认关闭；不提供默认管理员，受控建立真实账号角色后开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.registration_enabled','DEV','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','正式注册默认关闭；配置真实邮件渠道、已发布服务政策与成年确认后开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.require_reauthentication','DEV','SERVER','BOOLEAN','{"booleanValue":true}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','隐私确认与导出下载必须重新认证，不能仅凭长期登录态。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.restore_requires_deletion_ledger','DEV','SERVER','BOOLEAN','{"booleanValue":true}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','备份恢复须先重放删除/撤权台账、撤旧凭据，完成验证后开放。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.change_retention_days','DEV','SERVER','NUMBER','{"numberValue":90}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":31,"maximum":365}},"additionalProperties":false}','个人变更日志工程保留天数；GC须保护有效初始化租约。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.max_offline_days','DEV','SERVER','NUMBER','{"numberValue":30}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','客户端允许离线上限；超过要求重基，不能盲重放。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.bootstrap_lease_minutes','DEV','SERVER','NUMBER','{"numberValue":120}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":15,"maximum":240}},"additionalProperties":false}','INITIALIZING租约分钟；READY稳态不继续受该租约限制。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.operation_retention_days','DEV','SERVER','NUMBER','{"numberValue":120}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":90,"maximum":365}},"additionalProperties":false}','幂等回执保留天数；须大于允许离线窗口。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.batch_max_operations','DEV','SERVER','NUMBER','{"numberValue":50}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":50}},"additionalProperties":false}','单次同步最多操作数，每项独立事务。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.changes_page_default','DEV','SERVER','NUMBER','{"numberValue":200}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":500}},"additionalProperties":false}','变更拉取默认页容量，不得跳过未返回事件推进游标。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.changes_page_max','DEV','SERVER','NUMBER','{"numberValue":500}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":500}},"additionalProperties":false}','变更拉取单页硬上限。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('record.restore_days','DEV','SERVER','NUMBER','{"numberValue":7}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":0,"maximum":7}},"additionalProperties":false}','普通软删除恢复窗口；隐私物理擦除和旧epoch不可恢复。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.upload_max_bytes','DEV','SERVER','NUMBER','{"numberValue":20971520}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1024,"maximum":20971520}},"additionalProperties":false}','普通上传总上限20MiB，仍按用途施加更小上限。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.avatar_max_bytes','DEV','SERVER','NUMBER','{"numberValue":2097152}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1024,"maximum":2097152}},"additionalProperties":false}','头像上限2MiB，探测真实内容类型后方可引用。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.image_max_bytes','DEV','SERVER','NUMBER','{"numberValue":10485760}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1024,"maximum":10485760}},"additionalProperties":false}','普通记录图片上限10MiB。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.download_url_seconds','DEV','SERVER','NUMBER','{"numberValue":300}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":30,"maximum":300}},"additionalProperties":false}','普通私有文件签名URL有效秒数，不提供永久公网链接。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.export_download_url_seconds','DEV','SERVER','NUMBER','{"numberValue":60}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":15,"maximum":60}},"additionalProperties":false}','导出下载签名URL有效秒数，须独立重新认证。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.preview_ttl_minutes','DEV','SERVER','NUMBER','{"numberValue":15}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','固定清单预览有效期；全擦确认须核expectedDataSeq。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.receipt_ttl_days','DEV','SERVER','NUMBER','{"numberValue":30}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":90}},"additionalProperties":false}','仅查工单状态的随机receipt有效天数，属工程配置而非服务承诺。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.export_retention_hours','DEV','SERVER','NUMBER','{"numberValue":24}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":72}},"additionalProperties":false}','导出文件工程保留小时数；失败或过期须清理。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('ai.max_concurrent_runs_per_user','DEV','SERVER','NUMBER','{"numberValue":2}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":2}},"additionalProperties":false}','单用户AI作业并发上限，预算与供应商额度另受控配置。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('ai.provider_timeout_seconds','DEV','SERVER','NUMBER','{"numberValue":60}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":5,"maximum":120}},"additionalProperties":false}','模型外部调用总超时秒数；调用不占数据库长事务。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.access_token_minutes','DEV','SERVER','NUMBER','{"numberValue":15}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','access短有效期；每次仍核securityVersion和账号状态。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.refresh_token_days','DEV','SERVER','NUMBER','{"numberValue":7}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','每代refresh最大有效天数，仍受family绝对截止约束。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.session_family_max_days','DEV','SERVER','NUMBER','{"numberValue":30}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','会话family绝对上限，旋转不能无限续期。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_ttl_minutes','DEV','SERVER','NUMBER','{"numberValue":10}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}','一次性邮箱验证码期限，重发作废旧码。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_resend_seconds','DEV','SERVER','NUMBER','{"numberValue":60}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":30,"maximum":300}},"additionalProperties":false}','同收件人验证码重发最短间隔。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_max_attempts','DEV','SERVER','NUMBER','{"numberValue":5}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}','每挑战验证失败上限。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_hourly_limit','DEV','SERVER','NUMBER','{"numberValue":5}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}','收件人每小时挑战上限，另结合IP/网关风控。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.ai_plan','TEST','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','AI计划草稿：默认关闭；模型、政策、授权、预算和校验通过后受控开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.ai_record','TEST','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1自然语言记录：默认关闭，始终需用户核对后应用。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.ai_chat','TEST','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1知识问答：默认关闭，需已核资料与引用校验。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.weekly_review','TEST','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1每周回顾：默认关闭，需真实统计覆盖与证据。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.local_reminder','TEST','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1系统提醒：默认关闭，目标设备权限、锁屏、重启及可观测能力实测后开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.watch_task','TEST','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2手表任务：默认关闭，未确定SDK和目标设备时不得返回已接入。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.external_health','TEST','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2外部运动健康数据：默认关闭，须取得审批与用户单独授权。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.voice','TEST','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2语音：默认关闭，须验证麦克风、文件和处理授权。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.course_import','TEST','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2课程图片导入：默认关闭，识别结果只能先生成待确认草稿。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.external_identity','TEST','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2第三方身份：默认关闭；不将第三方登录授权混作健康数据授权。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.record_attachment','TEST','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1记录附件：默认关闭，先完成私有存储、扫描、配额和删除链路验证。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.feedback','TEST','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1反馈：默认关闭，启用前落实支持权限和最小采集。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.admin_console','TEST','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1管理后台：默认关闭；不提供默认管理员，受控建立真实账号角色后开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.registration_enabled','TEST','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','正式注册默认关闭；配置真实邮件渠道、已发布服务政策与成年确认后开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.require_reauthentication','TEST','SERVER','BOOLEAN','{"booleanValue":true}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','隐私确认与导出下载必须重新认证，不能仅凭长期登录态。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.restore_requires_deletion_ledger','TEST','SERVER','BOOLEAN','{"booleanValue":true}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','备份恢复须先重放删除/撤权台账、撤旧凭据，完成验证后开放。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.change_retention_days','TEST','SERVER','NUMBER','{"numberValue":90}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":31,"maximum":365}},"additionalProperties":false}','个人变更日志工程保留天数；GC须保护有效初始化租约。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.max_offline_days','TEST','SERVER','NUMBER','{"numberValue":30}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','客户端允许离线上限；超过要求重基，不能盲重放。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.bootstrap_lease_minutes','TEST','SERVER','NUMBER','{"numberValue":120}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":15,"maximum":240}},"additionalProperties":false}','INITIALIZING租约分钟；READY稳态不继续受该租约限制。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.operation_retention_days','TEST','SERVER','NUMBER','{"numberValue":120}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":90,"maximum":365}},"additionalProperties":false}','幂等回执保留天数；须大于允许离线窗口。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.batch_max_operations','TEST','SERVER','NUMBER','{"numberValue":50}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":50}},"additionalProperties":false}','单次同步最多操作数，每项独立事务。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.changes_page_default','TEST','SERVER','NUMBER','{"numberValue":200}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":500}},"additionalProperties":false}','变更拉取默认页容量，不得跳过未返回事件推进游标。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.changes_page_max','TEST','SERVER','NUMBER','{"numberValue":500}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":500}},"additionalProperties":false}','变更拉取单页硬上限。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('record.restore_days','TEST','SERVER','NUMBER','{"numberValue":7}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":0,"maximum":7}},"additionalProperties":false}','普通软删除恢复窗口；隐私物理擦除和旧epoch不可恢复。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.upload_max_bytes','TEST','SERVER','NUMBER','{"numberValue":20971520}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1024,"maximum":20971520}},"additionalProperties":false}','普通上传总上限20MiB，仍按用途施加更小上限。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.avatar_max_bytes','TEST','SERVER','NUMBER','{"numberValue":2097152}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1024,"maximum":2097152}},"additionalProperties":false}','头像上限2MiB，探测真实内容类型后方可引用。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.image_max_bytes','TEST','SERVER','NUMBER','{"numberValue":10485760}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1024,"maximum":10485760}},"additionalProperties":false}','普通记录图片上限10MiB。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.download_url_seconds','TEST','SERVER','NUMBER','{"numberValue":300}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":30,"maximum":300}},"additionalProperties":false}','普通私有文件签名URL有效秒数，不提供永久公网链接。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.export_download_url_seconds','TEST','SERVER','NUMBER','{"numberValue":60}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":15,"maximum":60}},"additionalProperties":false}','导出下载签名URL有效秒数，须独立重新认证。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.preview_ttl_minutes','TEST','SERVER','NUMBER','{"numberValue":15}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','固定清单预览有效期；全擦确认须核expectedDataSeq。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.receipt_ttl_days','TEST','SERVER','NUMBER','{"numberValue":30}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":90}},"additionalProperties":false}','仅查工单状态的随机receipt有效天数，属工程配置而非服务承诺。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.export_retention_hours','TEST','SERVER','NUMBER','{"numberValue":24}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":72}},"additionalProperties":false}','导出文件工程保留小时数；失败或过期须清理。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('ai.max_concurrent_runs_per_user','TEST','SERVER','NUMBER','{"numberValue":2}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":2}},"additionalProperties":false}','单用户AI作业并发上限，预算与供应商额度另受控配置。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('ai.provider_timeout_seconds','TEST','SERVER','NUMBER','{"numberValue":60}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":5,"maximum":120}},"additionalProperties":false}','模型外部调用总超时秒数；调用不占数据库长事务。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.access_token_minutes','TEST','SERVER','NUMBER','{"numberValue":15}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','access短有效期；每次仍核securityVersion和账号状态。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.refresh_token_days','TEST','SERVER','NUMBER','{"numberValue":7}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','每代refresh最大有效天数，仍受family绝对截止约束。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.session_family_max_days','TEST','SERVER','NUMBER','{"numberValue":30}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','会话family绝对上限，旋转不能无限续期。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_ttl_minutes','TEST','SERVER','NUMBER','{"numberValue":10}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}','一次性邮箱验证码期限，重发作废旧码。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_resend_seconds','TEST','SERVER','NUMBER','{"numberValue":60}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":30,"maximum":300}},"additionalProperties":false}','同收件人验证码重发最短间隔。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_max_attempts','TEST','SERVER','NUMBER','{"numberValue":5}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}','每挑战验证失败上限。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_hourly_limit','TEST','SERVER','NUMBER','{"numberValue":5}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}','收件人每小时挑战上限，另结合IP/网关风控。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.ai_plan','PROD','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','AI计划草稿：默认关闭；模型、政策、授权、预算和校验通过后受控开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.ai_record','PROD','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1自然语言记录：默认关闭，始终需用户核对后应用。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.ai_chat','PROD','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1知识问答：默认关闭，需已核资料与引用校验。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.weekly_review','PROD','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1每周回顾：默认关闭，需真实统计覆盖与证据。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.local_reminder','PROD','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1系统提醒：默认关闭，目标设备权限、锁屏、重启及可观测能力实测后开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.watch_task','PROD','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2手表任务：默认关闭，未确定SDK和目标设备时不得返回已接入。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.external_health','PROD','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2外部运动健康数据：默认关闭，须取得审批与用户单独授权。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.voice','PROD','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2语音：默认关闭，须验证麦克风、文件和处理授权。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.course_import','PROD','PUBLIC','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2课程图片导入：默认关闭，识别结果只能先生成待确认草稿。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.external_identity','PROD','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P2第三方身份：默认关闭；不将第三方登录授权混作健康数据授权。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.record_attachment','PROD','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1记录附件：默认关闭，先完成私有存储、扫描、配额和删除链路验证。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.feedback','PROD','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1反馈：默认关闭，启用前落实支持权限和最小采集。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('feature.admin_console','PROD','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','P1管理后台：默认关闭；不提供默认管理员，受控建立真实账号角色后开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.registration_enabled','PROD','SERVER','BOOLEAN','{"booleanValue":false}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','正式注册默认关闭；配置真实邮件渠道、已发布服务政策与成年确认后开启。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.require_reauthentication','PROD','SERVER','BOOLEAN','{"booleanValue":true}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','隐私确认与导出下载必须重新认证，不能仅凭长期登录态。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.restore_requires_deletion_ledger','PROD','SERVER','BOOLEAN','{"booleanValue":true}','{"type":"object","required":["booleanValue"],"properties":{"booleanValue":{"type":"boolean"}},"additionalProperties":false}','备份恢复须先重放删除/撤权台账、撤旧凭据，完成验证后开放。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.change_retention_days','PROD','SERVER','NUMBER','{"numberValue":90}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":31,"maximum":365}},"additionalProperties":false}','个人变更日志工程保留天数；GC须保护有效初始化租约。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.max_offline_days','PROD','SERVER','NUMBER','{"numberValue":30}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','客户端允许离线上限；超过要求重基，不能盲重放。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.bootstrap_lease_minutes','PROD','SERVER','NUMBER','{"numberValue":120}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":15,"maximum":240}},"additionalProperties":false}','INITIALIZING租约分钟；READY稳态不继续受该租约限制。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.operation_retention_days','PROD','SERVER','NUMBER','{"numberValue":120}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":90,"maximum":365}},"additionalProperties":false}','幂等回执保留天数；须大于允许离线窗口。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.batch_max_operations','PROD','SERVER','NUMBER','{"numberValue":50}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":50}},"additionalProperties":false}','单次同步最多操作数，每项独立事务。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.changes_page_default','PROD','SERVER','NUMBER','{"numberValue":200}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":500}},"additionalProperties":false}','变更拉取默认页容量，不得跳过未返回事件推进游标。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('sync.changes_page_max','PROD','SERVER','NUMBER','{"numberValue":500}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":500}},"additionalProperties":false}','变更拉取单页硬上限。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('record.restore_days','PROD','SERVER','NUMBER','{"numberValue":7}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":0,"maximum":7}},"additionalProperties":false}','普通软删除恢复窗口；隐私物理擦除和旧epoch不可恢复。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.upload_max_bytes','PROD','SERVER','NUMBER','{"numberValue":20971520}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1024,"maximum":20971520}},"additionalProperties":false}','普通上传总上限20MiB，仍按用途施加更小上限。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.avatar_max_bytes','PROD','SERVER','NUMBER','{"numberValue":2097152}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1024,"maximum":2097152}},"additionalProperties":false}','头像上限2MiB，探测真实内容类型后方可引用。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.image_max_bytes','PROD','SERVER','NUMBER','{"numberValue":10485760}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1024,"maximum":10485760}},"additionalProperties":false}','普通记录图片上限10MiB。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('file.download_url_seconds','PROD','SERVER','NUMBER','{"numberValue":300}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":30,"maximum":300}},"additionalProperties":false}','普通私有文件签名URL有效秒数，不提供永久公网链接。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.export_download_url_seconds','PROD','SERVER','NUMBER','{"numberValue":60}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":15,"maximum":60}},"additionalProperties":false}','导出下载签名URL有效秒数，须独立重新认证。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.preview_ttl_minutes','PROD','SERVER','NUMBER','{"numberValue":15}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','固定清单预览有效期；全擦确认须核expectedDataSeq。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.receipt_ttl_days','PROD','SERVER','NUMBER','{"numberValue":30}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":90}},"additionalProperties":false}','仅查工单状态的随机receipt有效天数，属工程配置而非服务承诺。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('privacy.export_retention_hours','PROD','SERVER','NUMBER','{"numberValue":24}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":72}},"additionalProperties":false}','导出文件工程保留小时数；失败或过期须清理。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('ai.max_concurrent_runs_per_user','PROD','SERVER','NUMBER','{"numberValue":2}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":2}},"additionalProperties":false}','单用户AI作业并发上限，预算与供应商额度另受控配置。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('ai.provider_timeout_seconds','PROD','SERVER','NUMBER','{"numberValue":60}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":5,"maximum":120}},"additionalProperties":false}','模型外部调用总超时秒数；调用不占数据库长事务。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.access_token_minutes','PROD','SERVER','NUMBER','{"numberValue":15}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','access短有效期；每次仍核securityVersion和账号状态。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.refresh_token_days','PROD','SERVER','NUMBER','{"numberValue":7}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','每代refresh最大有效天数，仍受family绝对截止约束。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.session_family_max_days','PROD','SERVER','NUMBER','{"numberValue":30}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":30}},"additionalProperties":false}','会话family绝对上限，旋转不能无限续期。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_ttl_minutes','PROD','SERVER','NUMBER','{"numberValue":10}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}','一次性邮箱验证码期限，重发作废旧码。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_resend_seconds','PROD','SERVER','NUMBER','{"numberValue":60}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":30,"maximum":300}},"additionalProperties":false}','同收件人验证码重发最短间隔。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_max_attempts','PROD','SERVER','NUMBER','{"numberValue":5}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}','每挑战验证失败上限。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL),
  ('auth.verification_hourly_limit','PROD','SERVER','NUMBER','{"numberValue":5}','{"type":"object","required":["numberValue"],"properties":{"numberValue":{"type":"integer","minimum":1,"maximum":10}},"additionalProperties":false}','收件人每小时挑战上限，另结合IP/网关风控。',@zh_seed_utc,NULL,'新库受控初始化：默认关闭未验证或需审批的能力',1,@zh_seed_utc,@zh_seed_utc,1,NULL);

COMMIT;
-- 预期种子数：goal_definition=5，sys_permission=15，sys_role=6，role_permission=18，app_configuration=123。
-- 注册、AI、系统提醒和P2能力默认关闭；正式启动须配置真实渠道、审核资料、政策与设备能力，再由真实后台操作者受控开启。
-- 完整脚本后续校验应确认上述数量、孤立关系为0，以及未创建任何账号/健康事实。

