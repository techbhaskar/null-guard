use proc_macro2::Span;
use quote::ToTokens;
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, BTreeSet},
    io::{self, Read},
    path::PathBuf,
};
use syn::{Expr, Item, Pat, Stmt, Type, spanned::Spanned};
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Request {
    protocol_version: String,
    project: String,
    root: String,
    files: Vec<String>,
    max_iterations: Option<usize>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
enum Kind {
    Option,
    Result,
    Sequence,
    Unknown,
}
#[derive(Clone, Debug)]
struct Fact {
    kind: Kind,
    mask: u8,
    origin: String,
    line: usize,
    len: Option<usize>,
}
impl Fact {
    fn unknown() -> Self {
        Self {
            kind: Kind::Unknown,
            mask: 3,
            origin: "No resolved type contract".into(),
            line: 1,
            len: None,
        }
    }
    fn new(kind: Kind, mask: u8, origin: &str, line: usize) -> Self {
        Self {
            kind,
            mask,
            origin: origin.into(),
            line,
            len: None,
        }
    }
}
type State = BTreeMap<String, Fact>;
fn merge(a: &Fact, b: &Fact) -> Fact {
    Fact {
        kind: if a.kind == b.kind {
            a.kind.clone()
        } else {
            Kind::Unknown
        },
        mask: a.mask | b.mask,
        origin: if a.origin == b.origin {
            a.origin.clone()
        } else {
            "Joined control-flow paths".into()
        },
        line: a.line.min(b.line),
        len: if a.len == b.len { a.len } else { None },
    }
}
fn join(a: &State, b: &State) -> State {
    let mut r = State::new();
    for k in a.keys().chain(b.keys()) {
        r.insert(
            k.clone(),
            merge(
                a.get(k).unwrap_or(&Fact::unknown()),
                b.get(k).unwrap_or(&Fact::unknown()),
            ),
        );
    }
    r
}
fn masks(s: &State) -> Vec<(String, Kind, u8, Option<usize>)> {
    s.iter()
        .map(|(k, v)| (k.clone(), v.kind.clone(), v.mask, v.len))
        .collect()
}
struct Analyzer {
    r: Request,
    file: String,
    lines: Vec<String>,
    current: String,
    findings: BTreeMap<String, Value>,
    diagnostics: BTreeSet<String>,
    unresolved: BTreeSet<String>,
    functions: BTreeMap<String, (String, syn::ItemFn)>,
    summaries: BTreeMap<String, Fact>,
    module: String,
    shadow: BTreeSet<String>,
    shadows: BTreeMap<String, BTreeSet<String>>,
    failed: usize,
    analyzed: usize,
    budget: bool,
    report: bool,
    returns: Vec<Fact>,
    loop_exits: Vec<Vec<State>>,
    unsupported: usize,
}
impl Analyzer {
    fn standard(&self, path: &syn::Path, name: &str) -> bool {
        let parts: Vec<String> = path.segments.iter().map(|s| s.ident.to_string()).collect();
        if parts.len() == 1 {
            return parts[0] == name && !self.shadow.contains(name);
        };
        if parts
            .first()
            .map(|p| self.shadow.contains(p))
            .unwrap_or(false)
        {
            return false;
        }
        let written = parts.join("::");
        match name {
            "Some" | "None" => {
                written == format!("std::option::Option::{name}")
                    || written == format!("core::option::Option::{name}")
                    || (!self.shadow.contains("Option") && written == format!("Option::{name}"))
            }
            "Ok" | "Err" => {
                written == format!("std::result::Result::{name}")
                    || written == format!("core::result::Result::{name}")
                    || (!self.shadow.contains("Result") && written == format!("Result::{name}"))
            }
            _ => {
                written == format!("std::option::{name}")
                    || written == format!("core::option::{name}")
                    || written == format!("std::result::{name}")
                    || written == format!("core::result::{name}")
                    || written == format!("std::vec::{name}")
            }
        }
    }
    fn imports(&mut self, tree: &syn::UseTree, prefix: Vec<String>) {
        const NAMES: [&str; 9] = [
            "Option", "Result", "Some", "None", "Ok", "Err", "Vec", "std", "core",
        ];
        match tree {
            syn::UseTree::Path(path) => {
                let mut prefix = prefix;
                prefix.push(path.ident.to_string());
                self.imports(&path.tree, prefix)
            }
            syn::UseTree::Group(group) => {
                for item in &group.items {
                    self.imports(item, prefix.clone())
                }
            }
            syn::UseTree::Name(name) => {
                let imported = name.ident.to_string();
                let mut full = prefix;
                full.push(imported.clone());
                if NAMES.contains(&imported.as_str()) {
                    let path: syn::Path = syn::parse_str(&full.join("::")).unwrap();
                    if !self.standard(&path, &imported) {
                        self.shadow.insert(imported);
                    }
                }
            }
            syn::UseTree::Rename(rename) => {
                let imported = rename.rename.to_string();
                let mut full = prefix;
                full.push(rename.ident.to_string());
                if NAMES.contains(&imported.as_str()) {
                    let path: syn::Path = syn::parse_str(&full.join("::")).unwrap();
                    if !self.standard(&path, &imported) {
                        self.shadow.insert(imported);
                    }
                }
            }
            syn::UseTree::Glob(_) => {
                let path = prefix.join("::");
                let root = prefix.first().map(String::as_str).unwrap_or("");
                let established = !self.shadow.contains(root)
                    && ([
                        "std::option::Option",
                        "core::option::Option",
                        "std::result::Result",
                        "core::result::Result",
                    ]
                    .contains(&path.as_str())
                        || path.starts_with("std::prelude::")
                        || path.starts_with("core::prelude::"));
                if !established {
                    self.shadow.extend(NAMES.map(str::to_owned));
                    self.unresolved.insert(format!(
                        "{}:unresolved wildcard import standard identities",
                        self.file
                    ));
                    self.unsupported += 1;
                }
            }
        }
    }
    fn typed(&self, t: &Type) -> Fact {
        if let Type::Reference(r) = t {
            return self.typed(&r.elem);
        };
        if let Type::Path(p) = t {
            for (name, kind) in [
                ("Option", Kind::Option),
                ("Result", Kind::Result),
                ("Vec", Kind::Sequence),
            ] {
                if self.standard(&p.path, name) {
                    return Fact::new(kind, 3, "Declared standard type", t.span().start().line);
                }
            }
        };
        if matches!(t, Type::Array(_) | Type::Slice(_)) {
            return Fact::new(
                Kind::Sequence,
                3,
                "Declared sequence",
                t.span().start().line,
            );
        };
        Fact::unknown()
    }
    fn emit(&mut self, span: Span, v: &Fact, rule: &str, message: &str) {
        if !self.report {
            return;
        };
        let p = span.start();
        let col = self
            .lines
            .get(p.line.saturating_sub(1))
            .map(|s| s.get(..p.column.min(s.len())).unwrap_or("").chars().count() + 1)
            .unwrap_or(1);
        let f = json!({"ruleId":format!("NG-RS-{rule}"),"language":"rust","severity":"warning","confidence":"experimental","methodId":format!("{}#{}",self.file,self.current),"message":message,"location":{"path":self.file,"startLine":p.line.max(1),"startColumn":col},"evidence":[v.origin,format!("Standard value state mask: {}",v.mask)],"relatedLocations":[{"path":self.file,"startLine":v.line.max(1),"startColumn":1}]});
        self.findings.insert(
            format!("{}:{}:{}:{}:{}", self.file, p.line, col, rule, message),
            f,
        );
    }
    fn bind(&self, p: &Pat, v: &Fact, s: &mut State) {
        match p {
            Pat::Ident(i) => {
                s.retain(|key, _| !key.starts_with("$bound:"));
                s.insert(i.ident.to_string(), v.clone());
            }
            Pat::Type(t) => self.bind(&t.pat, v, s),
            Pat::Tuple(t) => {
                for e in &t.elems {
                    self.bind(e, &Fact::unknown(), s)
                }
            }
            Pat::TupleStruct(t) => {
                for e in &t.elems {
                    self.bind(e, &Fact::unknown(), s)
                }
            }
            _ => {}
        }
    }
    fn refine_pattern(&self, p: &Pat, subject: &Expr, s: &State, truth: bool) -> State {
        let mut out = s.clone();
        if let Expr::Path(v) = subject {
            let name = v.path.to_token_stream().to_string().replace(' ', "");
            if let Some(old) = s.get(&name) {
                let (mut mask, mut matched) = (3, false);
                match p {
                    Pat::TupleStruct(p) => {
                        if old.kind == Kind::Option && self.standard(&p.path, "Some")
                            || old.kind == Kind::Result && self.standard(&p.path, "Ok")
                        {
                            mask = 2;
                            matched = true;
                        } else if old.kind == Kind::Result && self.standard(&p.path, "Err") {
                            mask = 1;
                            matched = true;
                        }
                    }
                    Pat::Path(p) => {
                        if old.kind == Kind::Option && self.standard(&p.path, "None") {
                            mask = 1;
                            matched = true;
                        }
                    }
                    _ => {}
                }
                if matched {
                    let mut f = old.clone();
                    f.mask &= if truth { mask } else { 3 ^ mask };
                    f.origin = "Selected standard enum pattern".into();
                    out.insert(name, f);
                }
            }
        }
        if truth {
            self.bind(p, &Fact::unknown(), &mut out)
        }
        out
    }
    fn refine(&self, test: &Expr, s: &State, truth: bool) -> State {
        match test {
            Expr::Paren(p) => self.refine(&p.expr, s, truth),
            Expr::Unary(u) if matches!(u.op, syn::UnOp::Not(_)) => self.refine(&u.expr, s, !truth),
            Expr::Let(l) => self.refine_pattern(&l.pat, &l.expr, s, truth),
            Expr::Binary(b) if matches!(b.op, syn::BinOp::And(_) | syn::BinOp::Or(_)) => {
                let all = matches!(b.op, syn::BinOp::And(_)) == truth;
                if all {
                    self.refine(&b.right, &self.refine(&b.left, s, truth), truth)
                } else {
                    join(
                        &self.refine(&b.left, s, truth),
                        &self.refine(&b.right, s, truth),
                    )
                }
            }
            Expr::Binary(b) if matches!(b.op, syn::BinOp::Lt(_) | syn::BinOp::Ge(_)) => {
                let mut out = s.clone();
                if truth == matches!(b.op, syn::BinOp::Lt(_)) {
                    if let (Expr::Path(index), Expr::MethodCall(length)) =
                        (b.left.as_ref(), b.right.as_ref())
                    {
                        if length.method == "len" && length.args.is_empty() {
                            if let Expr::Path(sequence) = length.receiver.as_ref() {
                                let seq =
                                    sequence.path.to_token_stream().to_string().replace(' ', "");
                                if s.get(&seq)
                                    .map(|v| v.kind == Kind::Sequence)
                                    .unwrap_or(false)
                                {
                                    let key = format!(
                                        "$bound:{}:{}",
                                        seq,
                                        index.path.to_token_stream().to_string().replace(' ', "")
                                    );
                                    out.insert(
                                        key,
                                        Fact::new(
                                            Kind::Unknown,
                                            2,
                                            "Index bounded by sequence length",
                                            test.span().start().line,
                                        ),
                                    );
                                }
                            }
                        }
                    }
                }
                out
            }
            Expr::MethodCall(m) => {
                let mut out = s.clone();
                if let Expr::Path(p) = m.receiver.as_ref() {
                    let name = p.path.to_token_stream().to_string().replace(' ', "");
                    if let Some(old) = s.get(&name) {
                        let method = m.method.to_string();
                        let valid = (old.kind == Kind::Option
                            && ["is_some", "is_none"].contains(&method.as_str()))
                            || (old.kind == Kind::Result
                                && ["is_ok", "is_err"].contains(&method.as_str()));
                        if valid {
                            let mut f = old.clone();
                            let yes = method == "is_some" || method == "is_ok";
                            f.mask &= if truth == yes { 2 } else { 1 };
                            f.origin = "Selected enum guard branch".into();
                            out.insert(name, f);
                        }
                    }
                }
                out
            }
            _ => s.clone(),
        }
    }
    fn eval(&mut self, e: &Expr, s: &mut State) -> Fact {
        let line = e.span().start().line;
        match e {
            Expr::Path(p) => {
                let name = p.path.to_token_stream().to_string().replace(' ', "");
                if let Some(v) = s.get(&name) {
                    return v.clone();
                };
                if self.standard(&p.path, "None") {
                    return Fact::new(Kind::Option, 1, "None variant", line);
                };
                Fact::unknown()
            }
            Expr::Call(c) => {
                s.retain(|key, _| !key.starts_with("$bound:"));
                for arg in &c.args {
                    self.eval(arg, s);
                }
                if let Expr::Path(p) = c.func.as_ref() {
                    if p.path
                        .get_ident()
                        .map(|name| s.contains_key(&name.to_string()))
                        .unwrap_or(false)
                    {
                        self.unresolved
                            .insert(format!("{}:{line}:local callable", self.file));
                        return Fact::unknown();
                    }

                    for (name, kind, mask) in [
                        ("Some", Kind::Option, 2),
                        ("None", Kind::Option, 1),
                        ("Ok", Kind::Result, 2),
                        ("Err", Kind::Result, 1),
                    ] {
                        if self.standard(&p.path, name) {
                            return Fact::new(kind, mask, "Standard enum constructor", line);
                        }
                    }
                    let name = p.path.to_token_stream().to_string().replace(' ', "");
                    let key = format!("{}:{}", self.module, name);
                    if !s.contains_key(&name) {
                        if let Some(v) = self.summaries.get(&key) {
                            return v.clone();
                        }
                    }
                    self.unresolved
                        .insert(format!("{}:{line}:{name}", self.file));
                }
                Fact::unknown()
            }
            Expr::MethodCall(m) => {
                let receiver = self.eval(&m.receiver, s);
                for a in &m.args {
                    self.eval(a, s);
                }
                let name = m.method.to_string();
                if ![
                    "len",
                    "get",
                    "is_some",
                    "is_none",
                    "is_ok",
                    "is_err",
                    "unwrap",
                    "expect",
                    "unwrap_or",
                ]
                .contains(&name.as_str())
                {
                    s.retain(|key, _| !key.starts_with("$bound:"));
                }
                if receiver.kind == Kind::Option && ["take", "replace"].contains(&name.as_str()) {
                    if let Expr::Path(p) = m.receiver.as_ref() {
                        if let Some(key) = p.path.get_ident() {
                            s.insert(
                                key.to_string(),
                                Fact::new(
                                    Kind::Option,
                                    if name == "take" { 1 } else { 2 },
                                    "Option mutated by take/replace",
                                    line,
                                ),
                            );
                        }
                    }
                    return receiver;
                }
                if ["unwrap", "expect"].contains(&name.as_str())
                    && matches!(receiver.kind, Kind::Option | Kind::Result)
                {
                    if receiver.mask & 1 != 0 {
                        self.emit(
                            m.span(),
                            &receiver,
                            "001",
                            if receiver.mask == 1 {
                                "Definite panic: unwrap/expect of None or Err"
                            } else {
                                "Possible panic: unwrap/expect without a proven Some/Ok guard"
                            },
                        );
                    }
                    return Fact::unknown();
                };
                if name == "get" && receiver.kind == Kind::Sequence {
                    let index = m.args.first().and_then(integer);
                    let mask = match (receiver.len, index) {
                        (Some(n), Some(i)) => {
                            if i < n {
                                2
                            } else {
                                1
                            }
                        }
                        _ => 3,
                    };
                    return Fact::new(
                        Kind::Option,
                        mask,
                        "Checked sequence get returns Option",
                        line,
                    );
                };
                if [
                    "is_some",
                    "is_none",
                    "is_ok",
                    "is_err",
                    "len",
                    "unwrap_or",
                    "unwrap_or_else",
                ]
                .contains(&name.as_str())
                {
                    return Fact::unknown();
                };
                if matches!(receiver.kind, Kind::Option | Kind::Result) {
                    if let Expr::Path(path) = m.receiver.as_ref() {
                        if let Some(id) = path.path.get_ident() {
                            let mut unknown = receiver.clone();
                            unknown.mask = 3;
                            unknown.origin = "Unresolved enum method may mutate state".into();
                            s.insert(id.to_string(), unknown);
                        }
                    }
                }
                self.unresolved
                    .insert(format!("{}:{line}:method {name}", self.file));
                Fact::unknown()
            }
            Expr::Try(t) => {
                let value = self.eval(&t.expr, s);
                if matches!(value.kind, Kind::Option | Kind::Result) {
                    if value.mask & 1 != 0 {
                        let mut invalid = value.clone();
                        invalid.mask = 1;
                        self.returns.push(invalid)
                    }
                    if let Expr::Path(p) = t.expr.as_ref() {
                        let name = p.path.to_token_stream().to_string().replace(' ', "");
                        let mut valid = value;
                        valid.mask = 2;
                        s.insert(name, valid);
                    }
                }
                Fact::unknown()
            }
            Expr::Range(range) => {
                if let Some(start) = &range.start {
                    self.eval(start, s);
                }
                if let Some(end) = &range.end {
                    self.eval(end, s);
                }
                Fact::unknown()
            }
            Expr::Index(i) => {
                let base = self.eval(&i.expr, s);
                self.eval(&i.index, s);
                if base.kind == Kind::Sequence {
                    let key = format!(
                        "$bound:{}:{}",
                        i.expr.to_token_stream().to_string().replace(' ', ""),
                        i.index.to_token_stream().to_string().replace(' ', "")
                    );
                    let range_safe = if let Expr::Range(range) = i.index.as_ref() {
                        if range.start.is_none() && range.end.is_none() {
                            true
                        } else if let Some(length) = base.len {
                            let start = range.start.as_deref().map(integer).unwrap_or(Some(0));
                            let end = range.end.as_deref().map(integer).unwrap_or(Some(length));
                            match (start, end) {
                                (Some(start), Some(end)) => {
                                    let end = if matches!(range.limits, syn::RangeLimits::Closed(_))
                                    {
                                        end.checked_add(1)
                                    } else {
                                        Some(end)
                                    };
                                    end.map(|end| start <= end && end <= length)
                                        .unwrap_or(false)
                                }
                                _ => false,
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    };
                    let safe = range_safe
                        || matches!((base.len,integer(&i.index)),(Some(n),Some(k)) if k<n)
                        || s.get(&key).map(|f| f.mask == 2).unwrap_or(false);
                    if !safe {
                        self.emit(
                            i.span(),
                            &base,
                            "002",
                            "Potentially panicking sequence indexing; use get or a proven bound",
                        )
                    }
                } else {
                    self.unresolved
                        .insert(format!("{}:{line}:unresolved index receiver", self.file));
                }
                Fact::unknown()
            }
            Expr::Array(a) => {
                for e in &a.elems {
                    self.eval(e, s);
                }
                let mut f = Fact::new(Kind::Sequence, 2, "Array literal", line);
                f.len = Some(a.elems.len());
                f
            }
            Expr::Reference(r) => self.eval(&r.expr, s),
            Expr::Paren(p) => self.eval(&p.expr, s),
            Expr::Group(g) => self.eval(&g.expr, s),
            Expr::Assign(a) => {
                s.retain(|key, _| !key.starts_with("$bound:"));
                let v = self.eval(&a.right, s);
                if let Expr::Path(p) = a.left.as_ref() {
                    s.insert(
                        p.path.to_token_stream().to_string().replace(' ', ""),
                        v.clone(),
                    );
                }
                v
            }
            Expr::If(i) => {
                self.eval(&i.cond, s);
                let yes = self.refine(&i.cond, s, true);
                let no = self.refine(&i.cond, s, false);
                let (a, x) = self.block(&i.then_branch, yes);
                let (b, y) = if let Some((_, other)) = &i.else_branch {
                    let mut out = no;
                    let v = self.eval(other, &mut out);
                    (Some(out), v)
                } else {
                    (Some(no), Fact::unknown())
                };
                match (a, b) {
                    (Some(a), Some(b)) => *s = join(&a, &b),
                    (Some(a), None) => *s = a,
                    (None, Some(b)) => *s = b,
                    (None, None) => {}
                }
                merge(&x, &y)
            }
            Expr::Match(m) => {
                let subject = self.eval(&m.expr, s);
                let mut states: Option<State> = None;
                let mut value: Option<Fact> = None;
                for arm in &m.arms {
                    let mut entry = self.refine_pattern(&arm.pat, &m.expr, s, true);
                    if let Some((_, g)) = &arm.guard {
                        self.eval(g, &mut entry);
                        entry = self.refine(g, &entry, true);
                    }
                    let f = self.eval(&arm.body, &mut entry);
                    states = Some(if let Some(old) = states {
                        join(&old, &entry)
                    } else {
                        entry
                    });
                    value = Some(if let Some(old) = value {
                        merge(&old, &f)
                    } else {
                        f
                    });
                }
                if let Some(out) = states {
                    *s = out
                };
                let _ = subject;
                value.unwrap_or_else(Fact::unknown)
            }
            Expr::Block(b) => {
                let (out, v) = self.block(&b.block, s.clone());
                if let Some(out) = out {
                    *s = out
                };
                v
            }
            Expr::Binary(b) => {
                if matches!(
                    b.op,
                    syn::BinOp::AddAssign(_)
                        | syn::BinOp::SubAssign(_)
                        | syn::BinOp::MulAssign(_)
                        | syn::BinOp::DivAssign(_)
                        | syn::BinOp::RemAssign(_)
                        | syn::BinOp::BitXorAssign(_)
                        | syn::BinOp::BitAndAssign(_)
                        | syn::BinOp::BitOrAssign(_)
                        | syn::BinOp::ShlAssign(_)
                        | syn::BinOp::ShrAssign(_)
                ) {
                    s.retain(|key, _| !key.starts_with("$bound:"));
                }
                self.eval(&b.left, s);
                let mut right = if matches!(b.op, syn::BinOp::And(_)) {
                    self.refine(&b.left, s, true)
                } else if matches!(b.op, syn::BinOp::Or(_)) {
                    self.refine(&b.left, s, false)
                } else {
                    s.clone()
                };
                self.eval(&b.right, &mut right);
                *s = join(s, &right);
                Fact::unknown()
            }
            Expr::Unary(u) => self.eval(&u.expr, s),
            Expr::Cast(c) => self.eval(&c.expr, s),
            Expr::Await(a) => self.eval(&a.base, s),
            Expr::Return(r) => {
                let value = r
                    .expr
                    .as_ref()
                    .map(|x| self.eval(x, s))
                    .unwrap_or_else(Fact::unknown);
                self.returns.push(value.clone());
                value
            }
            Expr::Loop(l) => {
                self.loop_block(&l.body, None, s);
                Fact::unknown()
            }
            Expr::While(w) => {
                self.loop_block(&w.body, Some(&w.cond), s);
                Fact::unknown()
            }
            Expr::ForLoop(f) => {
                self.eval(&f.expr, s);
                self.bind(&f.pat, &Fact::unknown(), s);
                self.loop_block(&f.body, None, s);
                Fact::unknown()
            }
            Expr::Macro(m) => {
                self.unsupported += 1;
                self.unresolved.insert(format!(
                    "{}:{line}:macro {}",
                    self.file,
                    m.mac.path.to_token_stream()
                ));
                self.diagnostics.insert(
                    "Macro bodies are not expanded; expressions inside macros are not analyzed"
                        .into(),
                );
                Fact::unknown()
            }
            Expr::Field(f) => {
                self.eval(&f.base, s);
                Fact::unknown()
            }
            Expr::Tuple(t) => {
                for e in &t.elems {
                    self.eval(e, s);
                }
                Fact::unknown()
            }
            Expr::Closure(_) => {
                self.unsupported += 1;
                self.diagnostics
                    .insert("Closure bodies are not yet lowered".into());
                Fact::unknown()
            }
            Expr::Lit(_) => Fact::unknown(),
            _ => {
                self.unresolved
                    .insert(format!("{}:{line}:unsupported expression", self.file));
                Fact::unknown()
            }
        }
    }
    fn loop_block(&mut self, body: &syn::Block, test: Option<&Expr>, state: &mut State) {
        let head = state.clone();
        let mut previous = head.clone();
        let mut converged = false;
        self.loop_exits.push(vec![]);
        let reporting = self.report;
        self.report = false;
        for _ in 0..self.r.max_iterations.unwrap_or(64).clamp(1, 64) {
            let entry = if let Some(t) = test {
                self.eval(t, &mut previous);
                self.refine(t, &previous, true)
            } else {
                previous.clone()
            };
            let (body, _) = self.block(body, entry);
            let next = join(&head, &body.unwrap_or(previous.clone()));
            if masks(&next) == masks(&previous) {
                converged = true;
                break;
            };
            previous = next;
        }
        if !converged {
            self.budget = true
        };
        self.report = reporting;
        if reporting {
            let entry = if let Some(test) = test {
                self.eval(test, &mut previous);
                self.refine(test, &previous, true)
            } else {
                previous.clone()
            };
            self.block(body, entry);
        }
        *state = join(&head, &previous);
        for exit in self.loop_exits.pop().unwrap_or_default() {
            *state = join(state, &exit)
        }
    }
    fn block(&mut self, block: &syn::Block, mut state: State) -> (Option<State>, Fact) {
        let original = state.clone();
        let mut declared = BTreeSet::new();
        let original_shadow = self.shadow.clone();
        for statement in &block.stmts {
            if let Stmt::Item(item) = statement {
                let name = match item {
                    Item::Type(i) => Some(&i.ident),
                    Item::Struct(i) => Some(&i.ident),
                    Item::Enum(i) => Some(&i.ident),
                    Item::Const(i) => Some(&i.ident),
                    Item::Static(i) => Some(&i.ident),
                    Item::Fn(i) => Some(&i.sig.ident),
                    _ => None,
                };
                if let Some(name) = name {
                    self.shadow.insert(name.to_string());
                    if matches!(item, Item::Fn(_)) {
                        state.insert(
                            name.to_string(),
                            Fact::new(
                                Kind::Unknown,
                                2,
                                "Unresolved local function",
                                item.span().start().line,
                            ),
                        );
                        declared.insert(name.to_string());
                        self.unresolved.insert(format!(
                            "{}:{}:local function body not resolved",
                            self.file,
                            item.span().start().line
                        ));
                        self.unsupported += 1;
                    }
                }
            }
        }
        let mut last = Fact::unknown();
        for stmt in &block.stmts {
            match stmt {
                Stmt::Local(l) => {
                    struct Bindings<'a>(&'a mut BTreeSet<String>);
                    impl<'ast> syn::visit::Visit<'ast> for Bindings<'_> {
                        fn visit_pat_ident(&mut self, p: &'ast syn::PatIdent) {
                            self.0.insert(p.ident.to_string());
                            syn::visit::visit_pat_ident(self, p);
                        }
                    }
                    syn::visit::Visit::visit_pat(&mut Bindings(&mut declared), &l.pat);
                    state.retain(|key, _| !key.starts_with("$bound:"));
                    if let Some(init) = &l.init {
                        let v = self.eval(&init.expr, &mut state);
                        if let Some((_, diverge)) = &init.diverge {
                            let mut failed = state.clone();
                            self.eval(diverge, &mut failed);
                            state = self.refine_pattern(&l.pat, &init.expr, &state, true);
                        }
                        let declared = if let Pat::Type(t) = &l.pat {
                            self.typed(&t.ty)
                        } else {
                            Fact::unknown()
                        };
                        self.bind(
                            &l.pat,
                            if v.kind == Kind::Unknown {
                                &declared
                            } else {
                                &v
                            },
                            &mut state,
                        );
                    } else {
                        self.bind(&l.pat, &Fact::unknown(), &mut state);
                    }
                }
                Stmt::Expr(e, _) => {
                    last = self.eval(e, &mut state);
                    if matches!(e, Expr::Return(_)) {
                        self.shadow = original_shadow;
                        return (None, last);
                    };
                    if matches!(e, Expr::Break(_) | Expr::Continue(_)) {
                        if let Some(exits) = self.loop_exits.last_mut() {
                            exits.push(state.clone())
                        };
                        self.shadow = original_shadow;
                        return (None, last);
                    }
                }
                Stmt::Macro(m) => {
                    self.unsupported += 1;
                    self.unresolved.insert(format!(
                        "{}:{}:macro {}",
                        self.file,
                        m.span().start().line,
                        m.mac.path.to_token_stream()
                    ));
                }
                Stmt::Item(_) => {}
            }
        }
        for name in declared {
            if let Some(old) = original.get(&name) {
                state.insert(name, old.clone());
            } else {
                state.remove(&name);
            }
        }
        self.shadow = original_shadow;
        (Some(state), last)
    }
    fn analyze_fn(&mut self, file: &str, function: &syn::ItemFn, report: bool) -> Fact {
        self.shadow = self.shadows.get(file).cloned().unwrap_or_default();
        for parameter in &function.sig.generics.params {
            if let syn::GenericParam::Type(parameter) = parameter {
                self.shadow.insert(parameter.ident.to_string());
            }
        }
        self.file = file.into();
        self.module = file.into();
        self.current = function.sig.ident.to_string();
        self.report = report;
        self.lines = std::fs::read_to_string(PathBuf::from(&self.r.root).join(file))
            .unwrap_or_default()
            .lines()
            .map(str::to_owned)
            .collect();
        self.returns.clear();
        let mut state = State::new();
        for arg in &function.sig.inputs {
            if let syn::FnArg::Typed(p) = arg {
                let v = self.typed(&p.ty);
                self.bind(&p.pat, &v, &mut state)
            }
        }
        let (out, tail) = self.block(&function.block, state);
        if out.is_some() {
            self.returns.push(tail)
        }
        let mut result = self.returns.first().cloned().unwrap_or_else(Fact::unknown);
        for v in self.returns.iter().skip(1) {
            result = merge(&result, v)
        }
        result
    }
    fn run(&mut self) -> Value {
        let mut parsed = vec![];
        for file in self.r.files.clone() {
            match std::fs::read_to_string(PathBuf::from(&self.r.root).join(&file))
                .map_err(|e| e.to_string())
                .and_then(|text| syn::parse_file(&text).map_err(|e| e.to_string()))
            {
                Ok(tree) => {
                    self.shadow.clear();
                    self.file = file.clone();
                    // Item bindings are visible throughout a Rust module, including
                    // bindings declared after an import or a function definition.
                    for item in &tree.items {
                        let name = match item {
                            Item::Struct(i) => Some(&i.ident),
                            Item::Enum(i) => Some(&i.ident),
                            Item::Type(i) => Some(&i.ident),
                            Item::Mod(i) => Some(&i.ident),
                            Item::Const(i) => Some(&i.ident),
                            Item::Static(i) => Some(&i.ident),
                            Item::Fn(i) => Some(&i.sig.ident),
                            Item::ExternCrate(i) => {
                                Some(i.rename.as_ref().map(|(_, name)| name).unwrap_or(&i.ident))
                            }
                            _ => None,
                        };
                        if let Some(name) = name {
                            self.shadow.insert(name.to_string());
                        }
                    }
                    self.analyzed += 1;
                    for item in &tree.items {
                        match item {
                            Item::Struct(s) => {
                                self.shadow.insert(s.ident.to_string());
                            }
                            Item::Enum(e) => {
                                self.shadow.insert(e.ident.to_string());
                            }
                            Item::Use(u) => self.imports(&u.tree, vec![]),
                            Item::Type(alias) => {
                                self.shadow.insert(alias.ident.to_string());
                            }
                            Item::Const(value) => {
                                self.shadow.insert(value.ident.to_string());
                            }
                            Item::Static(value) => {
                                self.shadow.insert(value.ident.to_string());
                            }
                            Item::ExternCrate(value) => {
                                self.shadow.insert(
                                    value
                                        .rename
                                        .as_ref()
                                        .map(|(_, name)| name)
                                        .unwrap_or(&value.ident)
                                        .to_string(),
                                );
                            }
                            Item::Mod(module) => {
                                self.shadow.insert(module.ident.to_string());
                                self.unsupported += 1;
                                self.diagnostics.insert(
                                    "Inline/external module dispatch is not semantically expanded"
                                        .into(),
                                );
                            }
                            Item::Fn(f) => {
                                self.shadow.insert(f.sig.ident.to_string());
                                self.functions.insert(
                                    format!("{file}:{}", f.sig.ident),
                                    (file.clone(), f.clone()),
                                );
                            }
                            _ => {}
                        }
                    }
                    self.shadows.insert(file.clone(), self.shadow.clone());
                    parsed.push((file, tree));
                }
                Err(e) => {
                    self.failed += 1;
                    self.diagnostics.insert(format!("{file}: {e}"));
                }
            }
        }
        if PathBuf::from(&self.r.root).join("Cargo.toml").exists() {
            let result = cargo_metadata::MetadataCommand::new()
                .manifest_path(PathBuf::from(&self.r.root).join("Cargo.toml"))
                .other_options(vec!["--offline".into(), "--locked".into()])
                .exec();
            if let Err(e) = result {
                self.diagnostics
                    .insert(format!("Offline Cargo metadata unavailable: {e}"));
            }
        }
        let functions: Vec<_> = self
            .functions
            .iter()
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect();
        let mut converged = false;
        for _ in 0..self.r.max_iterations.unwrap_or(64).clamp(1, 64) {
            let mut changed = false;
            for (key, (file, fun)) in &functions {
                let f = self.analyze_fn(file, fun, false);
                let old = self
                    .summaries
                    .get(key)
                    .cloned()
                    .unwrap_or_else(Fact::unknown);
                if old.kind != f.kind || old.mask != f.mask || old.len != f.len {
                    changed = true
                };
                self.summaries.insert(key.clone(), f);
            }
            if !changed {
                converged = true;
                break;
            }
        }
        if !converged {
            self.budget = true
        };
        for (_, (file, fun)) in functions {
            self.analyze_fn(&file, &fun, true);
        }
        // Methods use the same local flow engine, without assuming trait dispatch targets.
        for (file, tree) in parsed {
            for item in tree.items {
                if let Item::Impl(i) = item {
                    for method in i.items {
                        if let syn::ImplItem::Fn(f) = method {
                            let fun = syn::ItemFn {
                                attrs: f.attrs,
                                vis: f.vis,
                                sig: f.sig,
                                block: Box::new(f.block),
                            };
                            self.analyze_fn(&file, &fun, true);
                        }
                    }
                }
            }
        }
        let missing: Vec<String> = self
            .diagnostics
            .iter()
            .filter(|s| s.starts_with("Offline Cargo"))
            .cloned()
            .collect();
        let partial = self.failed > 0 || self.budget || self.unsupported > 0 || !missing.is_empty();
        json!({"protocolVersion":"1.0","analyzerVersion":"2.0.0-preview.1","analyzer":"rust","project":self.r.project,"languages":["rust"],"status":if partial{"partial"}else{"complete"},"findings":self.findings.values().collect::<Vec<_>>(),"dependencies":[],"diagnostics":self.diagnostics.iter().collect::<Vec<_>>(),"coverage":{"rust":{"discoveredFiles":self.r.files.len(),"analyzedFiles":self.analyzed,"failedFiles":self.failed,"unsupportedFiles":0,"unsupportedExpressionVisits":self.unsupported,"unresolvedSymbols":self.unresolved.len(),"budgetExhausted":self.budget,"missingDependencies":missing}},"capabilities":{"safetyAnalysis":true,"callGraph":false,"stabilityScore":false,"openApi":false,"vulnerabilityReachability":false}})
    }
}
fn integer(e: &Expr) -> Option<usize> {
    if let Expr::Lit(l) = e {
        if let syn::Lit::Int(n) = &l.lit {
            return n.base10_parse().ok();
        }
    }
    None
}
fn main() {
    let mut text = String::new();
    io::stdin().read_to_string(&mut text).unwrap();
    let r: Request = serde_json::from_str(&text).unwrap();
    assert_eq!(r.protocol_version, "1.0");
    let mut a = Analyzer {
        r,
        file: String::new(),
        lines: vec![],
        current: String::new(),
        findings: BTreeMap::new(),
        diagnostics: BTreeSet::new(),
        unresolved: BTreeSet::new(),
        functions: BTreeMap::new(),
        summaries: BTreeMap::new(),
        module: String::new(),
        shadow: BTreeSet::new(),
        shadows: BTreeMap::new(),
        failed: 0,
        analyzed: 0,
        budget: false,
        report: false,
        returns: vec![],
        loop_exits: vec![],
        unsupported: 0,
    };
    println!("{}", a.run());
}
