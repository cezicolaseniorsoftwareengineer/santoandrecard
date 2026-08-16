package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.application.CardService;
import com.cezicola.card.application.CreateCardCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    public CardResource(CardService service) {
        this.service = service;
    }

    @POST
    @Operation(summary = "Create a credit card idempotently")
    public Response create(@HeaderParam("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
                           @HeaderParam("X-Tenant-Id") @NotNull UUID tenantId,
                           @Valid CreateCardRequest request) {
        var card = service.create(new CreateCardCommand(tenantId, request.customerId(), request.creditLimit(), idempotencyKey));
        return Response.created(URI.create("/api/v1/cards/" + card.id())).entity(CardResponse.from(card)).build();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a credit card by identifier")
    public CardResponse get(@HeaderParam("X-Tenant-Id") @NotNull UUID tenantId, @PathParam("id") UUID id) {
        return CardResponse.from(service.get(tenantId, id));
    }
}
