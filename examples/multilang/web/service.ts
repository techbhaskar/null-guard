export function unsafe(name: string | null) { return name.length; }
export function guarded(name: string | null) { return name?.length ?? 0; }
