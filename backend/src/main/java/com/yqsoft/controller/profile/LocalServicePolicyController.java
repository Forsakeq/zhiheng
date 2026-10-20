package com.yqsoft.controller.profile;

import com.yqsoft.exception.BusinessException;
import com.yqsoft.resource.common.ApiResponse;
import com.yqsoft.resource.profile.LocalServicePolicyView;
import com.yqsoft.resource.profile.LocalServicePolicyView.Section;
import com.yqsoft.security.PublicEndpoint;
import com.yqsoft.service.auth.AuthDeploymentSettings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LocalServicePolicyController {
    private final AuthDeploymentSettings settings;

    public LocalServicePolicyController(AuthDeploymentSettings settings) {
        this.settings = settings;
    }

    @PublicEndpoint
    @GetMapping(AuthDeploymentSettings.LOCAL_SERVICE_POLICY_PATH)
    public ApiResponse<LocalServicePolicyView> servicePolicy() {
        if (!settings.localDevelopment()) {
            throw BusinessException.notFound();
        }
        String version =
                settings.servicePolicy()
                        .filter(
                                policy ->
                                        AuthDeploymentSettings.LOCAL_SERVICE_POLICY_PATH.equals(
                                                policy.documentUrl()))
                        .orElseThrow(BusinessException::notFound)
                        .version();
        return ApiResponse.ok(
                new LocalServicePolicyView(
                        "SERVICE",
                        version,
                        "知衡本地服务说明",
                        List.of(
                                new Section(
                                        "使用范围",
                                        "当前服务用于知衡项目的本地开发和功能联调，仅面向年满18周岁的使用者。"
                                                + "知衡提供生活记录、日常计划和相关信息整理，不提供医疗诊断或治疗。"),
                                new Section(
                                        "账号与验证码",
                                        "注册会创建真实账号并写入当前后端所连接的MySQL数据库。"
                                                + "本地CONSOLE配置下，验证码仅打印在启动Java后端的控制台，"
                                                + "不会发送邮箱；请勿将控制台或验证码转发给他人。"),
                                new Section(
                                        "数据存储与使用",
                                        "你提交的账号资料、记录、计划及相关操作保存在当前后端数据库中；"
                                                + "上传文件由该后端保存在配置的本地文件目录。"
                                                + "请由项目团队妥善管理开发设备、数据库、密钥和备份，优先使用自行编写的测试内容。"),
                                new Section(
                                        "可选能力与数据控制",
                                        "AI等可选能力需要后端实际配置，并按对应用途另行说明和取得确认。"
                                                + "你可以通过应用查看、修改或删除记录，使用数据导出、清理和账号注销功能；"
                                                + "开发者应在正式使用前核实这些流程以及备份的处理方式。"),
                                new Section(
                                        "本次确认与正式发布",
                                        "继续注册表示你已阅读本说明，确认年满18周岁，并同意在上述本地环境中创建账号和处理所提交的数据。"
                                                + "本说明属于本地功能说明，尚非项目正式发布的服务条款或隐私政策。"
                                                + "项目团队正式发布前须提供真实的主体、联系方式、处理目的、数据保存期限和权利渠道，"
                                                + "通过实际HTTPS文档另行发布并更新版本。"))));
    }
}
