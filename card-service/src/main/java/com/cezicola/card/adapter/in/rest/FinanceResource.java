package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.FinanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.UUID;

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class FinanceResource {
    private final FinanceService service;
    public FinanceResource(FinanceService service) { this.service = service; }

    @POST @Path("/wallet/top-ups")
    public Response topUp(@HeaderParam("X-Tenant-Id") @NotNull UUID tenantId, @Valid TopUpRequest request) {
        return Response.status(201).entity(service.topUp(tenantId, request.customerId(), request.amount())).build();
    }
    @POST @Path("/purchases/quote")
    public Object quote(@HeaderParam("X-Tenant-Id") @NotNull UUID tenantId, @Valid QuoteRequest request) {
        return service.quote(tenantId, request.amount(), request.installments());
    }
    @POST @Path("/purchases")
    public Response purchase(@HeaderParam("X-Tenant-Id") @NotNull UUID tenantId, @Valid PurchaseRequest request) {
        return Response.status(201).entity(service.purchase(tenantId, request.customerId(), request.merchantCategory(), request.amount(), request.installments())).build();
    }
    @PUT @Path("/admin/interest-policy")
    public Object setPolicy(@HeaderParam("X-Tenant-Id") @NotNull UUID tenantId, @Valid InterestPolicyRequest request) {
        return service.setInterestPolicy(tenantId, request.monthlyRate());
    }
    @GET @Path("/admin/summary")
    public Object summary(@HeaderParam("X-Tenant-Id") @NotNull UUID tenantId) { return service.adminSummary(tenantId); }

    public record TopUpRequest(@NotNull UUID customerId, @NotNull @DecimalMin("0.01") BigDecimal amount) {}
    public record QuoteRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @Min(1) @Max(24) int installments) {}
    public record PurchaseRequest(@NotNull UUID customerId, @NotBlank @Size(max=64) String merchantCategory,
                                  @NotNull @DecimalMin("0.01") BigDecimal amount, @Min(1) @Max(24) int installments) {}
    public record InterestPolicyRequest(@NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal monthlyRate) {}
}
