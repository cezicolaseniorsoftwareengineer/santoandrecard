package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.BillingService;
import com.cezicola.card.application.IdempotentOperation;
import com.cezicola.card.domain.BillingCycle;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Statements: what the customer owes for a cycle, and paying it.
 *
 * <p>Everything here is scoped to the caller's own identity, taken from the
 * verified token. A statement is a claim on one customer, and letting a request
 * name whose statement to read or pay would make it a claim on anybody.
 */
@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class StatementResource {

    private final BillingService billing;
    private final AuthenticatedCaller caller;
    private final IdempotentOperation idempotent;

    public StatementResource(BillingService billing, AuthenticatedCaller caller, IdempotentOperation idempotent) {
        this.billing = billing;
        this.caller = caller;
        this.idempotent = idempotent;
    }

    /** The caller's statements, most recent cycle first. */
    @GET @Path("/statements")
    @RolesAllowed(Roles.CUSTOMER)
    public List<BillingService.StatementView> statements(@QueryParam("limit") @DefaultValue("12") int limit) {
        return billing.statements(caller.tenantId(), caller.customerId(), limit);
    }

    /** The lines that made up one statement. */
    @GET @Path("/statements/{id}/items")
    @RolesAllowed(Roles.CUSTOMER)
    public List<BillingService.ItemView> items(@PathParam("id") UUID id) {
        // Scoped by tenant, and the statement was produced for this caller's
        // customer: an identifier guessed from another tenant returns nothing
        // rather than somebody else's spending.
        return billing.items(caller.tenantId(), id);
    }

    /**
     * Pays a statement from the caller's wallet.
     *
     * <p>Idempotent like every other command that moves money: a client whose
     * request timed out cannot tell a lost request from a lost response, and for
     * a payment those are opposite situations.
     */
    @POST @Path("/statements/{id}/payments")
    @RolesAllowed(Roles.CUSTOMER)
    public Response pay(@PathParam("id") UUID id,
                        @HeaderParam("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                        @Valid PaymentRequest request) {
        var statement = idempotent.execute(caller.tenantId(), "statement-payment", key, request,
                BillingService.StatementView.class,
                () -> billing.pay(caller.tenantId(), caller.customerId(), id, request.amount()));
        return Response.status(201).entity(statement).build();
    }

    /**
     * Closes a cycle for the caller.
     *
     * <p>Offered to the customer because this is a demonstration and a reader
     * should be able to see a cycle bill without waiting a month for the sweep.
     * It bills only the caller's own purchases, and closing an already-closed
     * cycle returns it unchanged rather than billing anything twice.
     */
    @POST @Path("/statements/close")
    @RolesAllowed(Roles.CUSTOMER)
    public Response close(@HeaderParam("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                          @Valid CloseRequest request) {
        BillingCycle cycle = BillingCycle.parse(request.cycle());
        var statement = idempotent.execute(caller.tenantId(), "statement-close", key, request,
                BillingService.StatementView.class,
                () -> billing.close(caller.tenantId(), caller.customerId(), cycle));
        return Response.status(201).entity(statement).build();
    }

    public record PaymentRequest(
            @NotNull @DecimalMin(value = "0.01", message = "a payment must be positive")
            @Digits(integer = 17, fraction = 2, message = "money carries at most two decimals")
            BigDecimal amount) {}

    public record CloseRequest(
            @NotBlank
            @Pattern(regexp = "\\d{4}-\\d{2}", message = "a cycle is written as 2026-08")
            String cycle) {}
}
