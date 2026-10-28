#!/usr/bin/env python3
"""NullGuard-owned Python abstract interpreter. Never imports analyzed modules."""
import ast
import json
import pathlib
import symtable
import sys
from dataclasses import dataclass

VERSION = "2.0.0-preview.1"
NULL, NONNULL, UNKNOWN = "null", "nonnull", "unknown"

@dataclass(frozen=True)
class Value:
    state: str = UNKNOWN
    origin: str = "Value has no resolved nullability contract"
    line: int = 1

UNKNOWN_VALUE = Value()

def merge(a, b):
    if a == b:
        return a
    return Value(a.state if a.state == b.state else UNKNOWN,
                 a.origin if a.origin == b.origin else "Joined control-flow paths: " + (a.origin + "; " + b.origin)[:500],
                 min(a.line, b.line))

def join(a, b):
    if a is None: return None if b is None else dict(b)
    if b is None: return dict(a)
    return {k: merge(a.get(k, UNKNOWN_VALUE), b.get(k, UNKNOWN_VALUE)) for k in a.keys() | b.keys()}

def optional(annotation):
    if annotation is None: return False
    return any(isinstance(n, ast.Constant) and n.value is None or
               isinstance(n, ast.Name) and n.id in ("Optional", "None") or
               isinstance(n, ast.Attribute) and n.attr == "Optional"
               for n in ast.walk(annotation))

def nonnull_annotation(annotation):
    return annotation is not None and not optional(annotation) and not isinstance(annotation, ast.Constant)

class Analyzer:
    def __init__(self, request):
        self.request = request
        self.findings = {}
        self.diagnostics = []
        self.functions = {}
        self.module_imports = {}
        self.summaries = {}
        self.trees = []
        self.unresolved = set()
        self.failed = 0
        self.exhausted = False
        self.report = True
        self.file = ""
        self.lines = []
        self.returns = []
        self.current = "<module>"
        self.module = ""
        self.budget = max(1, min(64, int(request.get("maxIterations", 64))))

    def finding(self, node, value, rule="001", message=None):
        if not self.report or value.state == NONNULL: return
        line = getattr(node, "lineno", 1)
        raw = self.lines[line-1] if line <= len(self.lines) else ""
        col = len(raw.encode("utf-8")[:getattr(node, "col_offset", 0)].decode("utf-8", errors="ignore")) + 1
        message = message or ("Definite None dereference" if value.state == NULL else "Possible None dereference")
        item = {"ruleId": "NG-PY-"+rule, "language": "python", "severity": "warning",
                "confidence": "experimental", "methodId": self.module+"#"+self.current,
                "message": message, "location": {"path": self.file, "startLine": line, "startColumn": col},
                "evidence": [value.origin, "Receiver state: "+value.state, "Unsafe operation at line "+str(line)],
                "relatedLocations": [{"path": self.file, "startLine": value.line, "startColumn": 1}]}
        self.findings[(self.file, line, col, item["ruleId"], message)] = item

    def evaluate(self, node, state):
        if node is None: return Value(NULL, "Implicit None", 1)
        line = getattr(node, "lineno", 1)
        if isinstance(node, ast.Constant): return Value(NULL if node.value is None else NONNULL, "Literal "+repr(node.value), line)
        if isinstance(node, ast.Name): return state.get(node.id, self.module_imports.get(self.module, {}).get(node.id, UNKNOWN_VALUE))
        if isinstance(node, (ast.List, ast.Tuple, ast.Set, ast.Dict, ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp, ast.Lambda)):
            # Comprehension scopes do not leak variables into the containing state.
            if isinstance(node, (ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)):
                inner = dict(state)
                for generator in node.generators:
                    self.evaluate(generator.iter, inner)
                    self.assign(generator.target, UNKNOWN_VALUE, inner)
                    for test in generator.ifs: self.evaluate(test, inner); inner = self.refine(test, inner, True)
                if hasattr(node, "elt"): self.evaluate(node.elt, inner)
                if hasattr(node, "key"): self.evaluate(node.key, inner); self.evaluate(node.value, inner)
            elif isinstance(node, ast.Lambda):
                self.unresolved.add((self.file, line, "Lambda body not lowered"))
            else:
                for child in ast.iter_child_nodes(node):
                    if isinstance(child, ast.expr): self.evaluate(child, state)
            return Value(NONNULL, "Constructed container or callable", line)
        if isinstance(node, ast.Attribute):
            receiver = self.evaluate(node.value, state); self.finding(node, receiver)
            return state.get(ast.unparse(node), Value(UNKNOWN, "Unresolved attribute "+ast.unparse(node), line))
        if isinstance(node, ast.Subscript):
            receiver = self.evaluate(node.value, state); self.finding(node, receiver)
            self.evaluate(node.slice, state)
            return Value(UNKNOWN, "Container element has no resolved contract", line)
        if isinstance(node, ast.Call):
            if isinstance(node.func, ast.Attribute):
                receiver = self.evaluate(node.func.value, state); self.finding(node.func, receiver)
            elif not isinstance(node.func, ast.Name): self.finding(node.func, self.evaluate(node.func, state), "004", "Possibly None callable")
            arguments = [self.evaluate(a, state) for a in node.args]
            keyword_values = {keyword.arg: self.evaluate(keyword.value, state) for keyword in node.keywords}
            if None in keyword_values: self.unresolved.add((self.file, line, "Expanded keyword arguments cannot be resolved"))
            name = node.func.id if isinstance(node.func, ast.Name) else None
            target = self.functions.get(self.module+":"+name) if name else None
            # Lexically shadowed local names never resolve to a module function or builtin.
            shadowed = name in state if name else False
            if name and shadowed:
                self.finding(node.func, state[name], "004", "Possibly None callable")
            if target and not shadowed:
                fn = target[2]
                parameters=fn.args.posonlyargs+fn.args.args
                supplied=list(zip(parameters,arguments))+[(p,keyword_values[p.arg]) for p in parameters+fn.args.kwonlyargs if p.arg in keyword_values]
                for parameter, value in supplied:
                    if nonnull_annotation(parameter.annotation) and value.state != NONNULL:
                        self.finding(node, value, "003", "Nullable argument passed to non-optional parameter '"+parameter.arg+"'")
                return self.summaries.get(self.module+":"+name, UNKNOWN_VALUE)
            if name in {"str", "int", "float", "bool", "list", "tuple", "dict", "set", "len", "range", "object", "bytes"} and not shadowed:
                return Value(NONNULL, "Python builtin constructor or scalar return", line)
            self.unresolved.add((self.file, line, ast.unparse(node.func)))
            return Value(UNKNOWN, "Unresolved call return: "+ast.unparse(node.func), line)
        if isinstance(node, ast.IfExp):
            self.evaluate(node.test, state)
            return merge(self.evaluate(node.body, self.refine(node.test, state, True)), self.evaluate(node.orelse, self.refine(node.test, state, False)))
        if isinstance(node, ast.BoolOp):
            active = dict(state); values = []
            for child in node.values:
                values.append(self.evaluate(child, active))
                active = self.refine(child, active, isinstance(node.op, ast.And))
            value = values[0]
            for other in values[1:]: value = merge(value, other)
            return value
        if isinstance(node, ast.NamedExpr):
            value = self.evaluate(node.value, state); self.assign(node.target, value, state); return value
        if isinstance(node, (ast.BinOp, ast.UnaryOp, ast.Compare, ast.JoinedStr, ast.FormattedValue)):
            for child in ast.iter_child_nodes(node):
                if isinstance(child, ast.expr): self.evaluate(child, state)
            return Value(NONNULL, "Scalar operation completed", line)
        if isinstance(node, ast.Await): return self.evaluate(node.value, state)
        for child in ast.iter_child_nodes(node):
            if isinstance(child, ast.expr): self.evaluate(child, state)
        return Value(UNKNOWN, "Unsupported expression: "+type(node).__name__, line)

    def refine(self, test, state, truth):
        result = dict(state)
        if isinstance(test, ast.UnaryOp) and isinstance(test.op, ast.Not): return self.refine(test.operand, state, not truth)
        if isinstance(test, ast.BoolOp):
            guaranteed = isinstance(test.op, ast.And) == truth
            if guaranteed:
                for item in test.values: result = self.refine(item, result, truth)
                return result
            branches = [self.refine(item, state, truth) for item in test.values]
            out = branches[0]
            for branch in branches[1:]: out = join(out, branch)
            return out
        if isinstance(test, ast.Compare) and len(test.ops) == 1:
            left, right = test.left, test.comparators[0]
            if isinstance(left, ast.Constant) and left.value is None: left, right = right, left
            if isinstance(left, ast.Name) and isinstance(right, ast.Constant) and right.value is None and isinstance(test.ops[0], (ast.Is, ast.IsNot)):
                notnull = truth == isinstance(test.ops[0], ast.IsNot)
                result[left.id] = Value(NONNULL if notnull else NULL, "Branch "+ast.unparse(test)+" is "+str(truth), test.lineno)
        elif isinstance(test, ast.Name) and truth:
            result[test.id] = Value(NONNULL, "Truthy branch excludes None", test.lineno)
        return result

    def assign(self, target, value, state):
        if isinstance(target, ast.Name): state[target.id] = value
        elif isinstance(target, (ast.Tuple, ast.List)):
            for item in target.elts: self.assign(item, UNKNOWN_VALUE, state)
        elif isinstance(target, ast.Attribute):
            self.finding(target, self.evaluate(target.value, state)); state[ast.unparse(target)] = value
        elif isinstance(target, ast.Subscript): self.finding(target, self.evaluate(target.value, state))

    def block(self, statements, incoming):
        state = None if incoming is None else dict(incoming)
        for statement in statements:
            if state is None: break
            if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)): continue
            if isinstance(statement, ast.Assign):
                value = self.evaluate(statement.value, state)
                for target in statement.targets: self.assign(target, value, state)
            elif isinstance(statement, ast.AnnAssign):
                value = self.evaluate(statement.value, state) if statement.value else Value(UNKNOWN, "Declaration without an assigned value", statement.lineno)
                self.assign(statement.target, value, state)
            elif isinstance(statement, ast.AugAssign): self.evaluate(statement.target, state); self.evaluate(statement.value, state)
            elif isinstance(statement, ast.Expr): self.evaluate(statement.value, state)
            elif isinstance(statement, ast.Return): self.returns.append(self.evaluate(statement.value, state)); return None
            elif isinstance(statement, ast.Raise):
                if statement.exc: self.evaluate(statement.exc, state)
                return None
            elif isinstance(statement, ast.If):
                self.evaluate(statement.test, state)
                state = join(self.block(statement.body, self.refine(statement.test, state, True)), self.block(statement.orelse, self.refine(statement.test, state, False)))
            elif isinstance(statement, (ast.While, ast.For, ast.AsyncFor)):
                head = dict(state)
                if isinstance(statement, ast.While): self.evaluate(statement.test, head)
                else: self.evaluate(statement.iter, head); self.assign(statement.target, UNKNOWN_VALUE, head)
                previous = head
                reporting = self.report
                self.report = False
                for _ in range(self.budget):
                    entry = self.refine(statement.test, previous, True) if isinstance(statement, ast.While) else previous
                    body = self.block(statement.body, entry)
                    next_state = join(head, body)
                    # Compare the lattice, not ever-growing explanation strings.
                    if {k:v.state for k,v in next_state.items()} == {k:v.state for k,v in previous.items()}: break
                    previous = next_state
                else: self.exhausted = True
                self.report = reporting
                if reporting:
                    entry = self.refine(statement.test, previous, True) if isinstance(statement, ast.While) else previous
                    self.block(statement.body, entry)
                # Retain a conservative exit; break paths cannot be assumed to satisfy test=false.
                state = join(state, previous)
                state = self.block(statement.orelse, state)
            elif isinstance(statement, (ast.Try, getattr(ast, "TryStar", ast.Try))):
                branches = self.block(statement.body, state)
                branches = self.block(statement.orelse, branches)
                for handler in statement.handlers:
                    entry = dict(state)
                    if handler.name: entry[handler.name] = Value(NONNULL, "Caught exception instance", handler.lineno)
                    branches = join(branches, self.block(handler.body, entry))
                state = self.block(statement.finalbody, branches)
            elif isinstance(statement, ast.Assert):
                self.evaluate(statement.test, state)
                # Assertions can be disabled with -O; they are not unconditional contracts.
            elif isinstance(statement, (ast.With, ast.AsyncWith)):
                for item in statement.items:
                    self.evaluate(item.context_expr, state)
                    if item.optional_vars: self.assign(item.optional_vars, UNKNOWN_VALUE, state)
                state = self.block(statement.body, state)
            elif isinstance(statement, (ast.Break, ast.Continue)):
                # Loop exit handling is conservatively joined by the loop visitor.
                return state
            elif isinstance(statement, ast.Delete):
                for target in statement.targets:
                    if isinstance(target, ast.Name): state.pop(target.id, None)
            elif isinstance(statement, (ast.Import, ast.ImportFrom, ast.Pass, ast.Global, ast.Nonlocal)): pass
            else:
                self.unresolved.add((self.file, statement.lineno, "Unsupported statement: "+type(statement).__name__))
        return state

    def analyze_function(self, module, file, function, report):
        self.module, self.file, self.current, self.report = module, file, function.name, report
        self.lines = (pathlib.Path(self.request["root"])/file).read_text(encoding="utf-8").splitlines()
        state = {}
        positional = function.args.posonlyargs+function.args.args
        default_none = {p.arg for p,d in zip(positional[-len(function.args.defaults):], function.args.defaults) if isinstance(d, ast.Constant) and d.value is None} if function.args.defaults else set()
        for parameter in positional+function.args.kwonlyargs:
            state[parameter.arg] = Value(NONNULL if parameter.arg in ("self", "cls") or (nonnull_annotation(parameter.annotation) and parameter.arg not in default_none) else UNKNOWN,
                                         "Parameter '"+parameter.arg+"'"+(" is optional" if optional(parameter.annotation) else ""), parameter.lineno)
        self.returns = []
        fallthrough = self.block(function.body, state)
        values = self.returns + ([Value(NULL, "Function falls through without return", function.lineno)] if fallthrough is not None else [])
        result = values[0] if values else Value(NONNULL, "Function never returns normally", function.lineno)
        for value in values[1:]: result = merge(result, value)
        if report and nonnull_annotation(function.returns) and result.state != NONNULL:
            self.finding(function, result, "002", "Non-optional return annotation may produce None")
        return result

    def run(self):
        for file in self.request["files"]:
            path = pathlib.Path(self.request["root"])/file
            try:
                text = path.read_text(encoding="utf-8-sig")
                tree = ast.parse(text, filename=file, type_comments=True)
                symtable.symtable(text, file, "exec")
                module = str(pathlib.PurePosixPath(file).with_suffix("")).replace("/", ".")
                self.trees.append((module, file, tree))
                imported={}
                class Imports(ast.NodeVisitor):
                    def visit_FunctionDef(self,node): pass
                    visit_AsyncFunctionDef=visit_FunctionDef
                    visit_ClassDef=visit_FunctionDef
                    def visit_Name(self,node):
                        if isinstance(node.ctx,ast.Store): imported.pop(node.id,None)
                    def visit_Import(self,node):
                        for alias in node.names: imported[alias.asname or alias.name.split('.')[0]]=Value(NONNULL,"Imported module binding",node.lineno)
                Imports().visit(tree)
                for node in ast.walk(tree):
                    if isinstance(node,ast.Global):
                        for name in node.names: imported.pop(name,None)
                self.module_imports[module]=imported
                for function in tree.body:
                    if isinstance(function, (ast.FunctionDef, ast.AsyncFunctionDef)) and not function.decorator_list:
                        self.functions[module+":"+function.name] = (module, file, function)
            except (SyntaxError, UnicodeError, OSError, ValueError) as error:
                self.failed += 1; self.diagnostics.append(file+": "+str(error))
        for module,file,tree in self.trees:
            rebound=set()
            class Rebound(ast.NodeVisitor):
                def visit_FunctionDef(self,node): pass
                visit_AsyncFunctionDef=visit_FunctionDef
                visit_ClassDef=visit_FunctionDef
                def visit_Name(self,node):
                    if isinstance(node.ctx,ast.Store): rebound.add(node.id)
            Rebound().visit(tree)
            for node in ast.walk(tree):
                if isinstance(node,ast.Global): rebound.update(node.names)
            for name in rebound: self.functions.pop(module+":"+name,None)
        for _ in range(self.budget):
            changed = False
            for key, (module, file, fn) in self.functions.items():
                result = self.analyze_function(module, file, fn, False)
                if self.summaries.get(key, UNKNOWN_VALUE).state != result.state: changed = True
                self.summaries[key] = result
            if not changed: break
        else: self.exhausted = True
        for module, file, tree in self.trees:
            self.module, self.file, self.current, self.report = module, file, "<module>", True
            self.lines = (pathlib.Path(self.request["root"])/file).read_text(encoding="utf-8-sig").splitlines()
            self.returns = []; self.block(tree.body, {})
            for node in ast.walk(tree):
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    self.analyze_function(module, file, node, True)
        self.diagnostics.append("Dynamic imports, decorators and runtime attribute contracts are not executed or inferred")
        return {"protocolVersion":"1.0", "analyzerVersion":VERSION,"analyzer":"python","project":self.request["project"],"languages":["python"],
                "status":"partial" if self.failed or self.exhausted else "complete",
                "findings":sorted(self.findings.values(), key=lambda f:(f["location"]["path"],f["location"]["startLine"],f["location"]["startColumn"],f["ruleId"],f["message"])),
                "dependencies":[],"diagnostics":self.diagnostics,
                "coverage":{"python":{"discoveredFiles":len(self.request["files"]),"analyzedFiles":len(self.trees),"failedFiles":self.failed,"unsupportedFiles":0,"unresolvedSymbols":len(self.unresolved),"budgetExhausted":self.exhausted,"missingDependencies":[]}},
                "capabilities":{"safetyAnalysis":True,"callGraph":False,"stabilityScore":False,"openApi":False,"vulnerabilityReachability":False}}

if __name__ == "__main__":
    if sys.version_info < (3, 11): raise RuntimeError("Python >=3.11 is required")
    request=json.load(sys.stdin)
    if request.get("protocolVersion") != "1.0": raise ValueError("Unsupported protocol")
    json.dump(Analyzer(request).run(), sys.stdout, sort_keys=True)
