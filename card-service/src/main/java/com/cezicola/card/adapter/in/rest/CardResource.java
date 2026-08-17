package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.CardNotFoundException;
import com.cezicola.card.application.CardService;
import com.cezicola.card.application.CreateCardCommand;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;
import java.util.UUID;

@Path("/api/v1/cards")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Cards")
public class CardResource {
    private final CardService service;
    private final AuthenticatedCaller caller;
    private final SecurityIdentity identity;

    public CardResource(CardService service, AuthenticatedCaller caller, SecurityIdentity identity) {
        this.service = service;
        this.caller = caller;
        this.identity = identity;
    }

    /** Issuing a card is an issuer action, so it is restricted to the admin role. */
    @POST
    @RolesAllowed(Roles.ADMIN)
    @Operation(summary = "Create a credit card idempotently")
    public Response create(@HeaderParam("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
                           @Valid CreateCardRequest request) {
        var card = service.create(
                new CreateCardCommand(caller.tenantId(), request.customerId(), request.creditLimit(), idempotencyKey));
        return Response.created(URI.create("/api/v1/cards/" + card.id())).entity(CardResponse.from(card)).build();
    }

    @GET
    @Path("/{id}")
    @RolesAllowed({Roles.CUSTOMER, Roles.ADMIN})
    @Operation(summary = "Get a credit card by identifier")
    public CardResponse get(@PathParam("id") UUID id) {
        var card = service.get(caller.tenantId(), id);
        if (!identity.hasRole(Roles.ADMIN) && !card.customerId().equals(caller.customerId())) {
            // Report the same 404 as a non-existent card so the response does not
            // reveal that another customer holds this identifier.
            throw new CardNotFoundException(id);
        }
        return CardResponse.from(card);
    }
}
