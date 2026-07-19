package me.one_org.chatControlePlane.utils;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class Mail {
    private final JavaMailSender mailSender;

    public Mail(JavaMailSender mailSender) {

        this.mailSender = mailSender;
    }

    public void sendMail(String toEmail,String otp){
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject("otp");
        message.setText(otp);
        mailSender.send(message);
    }
}
