package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.FinanceService;
import com.cezicola.card.application.IdempotentOperation;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class FinanceResource {
    private final FinanceService service;
    private final AuthenticatedCaller caller;
    private final IdempotentOperation idempotent;

    public FinanceResource(FinanceService service, AuthenticatedCaller caller, IdempotentOperation idempotent) {
        this.service = service;
        this.caller = caller;
        this.idempotent = idempotent;
    }

    // Customer-facing operations act on the caller's own wallet. The customer
    // identifier comes from the access token, never from the request body, so one
    // customer cannot move another customer's money.

    // Every operation that moves money requires a key. A client that timed out
    // cannot tell a lost request from a lost response; the key is what lets it
    // ask again without paying twice.
    @POST @Path("/wallet/top-ups")
    @RolesAllowed(Roles.CUSTOMER)
    public Response topUp(@HeaderParam("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                          @Valid TopUpRequest request) {
        var wallet = idempotent.execute(caller.tenantId(), "wallet-top-up", key, request,
                FinanceService.WalletView.class,
                () -> service.topUp(caller.tenantId(), caller.customerId(), request.amount()));
        return Response.status(201).entity(wallet).build();
    }

    /** Moves the customer's own money from the wallet onto the card. */
    @POST @Path("/wallet/card-loads")
    @RolesAllowed(Roles.CUSTOMER)
    public Response loadCard(@HeaderParam("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                             @Valid TopUpRequest request) {
        var balances = idempotent.execute(caller.tenantId(), "card-load", key, request,
                FinanceService.CardBalanceView.class,
                () -> service.loadCard(caller.tenantId(), caller.customerId(), request.amount()));
        return Response.status(201).entity(balances).build();
    }

    @GET @Path("/wallet")
    @RolesAllowed(Roles.CUSTOMER)
    public Object wallet() {
        return service.wallet(caller.tenantId(), caller.customerId());
    }

    @GET @Path("/purchases")
    @RolesAllowed(Roles.CUSTOMER)
    public Object statement(@QueryParam("limit") @DefaultValue("50") int limit) {
        return service.statement(caller.tenantId(), caller.customerId(), limit);
    }

    @POST @Path("/purchases/quote")
    @RolesAllowed(Roles.CUSTOMER)
    public Object quote(@Valid QuoteRequest request) {
        return service.quote(caller.tenantId(), request.amount(), request.installments());
    }

    @POST @Path("/purchases")
    @RolesAllowed(Roles.CUSTOMER)
    public Response purchase(@HeaderParam("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                             @Valid PurchaseRequest request) {
        var purchase = idempotent.execute(caller.tenantId(), "purchase", key, request,
                FinanceService.PurchaseView.class,
                () -> service.purchase(caller.tenantId(), caller.customerId(),
                        request.merchantCategory(), request.amount(), request.installments()));
        return Response.status(201).entity(purchase).build();
    }

    @PUT @Path("/admin/interest-policy")
    @RolesAllowed(Roles.ADMIN)
    public Object setPolicy(@Valid InterestPolicyRequest request) {
        return service.setInterestPolicy(caller.tenantId(), request.monthlyRate());
    }

    /**
     * The rate in force, readable by customer and administrator alike. The
     * instalment screen states the rate it prices with, and it can only do that
     * if it can read it.
     */
    @GET @Path("/interest-policy")
    @RolesAllowed({Roles.CUSTOMER, Roles.ADMIN})
    public Object interestPolicy() { return service.interestPolicy(caller.tenantId()); }

    @GET @Path("/admin/summary")
    @RolesAllowed(Roles.ADMIN)
    public Object summary() { return service.adminSummary(caller.tenantId()); }

    public record TopUpRequest(@NotNull @DecimalMin("0.01") BigDecimal amount) {}
    public record QuoteRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @Min(1) @Max(12) int installments) {}
    public record PurchaseRequest(@NotBlank @Size(max=64) String merchantCategory,
                                  @NotNull @DecimalMin("0.01") BigDecimal amount, @Min(1) @Max(12) int installments) {}
    public record InterestPolicyRequest(@NotNull @DecimalMin("0.0") @DecimalMax("0.60") BigDecimal monthlyRate) {}
}
