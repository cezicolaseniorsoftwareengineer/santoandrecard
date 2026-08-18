package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.AuthorizationService;
import com.cezicola.card.application.IdempotentOperation;
import com.cezicola.card.domain.Authorization;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The authorization lifecycle: hold, then capture or release.
 *
 * <p>A hold is placed by the issuer against the cardholder's funds and settled
 * by the merchant. Capture and reversal are therefore separate calls rather than
 * fields on the first one — at the moment of authorization nobody knows yet
 * which will happen.
 */
@Path("/api/v1/authorizations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Authorizations")
public class AuthorizationResource {

    private final AuthorizationService service;
    private final AuthenticatedCaller caller;
    private final IdempotentOperation idempotent;

    public AuthorizationResource(AuthorizationService service, AuthenticatedCaller caller,
                                 IdempotentOperation idempotent) {
        this.service = service;
        this.caller = caller;
        this.idempotent = idempotent;
    }

    @POST
    @RolesAllowed(Roles.CUSTOMER)
    @Operation(summary = "Hold funds for a merchant")
    public Response authorize(@HeaderParam("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                              @Valid AuthorizeRequest request) {
        AuthorizationView view = idempotent.execute(caller.tenantId(), "authorization", key, request,
                AuthorizationView.class,
                () -> AuthorizationView.from(service.authorize(caller.tenantId(), caller.customerId(),
                        request.cardId(), request.merchantCategory(), request.amount())));
        return Response.created(URI.create("/api/v1/authorizations/" + view.id())).entity(view).build();
    }

    /**
     * Captures up to what was held. Omitting the amount captures it in full,
     * which is the common case; stating a smaller one is a partial capture.
     */
    @POST
    @Path("/{id}/capture")
    @RolesAllowed(Roles.CUSTOMER)
    @Operation(summary = "Capture an authorized hold")
    public AuthorizationView capture(@HeaderParam("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                                     @PathParam("id") UUID id,
                                     @Valid CaptureRequest request) {
        BigDecimal requested = request == null || request.amount() == null
                ? service.get(caller.tenantId(), id).amount()
                : request.amount();
        return idempotent.execute(caller.tenantId(), "capture", key, new CaptureRequest(requested),
                AuthorizationView.class,
                () -> AuthorizationView.from(service.capture(caller.tenantId(), id, requested)));
    }

    @POST
    @Path("/{id}/reversal")
    @RolesAllowed(Roles.CUSTOMER)
    @Operation(summary = "Release an authorized hold")
    public AuthorizationView reverse(@HeaderParam("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                                     @PathParam("id") UUID id) {
        return idempotent.execute(caller.tenantId(), "reversal", key, id, AuthorizationView.class,
                () -> AuthorizationView.from(service.reverse(caller.tenantId(), id)));
    }

    @GET
    @RolesAllowed(Roles.CUSTOMER)
    @Operation(summary = "List the calling customer's authorizations")
    public List<AuthorizationView> list(@QueryParam("limit") @DefaultValue("50") int limit) {
        return service.listForCustomer(caller.tenantId(), caller.customerId(), limit)
                .stream().map(AuthorizationView::from).toList();
    }

    @GET
    @Path("/{id}")
    @RolesAllowed(Roles.CUSTOMER)
    @Operation(summary = "Read one authorization")
    public AuthorizationView get(@PathParam("id") UUID id) {
        Authorization authorization = service.get(caller.tenantId(), id);
        if (!authorization.customerId().equals(caller.customerId())) {
            // The same 404 as a non-existent hold: the response must not reveal
            // that this identifier belongs to somebody else.
            throw new com.cezicola.card.application.AuthorizationNotFoundException(id);
        }
        return AuthorizationView.from(authorization);
    }

    public record AuthorizeRequest(
            @NotNull UUID cardId,
            @NotBlank @Size(max = 64) String merchantCategory,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    public record CaptureRequest(
            @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    public record AuthorizationView(UUID id, UUID customerId, UUID cardId, String merchantCategory,
                                    BigDecimal amount, BigDecimal capturedAmount, BigDecimal releasedAmount,
                                    String status, Instant createdAt, Instant expiresAt, Instant settledAt) {

        static AuthorizationView from(Authorization authorization) {
            return new AuthorizationView(authorization.id(), authorization.customerId(), authorization.cardId(),
                    authorization.merchantCategory(), authorization.amount(), authorization.capturedAmount(),
                    authorization.releasedAmount(), authorization.status().name(),
                    authorization.createdAt(), authorization.expiresAt(), authorization.settledAt());
        }
    }
}
