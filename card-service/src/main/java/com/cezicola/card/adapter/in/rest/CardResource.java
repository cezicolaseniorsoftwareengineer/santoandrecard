package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.CardNotFoundException;
import com.cezicola.card.application.CardService;
import com.cezicola.card.application.CreateCardCommand;
import com.cezicola.card.domain.CardProduct;
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
import java.util.List;
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
                new CreateCardCommand(caller.tenantId(), request.customerId(), request.creditLimit(),
                        CardProduct.PLATINUM, idempotencyKey));
        return Response.created(URI.create("/api/v1/cards/" + card.id())).entity(CardResponse.from(card)).build();
    }

    /**
     * Self-service issuance for the cardholder, available at any hour.
     *
     * <p>The request carries no body: the customer comes from the verified token
     * and the limit from the issuer's policy, so there is nothing here for a
     * caller to choose. Repeating the call returns the card already issued, with
     * 200 rather than 201, instead of issuing a second one.
     */
    @POST
    @Path("/self-service")
    // The class requires JSON, but this request has no body and a browser sends
    // no Content-Type for one. Inheriting the class-level @Consumes would answer
    // every real call with 415.
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed(Roles.CUSTOMER)
    @Operation(summary = "Issue the calling customer's own card")
    public Response issueForSelf() {
        var existing = service.listForCustomer(caller.tenantId(), caller.customerId());
        var card = service.issueForCustomer(caller.tenantId(), caller.customerId());
        boolean alreadyHeld = existing.stream().anyMatch(held -> held.id().equals(card.id()));
        return Response.status(alreadyHeld ? Response.Status.OK : Response.Status.CREATED)
                .location(URI.create("/api/v1/cards/" + card.id()))
                .entity(CardResponse.from(card))
                .build();
    }

    @GET
    @RolesAllowed(Roles.CUSTOMER)
    @Operation(summary = "List the calling customer's cards")
    public List<CardResponse> list() {
        return service.listForCustomer(caller.tenantId(), caller.customerId())
                .stream().map(CardResponse::from).toList();
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
