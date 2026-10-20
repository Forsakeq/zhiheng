package com.yqsoft.service.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.yqsoft.exception.BusinessException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class VerificationSenderConfigurationTest {
    @Test
    @SuppressWarnings("unchecked")
    void localConsoleShowsTheActualCodeAndNeverCallsSmtp(CapturedOutput output) {
        MockEnvironment environment = AuthDeploymentSettingsTest.localEnvironment();
        ObjectProvider<JavaMailSender> senders = mock(ObjectProvider.class);
        VerificationSender sender =
                new VerificationSenderConfiguration(environment).verificationSender(senders, environment);

        sender.send("member@example.invalid", "482731", "REGISTER");

        assertTrue(output.getOut().contains("[LOCAL-VERIFICATION]"));
        assertTrue(output.getOut().contains("destination=member@example.invalid"));
        assertTrue(output.getOut().contains("code=482731"));
        assertTrue(output.getOut().contains("purpose=REGISTER"));
        verifyNoInteractions(senders);
    }

    @Test
    @SuppressWarnings("unchecked")
    void smtpRemainsAvailableAndDoesNotPrintTheCode(CapturedOutput output) {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("zhiheng.auth.verification-delivery", "SMTP")
                        .withProperty("spring.mail.host", "mail.example.invalid")
                        .withProperty("zhiheng.auth.mail-from", "sender@example.invalid");
        ObjectProvider<JavaMailSender> senders = mock(ObjectProvider.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(senders.getIfAvailable()).thenReturn(mailSender);
        VerificationSender sender =
                new VerificationSenderConfiguration(environment).verificationSender(senders, environment);

        sender.send("member@example.invalid", "739182", "RESET_PASSWORD");

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertArrayEquals(new String[] {"member@example.invalid"}, message.getValue().getTo());
        assertTrue(message.getValue().getText().contains("739182"));
        assertFalse(output.getAll().contains("739182"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unconfiguredSmtpStillReportsARealFailureInsteadOfSucceeding() {
        MockEnvironment environment = new MockEnvironment();
        ObjectProvider<JavaMailSender> senders = mock(ObjectProvider.class);
        VerificationSender sender =
                new VerificationSenderConfiguration(environment).verificationSender(senders, environment);

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> sender.send("member@example.invalid", "123456", "REGISTER"));

        assertEquals("MAIL_NOT_CONFIGURED", exception.getCode());
    }
}
