public static class Example {
    public static int Unsafe(string? name) => name.Length;
    public static int Guarded(string? name) { if (name is null) return 0; return name.Length; }
}
