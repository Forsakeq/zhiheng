package com.yqsoft.service.auth;

import com.yqsoft.exception.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class VerificationSenderConfiguration {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(VerificationSenderConfiguration.class);

    private final String delivery;

    public VerificationSenderConfiguration(Environment environment) {
        // Validate even when another VerificationSender bean is supplied.
        this.delivery = AuthDeploymentSettings.verificationDelivery(environment);
    }

    @Bean
    @ConditionalOnMissingBean(VerificationSender.class)
    VerificationSender verificationSender(
            ObjectProvider<JavaMailSender> senders, Environment environment) {
        if ("CONSOLE".equals(delivery)) {
            LOGGER.warn("本地验证码已启用：验证码将打印在当前 Java 控制台，不发送邮件");
            return (destination, code, purpose) ->
                    LOGGER.info(
                            "[LOCAL-VERIFICATION] purpose={} destination={} code={}（仅本地开发）",
                            purpose,
                            destination,
                            code);
        }
        return (destination, code, purpose) -> {
            JavaMailSender sender = senders.getIfAvailable();
            String host = environment.getProperty("spring.mail.host", "");
            String from = environment.getProperty("zhiheng.auth.mail-from", "");
            if (sender == null || host.isBlank() || from.isBlank()) {
                throw BusinessException.unavailable("MAIL_NOT_CONFIGURED", "邮箱验证服务尚未配置");
            }
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(destination);
            mail.setSubject("知衡身份验证码");
            mail.setText("你的知衡验证码为：" + code + "。请在页面所示有效期内使用，请勿转发。" + "若不是你本人操作，请忽略。");
            try {
                sender.send(mail);
            } catch (RuntimeException exception) {
                throw BusinessException.unavailable("MAIL_DELIVERY_FAILED", "验证码发送失败，请稍后重试");
            }
        };
    }
}
