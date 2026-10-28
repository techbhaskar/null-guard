package example
func Unsafe(value *int) int { return *value }
func Guarded(value *int) int { if value == nil { return 0 }; return *value }
