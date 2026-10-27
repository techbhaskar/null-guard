fn unsafe_unwrap(value: Option<i32>) -> i32 { value.unwrap() }
fn guarded(value: Option<i32>) -> i32 { if value.is_none() { return 0; } value.unwrap() }
fn main() {}
