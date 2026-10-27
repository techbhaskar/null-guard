// NullGuard-owned nil/error flow analysis over official Go SSA. No application execution.
package main

import (
	"encoding/json"
	"fmt"
	"go/token"
	"go/types"
	"golang.org/x/tools/go/packages"
	"golang.org/x/tools/go/ssa"
	"golang.org/x/tools/go/ssa/ssautil"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode/utf8"
)

const (
	Nil     = 1
	Non     = 2
	Typed   = 4
	Unknown = 3
)

type Request struct {
	ProtocolVersion string   `json:"protocolVersion"`
	Project         string   `json:"project"`
	Root            string   `json:"root"`
	Files           []string `json:"files"`
	MaxIterations   int      `json:"maxIterations"`
}
type Fact struct {
	Mask             int
	Origin           string
	Line             int
	Error            ssa.Value
	Ignored, Success bool
}
type State map[ssa.Value]Fact
type Analyzer struct {
	r           Request
	files       map[string]string
	lines       map[string][]string
	fset        *token.FileSet
	findings    map[string]any
	unresolved  map[string]bool
	summaries   map[*ssa.Function][]Fact
	budget      bool
	diagnostics []string
}

func nullable(t types.Type) bool {
	if t == nil {
		return true
	}
	switch t.Underlying().(type) {
	case *types.Pointer, *types.Map, *types.Slice, *types.Chan, *types.Signature, *types.Interface:
		return true
	}
	return false
}
func base(v ssa.Value) Fact {
	m := Non
	if v == nil || nullable(v.Type()) {
		m = Unknown
	}
	return Fact{Mask: m, Origin: "Unresolved nil contract", Line: 1}
}
func merge(a, b Fact) Fact {
	o := a.Origin
	if o != b.Origin {
		o = "Joined control-flow paths"
	}
	return Fact{Mask: a.Mask | b.Mask, Origin: o, Line: max(1, min(a.Line, b.Line)), Error: a.Error, Ignored: a.Ignored || b.Ignored, Success: a.Success && b.Success}
}
func clone(s State) State {
	r := State{}
	for k, v := range s {
		r[k] = v
	}
	return r
}
func join(a, b State) State {
	if a == nil {
		return clone(b)
	}
	r := clone(a)
	for k, v := range b {
		if old, ok := r[k]; ok {
			r[k] = merge(old, v)
		} else {
			r[k] = merge(base(k), v)
		}
	}
	for k, v := range r {
		if _, ok := b[k]; !ok {
			r[k] = merge(v, base(k))
		}
	}
	return r
}
func equal(a, b State) bool {
	if len(a) != len(b) {
		return false
	}
	for k, v := range a {
		if o, ok := b[k]; !ok || v.Mask != o.Mask {
			return false
		}
	}
	return true
}
func (a *Analyzer) pos(p token.Pos) (string, int, int) {
	x := a.fset.Position(p)
	abs, _ := filepath.Abs(x.Filename)
	col := x.Column
	if lines := a.lines[abs]; x.Line > 0 && x.Line <= len(lines) {
		s := lines[x.Line-1]
		col = utf8.RuneCountInString(s[:min(len(s), max(0, col-1))]) + 1
	}
	return a.files[abs], max(1, x.Line), max(1, col)
}
func (a *Analyzer) emit(fn *ssa.Function, p token.Pos, f Fact, rule, message string) {
	file, line, col := a.pos(p)
	if file == "" {
		return
	}
	item := map[string]any{"ruleId": "NG-GO-" + rule, "language": "go", "severity": "warning", "confidence": "experimental", "methodId": fn.String(), "message": message, "location": map[string]any{"path": file, "startLine": line, "startColumn": col}, "evidence": []string{f.Origin, fmt.Sprintf("Nil-state mask: %d", f.Mask)}, "relatedLocations": []any{map[string]any{"path": file, "startLine": max(1, f.Line), "startColumn": 1}}}
	a.findings[fmt.Sprintf("%s:%d:%d:%s:%s", file, line, col, rule, message)] = item
}
func (a *Analyzer) val(v ssa.Value, s State) Fact {
	if f, ok := s[v]; ok {
		return f
	}
	switch x := v.(type) {
	case *ssa.Const:
		if x.IsNil() {
			return Fact{Mask: Nil, Origin: "nil constant", Line: 1}
		}
		return Fact{Mask: Non, Origin: "Constant", Line: 1}
	case *ssa.Function, *ssa.Builtin:
		return Fact{Mask: Non, Origin: "Resolved function", Line: 1}
	}
	return base(v)
}
func (a *Analyzer) refine(pred, succ *ssa.BasicBlock, s State) State {
	r := clone(s)
	if len(pred.Instrs) == 0 {
		return r
	}
	i, ok := pred.Instrs[len(pred.Instrs)-1].(*ssa.If)
	if !ok {
		return r
	}
	b, ok := i.Cond.(*ssa.BinOp)
	if !ok || (b.Op != token.EQL && b.Op != token.NEQ) {
		return r
	}
	v := b.X
	c, ok := b.Y.(*ssa.Const)
	if !ok || !c.IsNil() {
		c, ok = b.X.(*ssa.Const)
		v = b.Y
	}
	if !ok || !c.IsNil() {
		return r
	}
	f := a.val(v, s)
	isEqual := (pred.Succs[0] == succ) == (b.Op == token.EQL)
	if isEqual {
		f.Mask &= Nil
	} else {
		f.Mask &= Non | Typed
	}
	f.Origin = "Selected nil guard branch"
	r[v] = f
	return r
}
func isError(t types.Type) bool { return types.Identical(t, types.Universe.Lookup("error").Type()) }
func successful(call *ssa.Call) bool {
	f := call.Common().StaticCallee()
	if f == nil || f.Pkg == nil {
		return false
	}
	switch f.Pkg.Pkg.Path() + "." + f.Name() {
	case "os.Open", "os.OpenFile", "os.Create", "net.Dial", "net/http.NewRequest", "net/http.NewRequestWithContext":
		return true
	}
	return false
}
func (a *Analyzer) transfer(fn *ssa.Function, b *ssa.BasicBlock, in State, outs map[*ssa.BasicBlock]State, report bool) State {
	s := clone(in)
	errors := map[ssa.Value]ssa.Value{}
	for _, block := range fn.Blocks {
		for _, i := range block.Instrs {
			if e, ok := i.(*ssa.Extract); ok {
				if t, ok := e.Tuple.Type().(*types.Tuple); ok && isError(t.At(e.Index).Type()) {
					errors[e.Tuple] = e
				}
			}
		}
	}
	check := func(v ssa.Value, p token.Pos, kind string) {
		f := a.val(v, s)
		if f.Success && f.Error != nil && a.val(f.Error, s).Mask == Nil {
			return
		}
		if f.Mask&Nil != 0 && report {
			a.emit(fn, p, f, "001", "Possible nil "+kind)
			if f.Ignored || (f.Error != nil && a.val(f.Error, s).Mask != Nil) {
				a.emit(fn, p, f, "004", "Unchecked error path reaches unsafe result use")
			}
		}
	}
	for _, i := range b.Instrs {
		_, line, _ := a.pos(i.Pos())
		switch x := i.(type) {
		case *ssa.Phi:
			var f Fact
			for n, v := range x.Edges {
				edge := a.refine(b.Preds[n], b, outs[b.Preds[n]])
				if n == 0 {
					f = a.val(v, edge)
				} else {
					f = merge(f, a.val(v, edge))
				}
			}
			s[x] = f
		case *ssa.Alloc:
			s[x] = Fact{Mask: Non, Origin: "Allocated object", Line: line}
		case *ssa.MakeMap:
			s[x] = Fact{Mask: Non, Origin: "Initialized map", Line: line}
		case *ssa.MakeSlice:
			s[x] = Fact{Mask: Non, Origin: "Initialized slice", Line: line}
		case *ssa.MakeChan:
			s[x] = Fact{Mask: Non, Origin: "Initialized channel", Line: line}
		case *ssa.MakeClosure:
			s[x] = Fact{Mask: Non, Origin: "Function closure", Line: line}
		case *ssa.MakeInterface:
			f := a.val(x.X, s)
			if f.Mask == Nil {
				f.Mask = Typed
				f.Origin = "Interface wraps a typed nil"
			} else if f.Mask&Nil != 0 {
				f.Mask = Non | Typed
			} else {
				f.Mask = Non
			}
			s[x] = f
		case *ssa.ChangeInterface:
			s[x] = a.val(x.X, s)
		case *ssa.ChangeType:
			s[x] = a.val(x.X, s)
		case *ssa.Convert:
			s[x] = a.val(x.X, s)
		case *ssa.UnOp:
			if x.Op == token.MUL {
				check(x.X, x.Pos(), "pointer dereference")
			}
			s[x] = base(x)
		case *ssa.FieldAddr:
			check(x.X, x.Pos(), "field receiver")
			s[x] = Fact{Mask: Non, Origin: "Address after receiver access", Line: line}
		case *ssa.IndexAddr:
			f := a.val(x.X, s)
			if _, ok := x.X.Type().Underlying().(*types.Pointer); ok {
				check(x.X, x.Pos(), "array pointer")
			}
			if f.Mask == Nil && report {
				a.emit(fn, x.Pos(), f, "005", "Indexing a known nil slice cannot be in bounds")
			}
			s[x] = Fact{Mask: Non, Origin: "Element address", Line: line}
		case *ssa.MapUpdate:
			f := a.val(x.Map, s)
			if f.Mask&Nil != 0 && report {
				a.emit(fn, x.Pos(), f, "002", "Write to a possibly nil map")
			}
		case *ssa.Lookup:
			s[x] = base(x)
		case *ssa.Extract:
			f := base(x)
			if call, ok := x.Tuple.(*ssa.Call); ok {
				if target := call.Common().StaticCallee(); target != nil {
					if values, ok := a.summaries[target]; ok && x.Index < len(values) {
						f = values[x.Index]
					}
				}
				if tuple, ok := x.Tuple.Type().(*types.Tuple); ok && tuple.Len() > 0 && isError(tuple.At(tuple.Len()-1).Type()) && x.Index != tuple.Len()-1 && nullable(x.Type()) {
					f.Error = errors[x.Tuple]
					f.Ignored = f.Error == nil
					f.Success = successful(call)
					f.Origin = "Call result paired with error"
				}
			}
			s[x] = f
		case *ssa.Call:
			c := x.Common()
			if c.IsInvoke() {
				check(c.Value, x.Pos(), "interface call")
			} else if c.StaticCallee() == nil {
				if _, builtin := c.Value.(*ssa.Builtin); !builtin {
					check(c.Value, x.Pos(), "function call")
					a.unresolved[fn.String()+fmt.Sprint(x.Pos())] = true
				}
			}
			f := base(x)
			if target := c.StaticCallee(); target != nil {
				if values, ok := a.summaries[target]; ok && len(values) == 1 {
					f = values[0]
				}
			}
			s[x] = f
		case *ssa.Return:
			if report {
				for _, v := range x.Results {
					f := a.val(v, s)
					if f.Mask&Typed != 0 {
						a.emit(fn, x.Pos(), f, "003", "Returned interface may wrap typed nil and compare unequal to nil")
					}
				}
			}
		case *ssa.BinOp:
			if report && (x.Op == token.EQL || x.Op == token.NEQ) {
				f := a.val(x.X, s)
				if f.Mask&Typed != 0 {
					if c, ok := x.Y.(*ssa.Const); ok && c.IsNil() {
						a.emit(fn, x.Pos(), f, "003", "Typed nil interface compares unequal to nil")
					}
				}
			}
			s[x] = Fact{Mask: Non, Origin: "Scalar operation", Line: line}
		default:
			if v, ok := i.(ssa.Value); ok {
				s[v] = base(v)
			}
		}
	}
	return s
}
func (a *Analyzer) analyze(fn *ssa.Function, report bool) []Fact {
	ins := map[*ssa.BasicBlock]State{}
	outs := map[*ssa.BasicBlock]State{}
	for _, b := range fn.Blocks {
		outs[b] = State{}
	}
	converged := false
	for n := 0; n < a.r.MaxIterations; n++ {
		changed := false
		for _, b := range fn.Blocks {
			var incoming State
			for _, pred := range b.Preds {
				incoming = join(incoming, a.refine(pred, b, outs[pred]))
			}
			if incoming == nil {
				incoming = State{}
			}
			for _, p := range fn.Params {
				if _, ok := incoming[p]; !ok {
					incoming[p] = base(p)
				}
			}
			ins[b] = incoming
			out := a.transfer(fn, b, incoming, outs, false)
			if !equal(out, outs[b]) {
				outs[b] = out
				changed = true
			}
		}
		if !changed {
			converged = true
			break
		}
	}
	if !converged {
		a.budget = true
	}
	results := make([]Fact, fn.Signature.Results().Len())
	for _, b := range fn.Blocks {
		state := a.transfer(fn, b, ins[b], outs, report)
		if len(b.Instrs) > 0 {
			if ret, ok := b.Instrs[len(b.Instrs)-1].(*ssa.Return); ok {
				for i, v := range ret.Results {
					results[i] = merge(results[i], a.val(v, state))
				}
			}
		}
	}
	return results
}
func (a *Analyzer) run() map[string]any {
	a.fset = token.NewFileSet()
	a.files = map[string]string{}
	a.lines = map[string][]string{}
	for _, file := range a.r.Files {
		abs, _ := filepath.Abs(filepath.Join(a.r.Root, file))
		a.files[abs] = file
		if data, e := os.ReadFile(abs); e == nil {
			a.lines[abs] = strings.Split(string(data), "\n")
		}
	}
	env := append(os.Environ(), "GOPROXY=off", "GOSUMDB=off", "GOTOOLCHAIN=local", "GOFLAGS=")
	flags := []string{"-mod=readonly"}
	originalFlags := strings.Fields(os.Getenv("GOFLAGS"))
	for i := 0; i < len(originalFlags); i++ {
		flag := originalFlags[i]
		if strings.HasPrefix(flag, "-tags=") {
			flags = append(flags, flag)
		} else if flag == "-tags" && i+1 < len(originalFlags) {
			i++
			flags = append(flags, "-tags="+originalFlags[i])
		} else if flag != "-mod=readonly" {
			a.diagnostics = append(a.diagnostics, "Unsupported GOFLAGS option omitted: "+flag)
		}
	}
	cfg := &packages.Config{BuildFlags: flags, Dir: a.r.Root, Env: env, Fset: a.fset, Tests: false, Mode: packages.NeedName | packages.NeedFiles | packages.NeedCompiledGoFiles | packages.NeedSyntax | packages.NeedTypes | packages.NeedTypesInfo | packages.NeedImports | packages.NeedDeps | packages.NeedModule}
	pkgs, err := packages.Load(cfg, "./...")
	failed := map[string]bool{}
	analyzed := map[string]bool{}
	missing := []string{}
	if err != nil {
		a.diagnostics = append(a.diagnostics, err.Error())
	}
	healthy := err == nil
	for _, pkg := range pkgs {
		if pkg.IllTyped {
			healthy = false
		}
		for _, problem := range pkg.Errors {
			a.diagnostics = append(a.diagnostics, problem.Error())
			missing = append(missing, problem.Msg)
		}
		for _, file := range pkg.CompiledGoFiles {
			abs, _ := filepath.Abs(file)
			if _, ok := a.files[abs]; ok {
				if pkg.IllTyped {
					failed[abs] = true
				} else {
					analyzed[abs] = true
				}
			}
		}
	}
	if healthy && len(pkgs) > 0 {
		prog, _ := ssautil.AllPackages(pkgs, ssa.InstantiateGenerics)
		prog.Build()
		var functions []*ssa.Function
		for fn := range ssautil.AllFunctions(prog) {
			if len(fn.Blocks) > 0 {
				file, _, _ := a.pos(fn.Pos())
				if file != "" {
					functions = append(functions, fn)
				}
			}
		}
		sort.Slice(functions, func(i, j int) bool { return functions[i].String() < functions[j].String() })
		converged := false
		for n := 0; n < a.r.MaxIterations; n++ {
			changed := false
			for _, fn := range functions {
				next := a.analyze(fn, false)
				old := a.summaries[fn]
				if len(old) != len(next) {
					changed = true
				} else {
					for i := range next {
						if old[i].Mask != next[i].Mask {
							changed = true
						}
					}
				}
				a.summaries[fn] = next
			}
			if !changed {
				converged = true
				break
			}
		}
		if !converged {
			a.budget = true
		}
		for _, fn := range functions {
			a.analyze(fn, true)
		}
	} else {
		for abs := range a.files {
			failed[abs] = true
		}
		analyzed = map[string]bool{}
	}
	unsupported := max(0, len(a.r.Files)-len(analyzed)-len(failed))
	status := "complete"
	if len(failed) > 0 || unsupported > 0 || len(missing) > 0 || a.budget || err != nil {
		status = "partial"
	}
	if unsupported > 0 {
		a.diagnostics = append(a.diagnostics, "Files excluded by GOOS/GOARCH/build tags are recorded as unsupported")
	}
	keys := []string{}
	for k := range a.findings {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	findings := []any{}
	for _, k := range keys {
		findings = append(findings, a.findings[k])
	}
	if a.diagnostics == nil {
		a.diagnostics = []string{}
	}
	return map[string]any{"protocolVersion": "1.0", "analyzerVersion": "2.0.0-preview.1", "analyzer": "go", "project": a.r.Project, "languages": []string{"go"}, "status": status, "findings": findings, "dependencies": []any{}, "diagnostics": a.diagnostics, "coverage": map[string]any{"go": map[string]any{"discoveredFiles": len(a.r.Files), "analyzedFiles": len(analyzed), "failedFiles": len(failed), "unsupportedFiles": unsupported, "unresolvedSymbols": len(a.unresolved), "budgetExhausted": a.budget, "missingDependencies": missing}}, "capabilities": map[string]bool{"safetyAnalysis": true, "callGraph": false, "stabilityScore": false, "openApi": false, "vulnerabilityReachability": false}}
}
func main() {
	var r Request
	if e := json.NewDecoder(os.Stdin).Decode(&r); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
	if r.ProtocolVersion != "1.0" {
		fmt.Fprintln(os.Stderr, "Unsupported protocol")
		os.Exit(1)
	}
	if r.MaxIterations < 1 || r.MaxIterations > 64 {
		r.MaxIterations = 64
	}
	a := Analyzer{r: r, findings: map[string]any{}, unresolved: map[string]bool{}, summaries: map[*ssa.Function][]Fact{}}
	if e := json.NewEncoder(os.Stdout).Encode(a.run()); e != nil {
		fmt.Fprintln(os.Stderr, e)
		os.Exit(1)
	}
}
