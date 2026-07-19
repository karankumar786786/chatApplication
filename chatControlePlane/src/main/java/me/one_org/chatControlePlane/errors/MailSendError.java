package me.one_org.chatControlePlane.errors;

public class MailSendError extends RuntimeException {
    public MailSendError(String message) {
        super(message);
    }
}
