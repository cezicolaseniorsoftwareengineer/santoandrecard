package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.FinanceService;
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

    public FinanceResource(FinanceService service, AuthenticatedCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    // Customer-facing operations act on the caller's own wallet. The customer
    // identifier comes from the access token, never from the request body, so one
    // customer cannot move another customer's money.

    @POST @Path("/wallet/top-ups")
    @RolesAllowed(Roles.CUSTOMER)
    public Response topUp(@Valid TopUpRequest request) {
        return Response.status(201).entity(service.topUp(caller.tenantId(), caller.customerId(), request.amount())).build();
    }

    /** Moves the customer's own money from the wallet onto the card. */
    @POST @Path("/wallet/card-loads")
    @RolesAllowed(Roles.CUSTOMER)
    public Response loadCard(@Valid TopUpRequest request) {
        return Response.status(201)
                .entity(service.loadCard(caller.tenantId(), caller.customerId(), request.amount()))
                .build();
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
    public Response purchase(@Valid PurchaseRequest request) {
        return Response.status(201).entity(service.purchase(caller.tenantId(), caller.customerId(),
                request.merchantCategory(), request.amount(), request.installments())).build();
    }

    @PUT @Path("/admin/interest-policy")
    @RolesAllowed(Roles.ADMIN)
    public Object setPolicy(@Valid InterestPolicyRequest request) {
        return service.setInterestPolicy(caller.tenantId(), request.monthlyRate());
    }

    @GET @Path("/admin/summary")
    @RolesAllowed(Roles.ADMIN)
    public Object summary() { return service.adminSummary(caller.tenantId()); }

    public record TopUpRequest(@NotNull @DecimalMin("0.01") BigDecimal amount) {}
    public record QuoteRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @Min(1) @Max(24) int installments) {}
    public record PurchaseRequest(@NotBlank @Size(max=64) String merchantCategory,
                                  @NotNull @DecimalMin("0.01") BigDecimal amount, @Min(1) @Max(24) int installments) {}
    public record InterestPolicyRequest(@NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal monthlyRate) {}
}
