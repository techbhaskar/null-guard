package sample;

// Minimal annotation fixtures: no running server or Spring dependency required.
@interface GetMapping { String value(); }
@interface NonNull {}
@interface Nullable {}

public class OrderController {
    private final Services.Risky service = new Services.Risky();
    @GetMapping("/orders/one") public String getOne() { return service.load(); }
    @GetMapping("/orders/two") public String getTwo() { return service.load(); }
    @GetMapping("/orders/three") public String getThree() { return service.load(); }
    @GetMapping("/orders/four") public String getFour() { return service.load(); }
    @GetMapping("/orders/five") public String getFive() { return service.load(); }
}

class Services {
    static class Risky {
        public String load() { return new OrderRepository().find(); }
    }
}

class ShadowServices {
    static class Risky {
        public String load() { return "safe"; }
    }
}

class OrderRepository {
    @Nullable public String find() {
        String a = null; a.trim();
        String b = null; b.trim();
        String c = null; c.trim();
        String d = null; d.trim();
        return null;
    }
}
