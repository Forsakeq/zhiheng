"""Build the reviewed 60-table MySQL schema from the frozen design catalog.

No database connection and no destructive operations occur in this builder.
All foreign keys are emitted after CREATE TABLE statements. SQL CHECK rules are
provided by the independent single-row-constraint review in check_constraints.json.
"""
from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from pathlib import Path

WORK = Path(__file__).resolve().parent
SOURCE = Path(r"C:/Users/86136/.codex/visualizations/2026/10/10/01a1238a-cbb3-70b3-88f9-97a9695f08f3/health-backend-plan-20261016/_work/database-schema-catalog.json")
OUTPUT = WORK / "schema_base.sql"
CHECK_FILE = WORK / "check_constraints.json"

def quote(value: str) -> str:
    # NO_BACKSLASH_ESCAPES is not required: escape both SQL quotes and backslashes.
    return "'" + value.replace("\\", "\\\\").replace("'", "''").replace("\x00", "") + "'"

def ident(value: str) -> str:
    assert re.fullmatch(r"[a-z][a-z0-9_]*", value), value
    return "`" + value + "`"

def short_name(prefix: str, table: str, columns: tuple[str, ...]) -> str:
    text = prefix + "_" + table + "_" + "_".join(columns)
    return text if len(text) <= 64 else text[:55] + "_" + hashlib.sha1(text.encode()).hexdigest()[:8]

catalog = json.loads(SOURCE.read_text(encoding="utf-8"))
assert len(catalog) == 60
assert sum(len(t["fields"]) for t in catalog) == 830
tables: dict[str, dict] = {}

def field(name: str, sql_type: str, nullable: bool, comment: str,
          default: str | None = None, auto_increment: bool = False,
          generated: str | None = None) -> dict:
    return dict(name=name, sql_type=sql_type, nullable=nullable, comment=comment,
                default=default, auto_increment=auto_increment, generated=generated)

for entry in catalog:
    name = entry["name"]
    common = entry["common"]
    columns = []
    if common.startswith(("CM", "CA")):
        columns.extend([
            field("id", "BIGINT UNSIGNED", False, "内部主键；API使用十进制字符串", auto_increment=True),
            field("created_at", "DATETIME(3)", False, "服务端写入UTC创建时间"),
        ])
        primary = ("id",)
        if common.startswith("CM"):
            columns.extend([
                field("updated_at", "DATETIME(3)", False, "服务端写入UTC更新时间；不自动替代业务版本"),
                field("row_version", "BIGINT UNSIGNED", False, "乐观锁版本；每次合法变更递增", "1"),
                field("deleted_at", "DATETIME(3)", True, "普通软删除UTC时间；隐私清除按工单执行"),
            ])
    elif common.startswith("CD"):
        columns.extend([
            field("record_id", "BIGINT UNSIGNED", False, "父health_record主键；详情无独立版本或软删除列"),
            field("created_at", "DATETIME(3)", False, "服务端写入UTC创建时间"),
            field("updated_at", "DATETIME(3)", False, "随主记录同事务更新UTC时间"),
        ])
        primary = ("record_id",)
    else:
        assert name == "user_sync_clock"
        primary = ("user_id",)

    for col_name, sql_type, null_default, description in entry["fields"]:
        if " ASCII" in sql_type:
            sql_type = sql_type.replace(" ASCII", " CHARACTER SET ascii COLLATE ascii_bin")
        nullable = null_default.startswith("NULL") or "生成列" in null_default
        default = None
        generated = None
        if "默认 " in null_default:
            literal = null_default.split("默认 ", 1)[1].strip()
            default = literal if re.fullmatch(r"-?\d+(\.\d+)?", literal) else quote(literal)
        if "生成列" in null_default:
            assert name == "external_data_connection" and col_name == "active_provider"
            generated = "CASE WHEN `status` = 'ACTIVE' AND `deleted_at` IS NULL THEN `provider_code` ELSE NULL END"
        if name == "role_permission" and col_name == "granted_by_user_id":
            nullable = True
            description += "；SQL落地修订：仅受控初始化种子允许NULL，真实后台授权仍必须真实操作者"
        if name == "knowledge_source" and col_name == "source_kind":
            sql_type = "VARCHAR(24)"
            description += "；SQL落地勘误：VARCHAR24可容纳APP_DOCUMENTATION（17字符）"
        if name == "ai_message" and col_name == "role":
            sql_type = "VARCHAR(16)"
            description += "；SQL落地勘误：VARCHAR16可容纳SYSTEM_NOTICE（13字符）"
        columns.append(field(col_name, sql_type, nullable, description, default, generated=generated))

    names = [x["name"] for x in columns]
    assert len(names) == len(set(names)), name
    tables[name] = dict(catalog=entry, columns=columns, by_col={x["name"]:x for x in columns},
                        primary=primary, unique=[], indexes=[], checks=[], foreign=[])

def key(table: str, columns: tuple[str, ...] | list[str], *, unique: bool) -> None:
    item = tables[table]
    cols = tuple(columns)
    assert all(c in item["by_col"] for c in cols), (table, cols)
    if cols == item["primary"]:
        return
    target = item["unique"] if unique else item["indexes"]
    if cols not in target and (unique or cols not in item["unique"]):
        target.append(cols)

# Frozen per-table business keys and query indexes from the reviewed catalog.
for table, item in tables.items():
    text = item["catalog"]["constraints"]
    for marker, unique in (("UNIQUE", True), ("INDEX", False)):
        for match in re.finditer(r"\b" + marker + r"\(([^()]*)\)", text):
            cols = tuple(c.strip().strip("`") for c in match.group(1).split(","))
            if all(re.fullmatch(r"[a-z][a-z0-9_]*", c) for c in cols):
                key(table, cols, unique=unique)
    # Every owned entity can be referenced with owner + object ID.
    if "user_id" in item["by_col"] and "id" in item["by_col"]:
        key(table, ("user_id", "id"), unique=True)

foreign_keys: list[dict] = []

def fk(table: str, columns: tuple[str, ...] | list[str],
       parent: str, parent_columns: tuple[str, ...] | list[str]) -> None:
    cols, pcols = tuple(columns), tuple(parent_columns)
    assert len(cols) == len(pcols)
    assert all(c in tables[table]["by_col"] for c in cols), (table, cols)
    assert all(c in tables[parent]["by_col"] for c in pcols), (parent, pcols)
    for child_col, parent_col in zip(cols, pcols):
        a, b = tables[table]["by_col"][child_col], tables[parent]["by_col"][parent_col]
        assert a["sql_type"] == b["sql_type"], (table, child_col, a["sql_type"], parent, parent_col, b["sql_type"])
    signature = (table, cols, parent, pcols)
    if any(x["signature"] == signature for x in foreign_keys):
        return
    # MySQL 8.4 requires an exact unique parent key, not a nonunique/prefix key.
    key(parent, pcols, unique=True)
    # Explicit child indexes avoid engine-assigned unpredictable index names.
    if not any(k[:len(cols)] == cols for k in [tables[table]["primary"]] + tables[table]["unique"] + tables[table]["indexes"]):
        key(table, cols, unique=False)
    definition = dict(table=table, columns=cols, parent=parent,
                      parent_columns=pcols, signature=signature)
    foreign_keys.append(definition)
    tables[table]["foreign"].append(definition)

def owned(table: str, column: str, parent: str) -> None:
    fk(table, ("user_id", column), parent, ("user_id", "id"))

for table, item in tables.items():
    if "user_id" in item["by_col"] and table != "app_user":
        fk(table, ("user_id",), "app_user", ("id",))

owned("user_profile", "avatar_file_id", "file_object")
owned("auth_session", "installation_id", "client_installation")
fk("auth_session", ("user_id", "family_id", "client_audience", "parent_session_id"),
   "auth_session", ("user_id", "family_id", "client_audience", "id"))
fk("auth_session", ("user_id", "family_id", "client_audience", "replaced_by_session_id"),
   "auth_session", ("user_id", "family_id", "client_audience", "id"))
owned("client_installation", "parent_installation_id", "client_installation")
owned("user_consent", "installation_id", "client_installation")
fk("user_consent", ("user_id", "id", "last_event_id"),
   "user_consent_event", ("user_id", "consent_id", "id"))
owned("user_consent_event", "consent_id", "user_consent")
owned("user_consent_event", "installation_id", "client_installation")
fk("user_goal", ("goal_definition_id",), "goal_definition", ("id",))
owned("availability_rule", "source_batch_id", "import_batch")
owned("availability_exception", "source_batch_id", "import_batch")
owned("health_record", "source_connection_id", "external_data_connection")
owned("health_record", "ai_draft_id", "ai_draft")
owned("health_record", "import_batch_id", "import_batch")
for detail in ("sleep_record_detail", "activity_record_detail", "meal_record_detail", "energy_record_detail"):
    fk(detail, ("user_id", "record_id", "detail_type"),
       "health_record", ("user_id", "id", "record_type"))
owned("record_tag_relation", "record_id", "health_record")
owned("record_tag_relation", "tag_id", "record_tag")
owned("record_attachment", "record_id", "health_record")
owned("record_attachment", "file_id", "file_object")
owned("plan", "origin_draft_id", "ai_draft")
fk("plan", ("user_id", "id", "active_version_id"),
   "plan_version", ("user_id", "plan_id", "id"))
owned("plan_version", "plan_id", "plan")
fk("plan_version", ("user_id", "plan_id", "based_on_version_id"),
   "plan_version", ("user_id", "plan_id", "id"))
fk("plan_version", ("user_id", "plan_id", "restore_from_version_id"),
   "plan_version", ("user_id", "plan_id", "id"))
owned("plan_version", "source_draft_id", "ai_draft")
owned("task", "plan_id", "plan")
fk("task", ("user_id", "plan_id", "current_plan_version_id"),
   "plan_version", ("user_id", "plan_id", "id"))
fk("task", ("user_id", "plan_id", "rule_id"),
   "plan_rule", ("user_id", "plan_id", "id"))
fk("plan_version_task", ("user_id", "plan_id", "version_id"),
   "plan_version", ("user_id", "plan_id", "id"))
fk("plan_version_task", ("user_id", "plan_id", "task_id"),
   "task", ("user_id", "plan_id", "id"))
fk("plan_version_task", ("user_id", "plan_id", "rule_id"),
   "plan_rule", ("user_id", "plan_id", "id"))
owned("plan_rule", "plan_id", "plan")
owned("task_event", "task_id", "task")
fk("task_event", ("user_id", "plan_version_id", "task_id", "plan_version_task_id"),
   "plan_version_task", ("user_id", "version_id", "task_id", "id"))
owned("task_record_link", "task_id", "task")
owned("task_record_link", "record_id", "health_record")
fk("ai_conversation", ("user_id", "id", "last_message_id"),
   "ai_message", ("user_id", "conversation_id", "id"))
owned("ai_message", "conversation_id", "ai_conversation")
fk("ai_message", ("user_id", "conversation_id", "reply_to_message_id"),
   "ai_message", ("user_id", "conversation_id", "id"))
owned("ai_message", "ai_run_id", "ai_run")
owned("ai_message", "draft_id", "ai_draft")
owned("ai_run", "conversation_id", "ai_conversation")
owned("ai_run", "input_message_id", "ai_message")
owned("ai_draft", "source_run_id", "ai_run")
owned("ai_draft", "conversation_id", "ai_conversation")
owned("ai_draft", "base_plan_id", "plan")
fk("ai_draft", ("user_id", "base_plan_id", "base_plan_version_id"),
   "plan_version", ("user_id", "plan_id", "id"))
owned("ai_draft_revision", "draft_id", "ai_draft")
owned("ai_message_citation", "message_id", "ai_message")
fk("ai_message_citation", ("article_version_id", "knowledge_source_id"),
   "knowledge_version_source", ("article_version_id", "knowledge_source_id"))
fk("knowledge_article", ("id", "active_version_id"),
   "knowledge_article_version", ("article_id", "id"))
fk("knowledge_article_version", ("article_id",), "knowledge_article", ("id",))
fk("knowledge_article_version", ("author_user_id",), "app_user", ("id",))
fk("knowledge_article_version", ("reviewer_user_id",), "app_user", ("id",))
fk("knowledge_version_source", ("article_version_id",), "knowledge_article_version", ("id",))
fk("knowledge_version_source", ("knowledge_source_id",), "knowledge_source", ("id",))
owned("weekly_review", "ai_run_id", "ai_run")
owned("weekly_review_evidence", "review_id", "weekly_review")
owned("weekly_review_evidence", "daily_stat_id", "daily_stat")
owned("weekly_review_evidence", "record_id", "health_record")
owned("weekly_review_evidence", "task_event_id", "task_event")
owned("reminder_rule", "task_id", "task")
owned("reminder_rule", "target_installation_id", "client_installation")
owned("reminder_delivery", "rule_id", "reminder_rule")
owned("reminder_delivery", "task_id", "task")
owned("reminder_delivery", "installation_id", "client_installation")
owned("privacy_request", "scope_manifest_file_id", "file_object")
owned("privacy_request", "result_file_id", "file_object")
owned("feedback_ticket", "installation_id", "client_installation")
fk("feedback_ticket", ("assigned_to_user_id",), "app_user", ("id",))
fk("app_configuration", ("changed_by_user_id",), "app_user", ("id",))
owned("sync_operation", "installation_id", "client_installation")
owned("device_task_receipt", "installation_id", "client_installation")
owned("device_task_receipt", "task_id", "task")
owned("import_batch", "source_connection_id", "external_data_connection")
owned("import_batch", "source_file_id", "file_object")
fk("user_role_assignment", ("role_id",), "sys_role", ("id",))
fk("user_role_assignment", ("granted_by_user_id",), "app_user", ("id",))
fk("user_role_assignment", ("revoked_by_user_id",), "app_user", ("id",))
fk("role_permission", ("role_id",), "sys_role", ("id",))
fk("role_permission", ("permission_id",), "sys_permission", ("id",))
fk("role_permission", ("granted_by_user_id",), "app_user", ("id",))

if CHECK_FILE.exists():
    rules = json.loads(CHECK_FILE.read_text(encoding="utf-8"))
    seen_check_names = set()
    for rule in rules:
        table, name, expression = rule["table"], rule["name"], rule["expression"]
        assert table in tables
        assert len(name) <= 64 and name not in seen_check_names, name
        ident(name)
        seen_check_names.add(name)
        tables[table]["checks"].append(dict(name=name, expression=expression, note=rule.get("note", "")))

HEADER = """-- 知衡完整数据库结构（60表；建议MySQL 8.4 / InnoDB，兼容性由实际验证记录说明）
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
CREATE DATABASE IF NOT EXISTS `zhiheng` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `zhiheng`;

"""

def render_column(col: dict) -> str:
    text = ident(col["name"]) + " " + col["sql_type"]
    if col["generated"]:
        return text + " GENERATED ALWAYS AS (" + col["generated"] + ") STORED COMMENT " + quote(col["comment"])
    text += " NULL" if col["nullable"] else " NOT NULL"
    if col["default"] is not None:
        text += " DEFAULT " + col["default"]
    if col["auto_increment"]:
        text += " AUTO_INCREMENT"
    return text + " COMMENT " + quote(col["comment"])

parts = [HEADER]
for number, (table, item) in enumerate(tables.items(), 1):
    meta = item["catalog"]
    parts.append(f"-- {number:02d}. {table} / {meta['title']} / {meta['phase']}\n")
    parts.append("-- " + meta["purpose"] + "\n")
    entries = [render_column(col) for col in item["columns"]]
    entries.append("PRIMARY KEY (" + ", ".join(map(ident, item["primary"])) + ")")
    for unique in item["unique"]:
        entries.append("UNIQUE KEY " + ident(short_name("uq", table, unique)) + " (" + ", ".join(map(ident, unique)) + ")")
    for index in item["indexes"]:
        if index not in item["unique"]:
            entries.append("KEY " + ident(short_name("ix", table, index)) + " (" + ", ".join(map(ident, index)) + ")")
    for rule in item["checks"]:
        entries.append("CONSTRAINT " + ident(rule["name"]) + " CHECK (" + rule["expression"] + ")")
    parts.append("CREATE TABLE " + ident(table) + " (\n  " + ",\n  ".join(entries) + "\n) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT=" + quote(meta["title"] + "；" + meta["phase"]) + ";\n\n")

parts.append("-- 所有表和精确唯一父键已创建，最后统一建立外键（不关闭检查）。\n")
used_fk_names = set()
for item in foreign_keys:
    name = short_name("fk", item["table"], item["columns"])
    if name in used_fk_names:
        name = short_name("fk", item["table"], item["columns"] + (item["parent"],))
    assert name not in used_fk_names
    used_fk_names.add(name)
    parts.append("ALTER TABLE " + ident(item["table"]) + " ADD CONSTRAINT " + ident(name) + " FOREIGN KEY (" + ", ".join(map(ident, item["columns"])) + ") REFERENCES " + ident(item["parent"]) + " (" + ", ".join(map(ident, item["parent_columns"])) + ");\n")

OUTPUT.write_text("".join(parts), encoding="utf-8")
summary = dict(tables=len(tables), business_columns=sum(len(x["fields"]) for x in catalog),
               physical_columns=sum(len(x["columns"]) for x in tables.values()),
               unique_keys=sum(len(x["unique"]) for x in tables.values()),
               indexes=sum(len(x["indexes"]) for x in tables.values()),
               foreign_keys=len(foreign_keys), checks=sum(len(x["checks"]) for x in tables.values()),
               source_sha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
               sql_sha256=hashlib.sha256(OUTPUT.read_bytes()).hexdigest(),
               initialization_override="role_permission.granted_by_user_id nullable only for controlled seed")
(WORK / "schema_model.json").write_text(json.dumps(tables, ensure_ascii=False, indent=2), encoding="utf-8")
(WORK / "schema_build_report.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False))
