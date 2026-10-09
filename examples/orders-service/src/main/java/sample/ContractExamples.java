package sample;

class ContractExamples {
    @NonNull String brokenReturn() { return null; }
    @NonNull String constant() { return "ready"; }
    @Nullable String nullable() { return null; }
    String required(@NonNull String value) { return value; }
    String unsafeParameter(String value) { return value.trim(); }
    String guardedParameter(String value) {
        if (value == null) return "";
        return value.trim();
    }
    String badArgument() { return required(nullable()); }
    String goodArgument() { return required(constant()); }
    String guardedArgument(String value) {
        if (value == null) return "";
        return required(value);
    }
    String badConsumption() { String result = nullable(); return result.trim(); }
    String goodConsumption() { String result = nullable(); if (result == null) return ""; return result.trim(); }
    String chainedBadConsumption() { return nullable().trim(); }
    String chainedGoodConsumption() { return constant().trim(); }
    String requireNonNullGuard(String value) { java.util.Objects.requireNonNull(value); return required(value); }
    String aliasBadArgument() { String value = null; value = value; return required(value); }
    int primitive(int value) { return value; }
    int primitiveExternal() { return Integer.parseInt("1"); }
}
