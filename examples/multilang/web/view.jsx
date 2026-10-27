export function unsafe() { const model = null; return <div>{model.name}</div>; }
export function guarded(model) { return <div>{model?.name ?? "Unknown"}</div>; }
