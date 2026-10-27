package com.example.lsposed_template;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TicketGuardHookTest {
    @Test
    public void matchesOnlyVerifyPubKeyPath() {
        assertTrue(TicketGuardHook.isVerifyPubKeyUrl(
                "https://security.snssdk.com/passport/ticket_guard/verify_pub_key/"));
        assertTrue(TicketGuardHook.isVerifyPubKeyUrl(
                "https://security.snssdk.com/passport/ticket_guard/verify_pub_key/?aid=123"));
        assertTrue(TicketGuardHook.isVerifyPubKeyUrl(TicketGuardHook.VERIFY_PUB_KEY_PATH));

        assertFalse(TicketGuardHook.isVerifyPubKeyUrl(null));
        assertFalse(TicketGuardHook.isVerifyPubKeyUrl(""));
        assertFalse(TicketGuardHook.isVerifyPubKeyUrl(
                "https://security.snssdk.com/passport/ticket_guard/get_client_cert/"));
        assertFalse(TicketGuardHook.isVerifyPubKeyUrl(
                "https://security.snssdk.com/passport/ticket_guard/verify_pub_key/extra/"));
        assertFalse(TicketGuardHook.isVerifyPubKeyUrl(
                "https://security.snssdk.com/passport/ticket_guard/verify_pub_key"));
        assertFalse(TicketGuardHook.isVerifyPubKeyUrl("not a url"));
    }
}
