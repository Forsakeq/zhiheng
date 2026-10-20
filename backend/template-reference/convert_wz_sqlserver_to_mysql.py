from __future__ import annotations

import argparse
import re
from pathlib import Path


TABLE_RE = re.compile(
    r"CREATE\s+TABLE\s+\[dbo\]\.\[(?P<name>[^\]]+)\]\s*\("
    r"(?P<body>.*?)\r?\n\)\s+ON\s+\[PRIMARY\]",
    re.IGNORECASE | re.DOTALL,
)
COLUMN_RE = re.compile(
    r"^\s*\[(?P<name>[^\]]+)\]\s+\[(?P<type>[^\]]+)\]"
    r"(?:\((?P<args>[^\)]*)\))?\s*"
    r"(?P<identity>IDENTITY\([^\)]*\))?\s*"
    r"(?P<nullable>NOT\s+NULL|NULL)\s*,?\s*$",
    re.IGNORECASE | re.MULTILINE,
)


def quote(identifier: str) -> str:
    return "`" + identifier.replace("`", "``") + "`"


def mysql_type(sql_server_type: str, args: str | None) -> str:
    type_name = sql_server_type.lower()
    type_args = (args or "").strip()
    if type_name in {"varchar", "nvarchar"}:
        if type_args.lower() == "max":
            return "LONGTEXT"
        return f"VARCHAR({type_args})"
    if type_name in {"decimal", "numeric"}:
        return f"DECIMAL({type_args})"
    if type_name == "datetime":
        return "DATETIME"
    if type_name == "bigint":
        return "BIGINT"
    if type_name == "int":
        return "INT"
    if type_name == "bit":
        return "TINYINT(1)"
    if type_name == "float":
        return "DOUBLE"
    raise ValueError(f"Unsupported SQL Server type: {sql_server_type}({type_args})")


def extract_primary_key(body: str) -> list[str]:
    match = re.search(
        r"PRIMARY\s+KEY\s+CLUSTERED\s*\((?P<columns>.*?)\)\s*WITH",
        body,
        re.IGNORECASE | re.DOTALL,
    )
    if not match:
        return []
    return re.findall(r"\[([^\]]+)\]\s+(?:ASC|DESC)", match.group("columns"), re.IGNORECASE)


def convert_tables(source: str) -> tuple[list[str], int]:
    output: list[str] = []
    parsed_column_count = 0
    matches = list(TABLE_RE.finditer(source))
    if len(matches) != 54:
        raise ValueError(f"Expected 54 source tables, found {len(matches)}")

    for table in matches:
        table_name = table.group("name")
        body = table.group("body")
        columns = list(COLUMN_RE.finditer(body))
        if not columns:
            raise ValueError(f"No columns parsed for table {table_name}")
        parsed_column_count += len(columns)
        primary_key = extract_primary_key(body)
        definitions: list[str] = []
        identity_columns: list[str] = []

        for column in columns:
            column_name = column.group("name")
            identity = bool(column.group("identity"))
            nullable = column.group("nullable").upper().replace("  ", " ")
            definition = f"  {quote(column_name)} {mysql_type(column.group('type'), column.group('args'))}"
            if identity:
                definition += " AUTO_INCREMENT"
                identity_columns.append(column_name)
            definition += f" {nullable}"
            definitions.append(definition)

        if primary_key:
            definitions.append("  PRIMARY KEY (" + ", ".join(quote(c) for c in primary_key) + ")")
        else:
            # SQL Server IDENTITY guarantees generated values even when the export omitted a PK.
            # MySQL requires an AUTO_INCREMENT column to be indexed.
            for identity_column in identity_columns:
                definitions.append(
                    f"  UNIQUE KEY {quote('uk_' + table_name + '_' + identity_column)} ({quote(identity_column)})"
                )

        output.append(
            f"CREATE TABLE IF NOT EXISTS {quote(table_name)} (\n"
            + ",\n".join(definitions)
            + "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;"
        )

    source_column_count = len(
        re.findall(r"(?m)^\s*\[[^\]]+\]\s+\[(?:bigint|bit|datetime|decimal|float|int|numeric|nvarchar|varchar)\]", source)
    )
    if parsed_column_count != source_column_count:
        raise ValueError(
            f"Column conversion mismatch: parsed={parsed_column_count}, source={source_column_count}"
        )
    return output, parsed_column_count


HEADER = """-- 文职管理系统 MySQL 8 数据库结构
-- 来源：老师提供的 wz.sql（SQL Server）
-- 说明：54 张原始表和 16 个原始视图均已转换；不包含正式业务数据。
--       team 库的 3 张表只包含原视图明确使用的字段，用于本地联调。
--       cyc 表及 2 个兼容视图来自旧 ASP 的明确引用，已单独标注。

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `team`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- wz.sql 的 login、depart 视图依赖外部 team 数据库。
-- 下列最小结构只用于开发测试；以后取得真实 team 库时可直接替换。
CREATE TABLE IF NOT EXISTS `team`.`baseinfo` (
  `policeno` VARCHAR(50) NOT NULL,
  `name` VARCHAR(50) NULL,
  `password` VARCHAR(255) NULL,
  `departno` VARCHAR(50) NULL,
  `zhiwujibie` INT NULL,
  PRIMARY KEY (`policeno`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `team`.`team_unit` (
  `code` VARCHAR(50) NOT NULL,
  `name` VARCHAR(150) NULL,
  `pxh` VARCHAR(50) NULL,
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `team`.`dpns` (
  `dpn` VARCHAR(50) NOT NULL,
  PRIMARY KEY (`dpn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `wenzhi`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
USE `wenzhi`;
"""


COMPATIBILITY = """
-- ---------------------------------------------------------------------------
-- 旧 ASP 已确认、但老师的 wz.sql 未包含的考核周期表
-- y、j 在旧页面中以表单字符串写入，因此使用 VARCHAR 保留兼容性。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `cyc` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `y` VARCHAR(20) NULL,
  `j` VARCHAR(20) NULL,
  `kssj` DATETIME NULL,
  `jssj` DATETIME NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 打码版 ASP 使用“新手”，老师的真实结构使用“辅警”。
-- 简单视图可继续兼容旧名称，同时只保存一份数据。
CREATE OR REPLACE ALGORITHM=MERGE SQL SECURITY INVOKER VIEW `新手业绩` AS
SELECT `id`, `录入人`, `业绩周期`, `得分`, `uid`, `单位`
FROM `辅警业绩`;

CREATE OR REPLACE ALGORITHM=MERGE SQL SECURITY INVOKER VIEW `文职新手机构设置` AS
SELECT `id`, `名称`, `code`
FROM `文职辅警机构设置`;
"""


VIEWS = """
-- ---------------------------------------------------------------------------
-- 由 wz.sql 转换并按依赖关系重排的 16 个视图
-- ---------------------------------------------------------------------------
CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `考核结果_今年累计` AS
SELECT SUM(`总分`) AS `累计得分`, `uid`
FROM `考核结果`
WHERE YEAR(`考核周期`) = YEAR(CURRENT_DATE())
GROUP BY `uid`;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `考核结果_今年累计2010` AS
SELECT SUM(`总分`) AS `累计得分`, `uid`
FROM `考核结果`
WHERE YEAR(`考核周期`) = 2010
GROUP BY `uid`;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `加班列表` AS
SELECT `id`, `jbr`, `kssj`, `jssj`, `ar`, `xs`, `shr`, `shrq`, `srsj`, `remark`, `dpn`,
       TIMESTAMPDIFF(MINUTE, `kssj`, `jssj`) AS `sjsj`,
       CASE
         WHEN IFNULL(`shr`, '') <> '' AND `shrq` IS NOT NULL THEN '已审核'
         WHEN TIMESTAMPDIFF(HOUR, `srsj`, NOW()) <= 720 THEN '未审核'
         ELSE '已过期'
       END AS `shenhe`
FROM `加班`;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `去年记分` AS
SELECT SUM(`得分`) AS `累计记分`, `uid`, `单位` AS `工作单位`
FROM `记分加分`
WHERE YEAR(`评估时间`) = YEAR(CURRENT_DATE()) - 1
GROUP BY `uid`, `单位`;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `今年记分` AS
SELECT SUM(`得分`) AS `累计记分`, `uid`, `单位` AS `工作单位`
FROM `记分加分`
WHERE YEAR(`评估时间`) = YEAR(CURRENT_DATE())
GROUP BY `uid`, `单位`;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `login` AS
SELECT 'wz' AS `tp`, p.`姓名`, p.`uid`, p.`pwd`, p.`gly`, t.`分管领导`, t.`专管民警`,
       t.`文职中心主任`, t.`政治处主任`, p.`所在管理机构`,
       CASE WHEN IFNULL(t.`管理单位`, '') = '' THEN p.`工作单位` ELSE t.`管理单位` END AS `管理单位`,
       p.`工作单位`, NULL AS `zhiwujibie`
FROM `p` p
LEFT JOIN `team` t ON p.`uid` = t.`pn`
WHERE p.`离开本单位时间` IS NULL
UNION
SELECT 'mj' AS `tp`, b.`name` AS `姓名`, b.`policeno` AS `uid`, b.`password` AS `pwd`,
       t2.`gly`, t2.`分管领导`, t2.`专管民警`, t2.`文职中心主任`, t2.`政治处主任`,
       NULL AS `所在管理机构`,
       CASE WHEN IFNULL(t2.`管理单位`, '') = '' THEN b.`departno` ELSE t2.`管理单位` END AS `管理单位`,
       b.`departno` AS `工作单位`, b.`zhiwujibie`
FROM `team`.`baseinfo` b
LEFT JOIN `team` t2 ON b.`policeno` = t2.`pn`
UNION
SELECT 'mj' AS `tp`, pn.`姓名`, pn.`uid`, pn.`pwd`, t1.`gly`, t1.`分管领导`, t1.`专管民警`,
       t1.`文职中心主任`, t1.`政治处主任`, NULL AS `所在管理机构`,
       CASE WHEN IFNULL(t1.`管理单位`, '') = '' THEN pn.`工作单位` ELSE t1.`管理单位` END AS `管理单位`,
       pn.`工作单位`, pn.`zhiwujibie`
FROM `pn` pn
LEFT JOIN `team` t1 ON pn.`uid` = t1.`pn`;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `请假列表` AS
SELECT `id`, `qjr`, `kssj`, `jssj`, `ar`, `ts`, `srsj`, `dpn`, `remark`, `shr`, `shrq`,
       `shr3`, `shrq3`, `shr7`, `shrq7`, `xjshr`, `xjshrq`, `xjshr3`, `xjshrq3`,
       `xjshr7`, `xjshrq7`, `bty`, `销假时间`,
       CASE WHEN `bty` = 1 THEN '不同意'
            WHEN IFNULL(`shr`, '') <> '' AND `shrq` IS NOT NULL THEN '已审核'
            ELSE '未审核' END AS `shenhe`,
       CASE WHEN `bty` = 1 THEN '不同意'
            WHEN IFNULL(`shr3`, '') <> '' AND `shrq3` IS NOT NULL THEN '已审核'
            WHEN `ts` >= 4 THEN '未审核' ELSE '无需审核' END AS `shenhe3`,
       CASE WHEN `bty` = 1 THEN '不同意'
            WHEN IFNULL(`shr7`, '') <> '' AND `shrq7` IS NOT NULL THEN '已审核'
            WHEN `ts` >= 8 THEN '未审核' ELSE '无需审核' END AS `shenhe7`,
       CASE WHEN IFNULL(`xjshr`, '') <> '' AND `xjshrq` IS NOT NULL THEN '已审核'
            ELSE '未审核' END AS `xjshenhe`,
       CASE WHEN IFNULL(`xjshr3`, '') <> '' AND `xjshrq3` IS NOT NULL THEN '已审核'
            WHEN `ts` >= 4 THEN '未审核' ELSE '无需审核' END AS `xjshenhe3`,
       CASE WHEN IFNULL(`xjshr7`, '') <> '' AND `xjshrq7` IS NOT NULL THEN '已审核'
            WHEN `ts` >= 8 THEN '未审核' ELSE '无需审核' END AS `xjshenhe7`
FROM `请假`;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `depart` AS
SELECT u.`pxh`, u.`code` AS `departno`, u.`name` AS `departname`
FROM `team`.`team_unit` u
LEFT JOIN `team`.`dpns` d ON u.`code` = d.`dpn`
UNION
SELECT `pxh`, `departno`, `departname`
FROM `dpn`;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `p1` AS
SELECT p.`id`, p.`uid`, p.`pwd`, p.`gly`, p.`工作编号`, p.`姓名`, p.`性别`, p.`民族`,
       p.`出生日期`, p.`籍贯`, p.`工作证编号`, p.`政治面貌`, p.`入党团时间`, p.`参加工作时间`,
       p.`进入我局时间`, p.`工作单位`, p.`编制单位`, p.`现单位工作时间`, p.`身份类别`,
       p.`岗位类别`, p.`最高学历`, p.`工作岗位`, p.`身份证号`, p.`婚姻状况`, p.`家庭住址`,
       p.`照片`, p.`上级领导`, p.`派遣单位`, p.`所在管理机构`, p.`现任职位`,
       p.`专业技术等级职称`, p.`离开本单位时间`, p.`离开原因`, p.`宅电`, p.`手机长号`,
       p.`公安短号`, p.`层级评定日期`, p.`实际层级评定日期`, p.`身份1类别`, p.`岗位类别22`,
       p.`视频侦查`, p.`层级`, p.`现层级起始时间`, p.`退伍军人`, p.`岗位工资分类`, p.`分管领导`,
       (SELECT d.`departname` FROM `depart` d WHERE d.`departno` = p.`工作单位`) AS `Expr1`
FROM `p` p
WHERE p.`离开本单位时间` IS NULL;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `编制表11` AS
SELECT b.`departno` AS `dn`
FROM `编制1` a
LEFT JOIN `depart` b ON a.`单位` = b.`departname` AND b.`departno` <> '33038200670000';

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `编制表` AS
SELECT a.*, b.`departno` AS `dn`
FROM `编制` a
LEFT JOIN `depart` b ON a.`单位` = b.`departname` AND b.`departno` <> '33038200670000';

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `去年总记分` AS
SELECT s.`uid`, s.`累计记分`, s.`工作单位`, p.`id`
FROM `p1` p
INNER JOIN `去年记分` s ON p.`uid` = s.`uid`
GROUP BY s.`uid`, s.`累计记分`, s.`工作单位`, p.`id`;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `今年总记分` AS
SELECT s.`uid`, s.`累计记分`, s.`工作单位`, p.`id`
FROM `p1` p
INNER JOIN `今年记分` s ON p.`uid` = s.`uid`
GROUP BY s.`uid`, s.`累计记分`, s.`工作单位`, p.`id`;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `在岗请假列表` AS
SELECT COUNT(*) AS `Expr1`, l.`qjr`, l.`kssj`, l.`jssj`, p.`id`, l.`ar`, l.`ts`, l.`srsj`,
       l.`dpn`, l.`remark`, l.`shr`, l.`shrq`, l.`shr3`, l.`shrq3`, l.`shr7`, l.`shrq7`,
       l.`xjshr`, l.`xjshrq`, l.`xjshr3`, l.`xjshrq3`, l.`xjshr7`, l.`xjshrq7`,
       l.`销假时间`, l.`shenhe`, l.`shenhe3`, l.`shenhe7`, l.`xjshenhe`, l.`xjshenhe3`,
       l.`xjshenhe7`, p.`性别`
FROM `p1` p
INNER JOIN `请假列表` l ON p.`uid` = l.`qjr`
WHERE l.`jssj` > CAST('2019-08-20 00:00:00' AS DATETIME)
  AND l.`ar` IN ('产假', '婚假', '病假', '事假', '哺乳假', '丧假')
GROUP BY l.`qjr`, l.`kssj`, l.`jssj`, p.`id`, l.`ar`, l.`ts`, l.`srsj`, l.`dpn`,
         l.`remark`, l.`shr`, l.`shrq`, l.`shr3`, l.`shrq3`, l.`shr7`, l.`shrq7`,
         l.`xjshr`, l.`xjshrq`, l.`xjshr3`, l.`xjshrq3`, l.`xjshr7`, l.`xjshrq7`,
         l.`销假时间`, l.`shenhe`, l.`shenhe3`, l.`shenhe7`, l.`xjshenhe`, l.`xjshenhe3`,
         l.`xjshenhe7`, p.`性别`;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `今年请假` AS
SELECT SUM(`ts`) AS `累计请假`, `qjr` AS `姓名`, `id`, `dpn` AS `工作单位`, `性别`, `ar`
FROM `在岗请假列表`
WHERE `ar` IN ('事假', '病假', '产假', '哺乳假')
  AND YEAR(`kssj`) = YEAR(CURRENT_DATE())
GROUP BY `qjr`, `id`, `dpn`, `性别`, `ar`
ORDER BY `累计请假` DESC;

CREATE OR REPLACE ALGORITHM=UNDEFINED SQL SECURITY INVOKER VIEW `去年请假` AS
SELECT SUM(`ts`) AS `累计请假`, `qjr` AS `姓名`, `id`, `dpn` AS `工作单位`, `性别`, `ar`
FROM `在岗请假列表`
WHERE `ar` IN ('事假', '病假', '产假', '哺乳假')
  AND YEAR(`kssj`) = YEAR(CURRENT_DATE()) - 1
GROUP BY `qjr`, `id`, `dpn`, `性别`, `ar`
ORDER BY `累计请假` DESC;

SET FOREIGN_KEY_CHECKS = 1;
"""


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert the supplied wz.sql schema to MySQL 8")
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    raw_source = args.source.read_bytes()
    if raw_source.startswith((b"\xff\xfe", b"\xfe\xff")):
        source = raw_source.decode("utf-16")
    elif raw_source.startswith(b"\xef\xbb\xbf"):
        source = raw_source.decode("utf-8-sig")
    else:
        try:
            source = raw_source.decode("utf-8")
        except UnicodeDecodeError:
            source = raw_source.decode("gb18030")
    tables, column_count = convert_tables(source)
    result = HEADER + "\n\n" + "\n\n".join(tables) + "\n" + COMPATIBILITY + "\n" + VIEWS
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(result, encoding="utf-8", newline="\n")

    generated_tables = len(re.findall(r"CREATE TABLE IF NOT EXISTS", result))
    generated_views = len(re.findall(r"CREATE OR REPLACE .*? VIEW", result))
    print(
        f"Generated {args.output}: 54 source tables, {column_count} source columns, "
        f"{generated_tables - 54} compatibility tables, {generated_views} views."
    )


if __name__ == "__main__":
    main()
