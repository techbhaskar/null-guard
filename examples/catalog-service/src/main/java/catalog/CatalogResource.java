package catalog;

// Minimal JAX-RS annotation stand-ins for static-analysis integration tests.
@interface GET {}
@interface Path { String value(); }

public class CatalogResource {
    @GET @Path("/catalog/items")
    public String list() { return "catalog"; }
}

interface UnknownProvider { String value(); }
