class Example {
    int unsafe(String value) { return value.length(); }
    int guarded(String value) { return value == null ? 0 : value.length(); }
}
