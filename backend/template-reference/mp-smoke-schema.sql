CREATE TABLE `p` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `uid` VARCHAR(4000),
    `pwd` VARCHAR(4000),
    `gly` BIGINT,
    `工作编号` VARCHAR(4000),
    `工作标识号` VARCHAR(4000),
    `工作标识号2` VARCHAR(4000),
    `姓名` VARCHAR(4000),
    `性别` VARCHAR(4000),
    `民族` VARCHAR(4000),
    `出生日期` VARCHAR(4000),
    `籍贯` VARCHAR(4000),
    `工作证编号` VARCHAR(4000),
    `政治面貌` VARCHAR(4000),
    `入党团时间` VARCHAR(4000),
    `参加工作时间` VARCHAR(4000),
    `参加工作时间bak` VARCHAR(4000),
    `晋升计算时间` VARCHAR(4000),
    `进入我局时间` VARCHAR(4000),
    `工作单位` VARCHAR(4000),
    `现单位工作时间` VARCHAR(4000),
    `身份类别` VARCHAR(4000),
    `身份1类别` VARCHAR(4000),
    `岗位类别` VARCHAR(4000),
    `岗位类别22` VARCHAR(4000),
    `岗位工资分类` VARCHAR(4000),
    `最高学历` VARCHAR(4000),
    `工作岗位` VARCHAR(4000),
    `岗位信息` VARCHAR(4000),
    `身份证号` VARCHAR(4000),
    `婚姻状况` VARCHAR(4000),
    `家庭住址` VARCHAR(4000),
    `照片` VARCHAR(4000),
    `上级领导` VARCHAR(4000),
    `派遣单位` VARCHAR(4000),
    `所在管理机构` VARCHAR(4000),
    `现任职位` VARCHAR(4000),
    `专业技术等级职称` VARCHAR(4000),
    `离开本单位时间` VARCHAR(4000),
    `离开原因` VARCHAR(4000),
    `宅电` VARCHAR(4000),
    `手机长号` VARCHAR(4000),
    `公安短号` VARCHAR(4000),
    `备注` VARCHAR(4000),
    `离职审核` VARCHAR(4000),
    `新增审核` VARCHAR(4000),
    `离职上传` VARCHAR(4000),
    `离职备注` VARCHAR(4000),
    `层级评定日期` VARCHAR(4000),
    `退伍军人` VARCHAR(4000),
    `实发工资百分比` VARCHAR(4000),
    `层级评定备注` VARCHAR(4000),
    `责任民警` VARCHAR(4000),
    `直接领导` VARCHAR(4000),
    `分管领导` VARCHAR(4000),
    `层级晋升信息` VARCHAR(4000),
    `层级降级信息` VARCHAR(4000),
    `修改审核` VARCHAR(4000),
    `视频侦查` VARCHAR(4000),
    `工资卡类型` VARCHAR(4000),
    `银行卡号` VARCHAR(4000),
    `实际层级评定日期` VARCHAR(4000),
    `现层级起始时间` VARCHAR(4000),
    `层级` VARCHAR(4000),
    `下一级所需年限` VARCHAR(4000),
    `捆绑责任书签订日期` VARCHAR(4000),
    `责任书` VARCHAR(4000),
    `招聘编号` VARCHAR(4000),
    `离职编号` VARCHAR(4000),
    `编制单位` VARCHAR(4000)
);

CREATE TABLE `学历信息` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `pid` BIGINT,
    `开始时间` VARCHAR(4000),
    `结束时间` VARCHAR(4000),
    `学习方式` VARCHAR(4000),
    `毕业院校` VARCHAR(4000),
    `专业` VARCHAR(4000),
    `学历` VARCHAR(4000),
    `学位` VARCHAR(4000)
);

CREATE TABLE `工作履历` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `pid` BIGINT,
    `开始时间` VARCHAR(4000),
    `结束时间` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `职务` VARCHAR(4000)
);

CREATE TABLE `家庭成员` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `pid` BIGINT,
    `关系` VARCHAR(4000),
    `姓名` VARCHAR(4000),
    `工作单位` VARCHAR(4000),
    `联系电话` VARCHAR(4000)
);

CREATE TABLE `爱好特长` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `pid` BIGINT,
    `类型` VARCHAR(4000),
    `详细情况` VARCHAR(4000),
    `有无获奖或资格证书` VARCHAR(4000)
);

CREATE TABLE `p_editlog` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `sj` VARCHAR(4000),
    `pid` BIGINT,
    `修改字段` VARCHAR(4000),
    `原始值` VARCHAR(4000),
    `修改值` VARCHAR(4000),
    `修改uid` VARCHAR(4000),
    `审核` VARCHAR(4000)
);

CREATE TABLE `dpn` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `departno` VARCHAR(4000),
    `departname` VARCHAR(4000),
    `pxh` VARCHAR(4000)
);

CREATE TABLE `岗位类别` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `名称` VARCHAR(4000),
    `code` VARCHAR(4000)
);

CREATE TABLE `文职辅警机构设置` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `名称` VARCHAR(4000),
    `code` VARCHAR(4000)
);

CREATE TABLE `文职新手机构设置` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `名称` VARCHAR(4000),
    `code` VARCHAR(4000)
);

CREATE TABLE `身份类别排序` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `身份类别` VARCHAR(4000),
    `排序` VARCHAR(4000)
);

CREATE TABLE `编制` (
    `序号` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `辅警编制数` VARCHAR(4000)
);

CREATE TABLE `编制1` (
    `序号` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `辅警编制数` VARCHAR(4000)
);

CREATE TABLE `编号` (
    `工作单位` VARCHAR(4000),
    `姓名` VARCHAR(4000),
    `编号` VARCHAR(4000),
    `F4` VARCHAR(4000),
    `F5` VARCHAR(4000),
    `F6` VARCHAR(4000)
);

CREATE TABLE `sheet` (
    `序号` VARCHAR(4000),
    `姓名` VARCHAR(4000),
    `性别` VARCHAR(4000),
    `身份证号` VARCHAR(4000),
    `学历` VARCHAR(4000),
    `进入我局时间` VARCHAR(4000)
);

CREATE TABLE `pn` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `姓名` VARCHAR(4000),
    `uid` VARCHAR(4000),
    `pwd` VARCHAR(4000),
    `工作单位` VARCHAR(4000),
    `zhiwujibie` BIGINT
);

CREATE TABLE `team` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `pn` VARCHAR(4000),
    `gly` BIGINT,
    `分管领导` VARCHAR(4000),
    `专管民警` VARCHAR(4000),
    `文职中心主任` VARCHAR(4000),
    `政治处主任` VARCHAR(4000),
    `管理单位` VARCHAR(4000)
);

CREATE TABLE `limit` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `身份` VARCHAR(4000),
    `权限` VARCHAR(4000)
);

CREATE TABLE `log` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `uid` VARCHAR(4000),
    `tp` VARCHAR(4000),
    `sj` VARCHAR(4000),
    `ip` VARCHAR(4000)
);

CREATE TABLE `层级信息` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `pid` BIGINT,
    `开始时间` VARCHAR(4000),
    `层级` VARCHAR(4000),
    `备注` VARCHAR(4000)
);

CREATE TABLE `提前延迟晋升` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `pid` BIGINT,
    `事由` VARCHAR(4000),
    `评定日期` VARCHAR(4000),
    `提前` VARCHAR(4000),
    `延迟` VARCHAR(4000),
    `srr` VARCHAR(4000),
    `srsj` VARCHAR(4000),
    `shr` VARCHAR(4000),
    `shsj` VARCHAR(4000)
);

CREATE TABLE `请假` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `qjr` VARCHAR(4000),
    `kssj` VARCHAR(4000),
    `jssj` VARCHAR(4000),
    `ar` VARCHAR(4000),
    `ts` VARCHAR(4000),
    `srsj` VARCHAR(4000),
    `dpn` VARCHAR(4000),
    `remark` VARCHAR(4000),
    `shr` VARCHAR(4000),
    `shrq` VARCHAR(4000),
    `shr3` VARCHAR(4000),
    `shrq3` VARCHAR(4000),
    `shr7` VARCHAR(4000),
    `shrq7` VARCHAR(4000),
    `bty` VARCHAR(4000),
    `销假时间` VARCHAR(4000),
    `xjshr` VARCHAR(4000),
    `xjshrq` VARCHAR(4000),
    `xjshr3` VARCHAR(4000),
    `xjshrq3` VARCHAR(4000),
    `xjshr7` VARCHAR(4000),
    `xjshrq7` VARCHAR(4000),
    `上传资料` VARCHAR(4000),
    `上传资料2` VARCHAR(4000),
    `上传资料3` VARCHAR(4000),
    `上传资料4` VARCHAR(4000)
);

CREATE TABLE `加班` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `jbr` VARCHAR(4000),
    `kssj` VARCHAR(4000),
    `jssj` VARCHAR(4000),
    `ar` VARCHAR(4000),
    `xs` VARCHAR(4000),
    `shr` VARCHAR(4000),
    `shrq` VARCHAR(4000),
    `srsj` VARCHAR(4000),
    `remark` VARCHAR(4000),
    `dpn` VARCHAR(4000)
);

CREATE TABLE `加班.bak` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `jbr` VARCHAR(4000),
    `kssj` VARCHAR(4000),
    `jssj` VARCHAR(4000),
    `ar` VARCHAR(4000),
    `xs` VARCHAR(4000),
    `shr` VARCHAR(4000),
    `shrq` VARCHAR(4000),
    `srsj` VARCHAR(4000),
    `remark` VARCHAR(4000),
    `dpn` VARCHAR(4000)
);

CREATE TABLE `离职` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `jbr` VARCHAR(4000),
    `人员` VARCHAR(4000),
    `dpn` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `kssj` VARCHAR(4000),
    `ar` VARCHAR(4000),
    `remark` VARCHAR(4000),
    `上传资料` VARCHAR(4000),
    `上传资料2` VARCHAR(4000),
    `srsj` VARCHAR(4000),
    `shr` VARCHAR(4000),
    `shrq` VARCHAR(4000)
);

CREATE TABLE `记分加分` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `评估内容` VARCHAR(4000),
    `得分` VARCHAR(4000),
    `内容` VARCHAR(4000),
    `录入人` VARCHAR(4000),
    `输入时间` VARCHAR(4000),
    `uid` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `评估时间` VARCHAR(4000),
    `内容a` VARCHAR(4000),
    `内容b` VARCHAR(4000),
    `内容c` VARCHAR(4000),
    `内容d` VARCHAR(4000),
    `时间a` VARCHAR(4000),
    `复选框a` VARCHAR(4000)
);

CREATE TABLE `记分加分评估内容` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `评估项目` VARCHAR(4000),
    `评估内容` VARCHAR(4000),
    `分值` VARCHAR(4000)
);

CREATE TABLE `记分加分.bak` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `评估内容` VARCHAR(4000),
    `得分` VARCHAR(4000),
    `内容` VARCHAR(4000),
    `录入人` VARCHAR(4000),
    `输入时间` VARCHAR(4000),
    `uid` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `评估时间` VARCHAR(4000),
    `内容a` VARCHAR(4000),
    `内容b` VARCHAR(4000),
    `内容c` VARCHAR(4000),
    `内容d` VARCHAR(4000),
    `时间a` VARCHAR(4000),
    `复选框a` VARCHAR(4000)
);

CREATE TABLE `cyc` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `y` VARCHAR(4000),
    `j` VARCHAR(4000),
    `kssj` VARCHAR(4000),
    `jssj` VARCHAR(4000)
);

CREATE TABLE `参加考核人员` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `need` VARCHAR(4000),
    `uid` VARCHAR(4000),
    `测评周期` VARCHAR(4000),
    `备注` VARCHAR(4000),
    `录入人` VARCHAR(4000),
    `单位` VARCHAR(4000)
);

CREATE TABLE `khn_月考核` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `pid` BIGINT,
    `单位` VARCHAR(4000),
    `单位名称` VARCHAR(4000),
    `年度` VARCHAR(4000),
    `月度` VARCHAR(4000),
    `岗位职责` VARCHAR(4000),
    `日常管理` VARCHAR(4000),
    `备注1` VARCHAR(4000),
    `备注2` VARCHAR(4000),
    `备注3` VARCHAR(4000),
    `等次` VARCHAR(4000),
    `总分` VARCHAR(4000),
    `srr` VARCHAR(4000),
    `srsj` VARCHAR(4000)
);

CREATE TABLE `khn_年考核` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `pid` BIGINT,
    `单位` VARCHAR(4000),
    `单位名称` VARCHAR(4000),
    `年度` VARCHAR(4000),
    `岗位职责` VARCHAR(4000),
    `日常管理` VARCHAR(4000),
    `荣誉加分` VARCHAR(4000),
    `备注1` VARCHAR(4000),
    `备注2` VARCHAR(4000),
    `备注3` VARCHAR(4000),
    `备注4` VARCHAR(4000),
    `等次` VARCHAR(4000),
    `总分` VARCHAR(4000),
    `srr` VARCHAR(4000),
    `srsj` VARCHAR(4000),
    `shr` VARCHAR(4000),
    `shsj` VARCHAR(4000)
);

CREATE TABLE `khn_单位` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `年度` VARCHAR(4000),
    `序列` VARCHAR(4000),
    `单位代码` VARCHAR(4000),
    `单位名称` VARCHAR(4000),
    `单位综合考评` VARCHAR(4000),
    `日常管理` VARCHAR(4000),
    `教育训练` VARCHAR(4000),
    `责任捆绑` VARCHAR(4000),
    `编制薪酬` VARCHAR(4000),
    `人数` VARCHAR(4000),
    `人均绩效` VARCHAR(4000),
    `用辅绩效` VARCHAR(4000),
    `荣誉加分` VARCHAR(4000),
    `违规违纪扣分` VARCHAR(4000),
    `总分` VARCHAR(4000),
    `评估等级` VARCHAR(4000)
);

CREATE TABLE `测评` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `类别` VARCHAR(4000),
    `测评周期` VARCHAR(4000),
    `得分` VARCHAR(4000),
    `测评人` VARCHAR(4000),
    `uid` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `测评时间` VARCHAR(4000)
);

CREATE TABLE `辅警业绩` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `录入人` VARCHAR(4000),
    `业绩周期` VARCHAR(4000),
    `得分` VARCHAR(4000),
    `uid` VARCHAR(4000),
    `单位` VARCHAR(4000)
);

CREATE TABLE `新手业绩` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `录入人` VARCHAR(4000),
    `业绩周期` VARCHAR(4000),
    `得分` VARCHAR(4000),
    `uid` VARCHAR(4000),
    `单位` VARCHAR(4000)
);

CREATE TABLE `文职业绩` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `业绩周期` VARCHAR(4000),
    `uid` VARCHAR(4000),
    `述职报告` VARCHAR(4000),
    `录入时间` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `单位得分` VARCHAR(4000),
    `得分` VARCHAR(4000),
    `审核人` VARCHAR(4000),
    `中心鉴定意见` VARCHAR(4000),
    `鉴定意见` VARCHAR(4000),
    `审核时间` VARCHAR(4000),
    `中心审核人` VARCHAR(4000),
    `中心审核时间` VARCHAR(4000)
);

CREATE TABLE `考核结果` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `uid` VARCHAR(4000),
    `考核周期` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `身份类别` VARCHAR(4000),
    `执行制度` VARCHAR(4000),
    `信访投诉` VARCHAR(4000),
    `表彰奖励` VARCHAR(4000),
    `请假` VARCHAR(4000),
    `加班` VARCHAR(4000),
    `警令执行力` VARCHAR(4000),
    `教育训练` VARCHAR(4000),
    `职业能力` VARCHAR(4000),
    `调研创新` VARCHAR(4000),
    `宣传能力` VARCHAR(4000),
    `业绩` VARCHAR(4000),
    `总分` VARCHAR(4000),
    `累计得分` VARCHAR(4000)
);

CREATE TABLE `考核结果.bak` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `uid` VARCHAR(4000),
    `考核周期` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `身份类别` VARCHAR(4000),
    `执行制度` VARCHAR(4000),
    `信访投诉` VARCHAR(4000),
    `表彰奖励` VARCHAR(4000),
    `请假` VARCHAR(4000),
    `加班` VARCHAR(4000),
    `警令执行力` VARCHAR(4000),
    `教育训练` VARCHAR(4000),
    `职业能力` VARCHAR(4000),
    `调研创新` VARCHAR(4000),
    `宣传能力` VARCHAR(4000),
    `业绩` VARCHAR(4000),
    `总分` VARCHAR(4000),
    `累计得分` VARCHAR(4000)
);

CREATE TABLE `荣誉加分` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `uid` VARCHAR(4000),
    `荣誉时间` VARCHAR(4000),
    `内容` VARCHAR(4000),
    `得分` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `录入人` VARCHAR(4000),
    `输入时间` VARCHAR(4000)
);

CREATE TABLE `工资n` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `pid` BIGINT,
    `单位` VARCHAR(4000),
    `单位名称` VARCHAR(4000),
    `年度` VARCHAR(4000),
    `月度` VARCHAR(4000),
    `srr` VARCHAR(4000),
    `srsj` VARCHAR(4000),
    `基础工资` VARCHAR(4000),
    `职位工资` VARCHAR(4000),
    `职级工资` VARCHAR(4000),
    `岗位差异补贴` VARCHAR(4000),
    `加班天数` VARCHAR(4000),
    `请假天数` VARCHAR(4000),
    `加班补贴` VARCHAR(4000),
    `年休补贴` VARCHAR(4000),
    `高温补贴` VARCHAR(4000),
    `月绩效奖` VARCHAR(4000),
    `年绩效奖` VARCHAR(4000),
    `调资预发` VARCHAR(4000),
    `技术等级津贴` VARCHAR(4000),
    `其他` VARCHAR(4000),
    `五险` VARCHAR(4000),
    `公积金` VARCHAR(4000),
    `扣发其他` VARCHAR(4000),
    `单位五险一金` VARCHAR(4000),
    `应发总额` VARCHAR(4000),
    `支出总额` VARCHAR(4000),
    `特岗补贴` VARCHAR(4000),
    `事假` VARCHAR(4000),
    `事假扣发` VARCHAR(4000),
    `病假` VARCHAR(4000),
    `病假扣发` VARCHAR(4000),
    `合法假期` VARCHAR(4000),
    `合法假期扣发` VARCHAR(4000),
    `旷工` VARCHAR(4000),
    `旷工扣发` VARCHAR(4000),
    `新招天数` VARCHAR(4000),
    `离职天数` VARCHAR(4000),
    `其他备注` VARCHAR(4000),
    `合计` VARCHAR(4000),
    `dwshr` VARCHAR(4000),
    `dwshsj` VARCHAR(4000),
    `shr` VARCHAR(4000),
    `shsj` VARCHAR(4000)
);

CREATE TABLE `工资n设置` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `tp` VARCHAR(4000),
    `五险` VARCHAR(4000),
    `公积金` VARCHAR(4000),
    `基础工资` VARCHAR(4000),
    `单位五险一金` VARCHAR(4000)
);

CREATE TABLE `工资` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `uid` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `录入人` VARCHAR(4000),
    `录入时间` VARCHAR(4000),
    `工资周期` VARCHAR(4000),
    `基本工资` VARCHAR(4000),
    `工资1` VARCHAR(4000),
    `工资2` VARCHAR(4000),
    `工资3` VARCHAR(4000),
    `工资4` VARCHAR(4000),
    `工资5` VARCHAR(4000),
    `工资6` VARCHAR(4000),
    `工资7` VARCHAR(4000),
    `工资8` VARCHAR(4000),
    `保险` VARCHAR(4000),
    `保险代扣` VARCHAR(4000),
    `保险代缴` VARCHAR(4000),
    `实发工资` VARCHAR(4000),
    `工资总计` VARCHAR(4000),
    `sh` VARCHAR(4000),
    `备注` VARCHAR(4000)
);

CREATE TABLE `工资审核` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `单位` VARCHAR(4000),
    `工资周期` VARCHAR(4000),
    `录入人` VARCHAR(4000),
    `录入时间` VARCHAR(4000),
    `审核人` VARCHAR(4000),
    `审核时间` VARCHAR(4000)
);

CREATE TABLE `岗位补贴400` (
    `身份证` VARCHAR(4000)
);

CREATE TABLE `证书` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `jbr` VARCHAR(4000),
    `人员` VARCHAR(4000),
    `dpn` VARCHAR(4000),
    `单位` VARCHAR(4000),
    `remark` VARCHAR(4000),
    `ar` VARCHAR(4000),
    `上传资料` VARCHAR(4000),
    `上传资料2` VARCHAR(4000),
    `srsj` VARCHAR(4000),
    `shr` VARCHAR(4000),
    `shrq` VARCHAR(4000),
    `jssj` VARCHAR(4000)
);

CREATE TABLE `导入_考勤` (
    `姓名` VARCHAR(4000)
);

CREATE TABLE `导入_考勤数` (
    `姓名` VARCHAR(4000),
    `考勤` VARCHAR(4000),
    `值班` VARCHAR(4000)
);

CREATE TABLE `导入_食堂` (
    `姓名` VARCHAR(4000)
);

CREATE TABLE account_permission_override (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, account_type VARCHAR(50), account_id BIGINT,
    permission_key VARCHAR(255), effect VARCHAR(50)
);
CREATE TABLE account_permission_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, account_type VARCHAR(50), account_id BIGINT,
    account_uid VARCHAR(255), permission_key VARCHAR(255), old_effect VARCHAR(50), new_effect VARCHAR(50),
    operator_uid VARCHAR(255), changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
