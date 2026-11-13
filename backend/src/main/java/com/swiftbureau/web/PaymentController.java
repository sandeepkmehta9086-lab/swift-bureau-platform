package com.swiftbureau.web;

import com.swiftbureau.domain.MessageDirection;
import com.swiftbureau.identity.AuthService;
import com.swiftbureau.payment.PaymentMessage;
import com.swiftbureau.payment.PaymentService;
import com.swiftbureau.security.CurrentUser;
import com.swiftbureau.security.SecurityUtil;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;
    private final AuthService authService;

    public PaymentController(PaymentService paymentService, AuthService authService) {
        this.paymentService = paymentService;
        this.authService = authService;
    }

    @PostMapping("/payments")
    public PaymentMessage create(@RequestBody PaymentService.CreatePaymentRequest request) {
        return paymentService.create(SecurityUtil.current(), request);
    }

    @PostMapping("/payments/{id}/submit")
    public PaymentMessage submit(@PathVariable java.util.UUID id) {
        return paymentService.submit(SecurityUtil.current(), id);
    }

    @PostMapping("/payments/{id}/approve")
    public PaymentMessage approve(
            @PathVariable java.util.UUID id,
            @RequestHeader("X-Step-Up-Totp") String totp
    ) {
        CurrentUser actor = SecurityUtil.current();
        authService.verifyStepUp(actor, totp);
        return paymentService.approve(actor, id);
    }

    @GetMapping("/payments")
    public List<PaymentMessage> list(@RequestParam(required = false) MessageDirection direction) {
        return paymentService.list(SecurityUtil.current(), direction);
    }

    @GetMapping("/payments/{id}")
    public PaymentMessage get(@PathVariable java.util.UUID id) {
        return paymentService.get(SecurityUtil.current(), id);
    }

    @GetMapping("/inbox")
    public List<PaymentMessage> inbox() {
        return paymentService.list(SecurityUtil.current(), MessageDirection.IN);
    }

    /** Stage 1: a customer pain.001 arrives; the bank answers with pain.002 and drafts the pacs.008 if accepted. */
    @PostMapping("/payments/initiate")
    public PaymentService.InitiationResult initiate(@RequestBody PaymentService.CustomerInstructionRequest request) {
        return paymentService.initiate(SecurityUtil.current(), request);
    }

    /** Stage 4: camt.056 recall. Body is optional; {@code reasonCode} defaults to CUST. */
    @PostMapping("/payments/{id}/recall")
    public PaymentMessage recall(@PathVariable java.util.UUID id, @RequestBody(required = false) RecallRequest request) {
        return paymentService.recall(SecurityUtil.current(), id, request == null ? null : request.reasonCode());
    }

    public record RecallRequest(String reasonCode) {
    }
}
