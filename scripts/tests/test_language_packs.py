"""Behavioral tests execute real compiler-backed packs, never the analyzed applications."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
PACKS=ROOT/'language-packs'
COMMANDS={
 'python':[sys.executable,'-I',str(PACKS/'python/analyzer.py')],
 'ecmascript':['node',str(PACKS/'ecmascript/analyzer.cjs')],
 'go':[str(PACKS/('go/bin/nullguard-go.exe' if os.name=='nt' else 'go/bin/nullguard-go'))],
 'rust':[str(PACKS/('rust/target/debug/nullguard-rust.exe' if os.name=='nt' else 'rust/target/debug/nullguard-rust'))],
 'csharp':['/opt/homebrew/bin/dotnet' if Path('/opt/homebrew/bin/dotnet').exists() else 'dotnet',str(PACKS/'csharp/bin/nullguard-csharp.dll')]
}

def analyze(pack,source,extension,extra=None):
 with tempfile.TemporaryDirectory(prefix='nullguard-fixture-') as directory:
  root=Path(directory);file='example.'+extension;(root/file).write_text(source)
  for name,text in (extra or {}).items():
   p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
  request={'protocolVersion':'1.0','project':'fixture:'+pack,'root':str(root),'files':[file],'languages':[{'ecmascript':'typescript' if extension in ('ts','tsx') else 'javascript','csharp':'csharp'}.get(pack,pack)],'maxIterations':64}
  env=dict(os.environ,PATH='/opt/homebrew/bin:'+os.environ['PATH'],GOPROXY='off',GOSUMDB='off',GOTOOLCHAIN='local',CARGO_NET_OFFLINE='true',DOTNET_CLI_TELEMETRY_OPTOUT='1')
  result=subprocess.run(COMMANDS[pack],input=json.dumps(request),capture_output=True,text=True,env=env,timeout=120)
  if result.returncode:raise AssertionError(result.stderr)
  data=json.loads(result.stdout)
  if data['protocolVersion']!='1.0' or data['project']!=request['project']:raise AssertionError('Protocol mismatch')
  return data

class LanguagePackTests(unittest.TestCase):
 def methods(self,result):return [(f['methodId'],f['ruleId'],f['message']) for f in result['findings']]
 def has(self,result,name,rule=None):return any(name in f['methodId'] and (rule is None or f['ruleId'].endswith(rule)) for f in result['findings'])
 def test_python_branches_calls_and_contracts(self):
  data=analyze('python','''def bad(value: str | None):
    return value.strip()
def guarded(value: str | None):
    if value is None:
        return ""
    return value.strip()
def wrong(value: str | None):
    if value is None:
        return value.strip()
def ternary(value: str | None):
    return value.strip() if value is not None else ""
def returns_none():
    return None
def consume():
    return returns_none().strip()
def callback(fn):
    return fn()
def contradicted() -> str:
    return None
''','py')
  for name in ['bad','wrong','consume','callback','contradicted']:self.assertTrue(self.has(data,'#'+name),self.methods(data))
  for name in ['guarded','ternary']:self.assertFalse(self.has(data,'#'+name),self.methods(data))
 def test_python_no_module_execution_and_parse_failure(self):
  with tempfile.TemporaryDirectory() as directory:
   sentinel=Path(directory)/'executed'
   data=analyze('python',f'from pathlib import Path\nPath({str(sentinel)!r}).write_text("wrong")\ndef f(x):\n    return x.value\n','py')
   self.assertFalse(sentinel.exists());self.assertEqual(1,data['coverage']['python']['analyzedFiles'])
  data=analyze('python','def broken(\n','py');self.assertEqual('partial',data['status']);self.assertEqual(1,data['coverage']['python']['failedFiles'])
 def test_typescript_nullish_optional_assertion_and_shadowing(self):
  data=analyze('ecmascript','''function bad(x: string | null) { return x.length; }
function guarded(x: string | null) { if (x === null) return 0; return x.length; }
function ternary(x: string | undefined) { return x !== undefined ? x.length : 0; }
function optional(x?: { name: string }) { return x?.name.length; }
function fallback(x?: string) { return (x ?? "").length; }
function assertion(x?: string) { return x!.length; }
function maybe() { return null; }
function consume() { return maybe().length; }
function shadow(maybe: () => string) { return maybe().length; }
''','ts',{'tsconfig.json':'{"compilerOptions":{"strict":true,"target":"ES2022"}}'})
  for name in ['bad','assertion','consume']:self.assertTrue(self.has(data,'#'+name),self.methods(data))
  for name in ['guarded','ternary','optional','fallback','shadow']:self.assertFalse(self.has(data,'#'+name),self.methods(data))
 def test_jsx_embedded_expressions_are_analyzed(self):
  data=analyze('ecmascript','function bad() { const x=null; return <div title={x.name}>{x.name}</div>; } function safe(x) { if(x==null)return null; return <div>{x.name}</div>; }','jsx')
  self.assertTrue(self.has(data,'#bad'));self.assertFalse(self.has(data,'#safe'),self.methods(data))
 def test_javascript_literal_null_and_react_jsx(self):
  data=analyze('ecmascript','''function bad() { const value = null; return value.name; }
function safe(value) { if (value == null) return null; return <div>{value.name}</div>; }
''','jsx',{'jsconfig.json':'{"compilerOptions":{"checkJs":true,"jsx":"preserve"}}'})
  self.assertTrue(self.has(data,'#bad'));self.assertFalse(self.has(data,'#safe'),self.methods(data))
 def test_csharp_cfg_nullable_guards_and_opposite_branch(self):
  data=analyze('csharp','''#nullable enable
class Cases {
 static int Bad(string? x) { return x.Length; }
 static int Guarded(string? x) { if (x is null) return 0; return x.Length; }
 static int Wrong(string? x) { if (x is null) return x.Length; return 0; }
 static int Pattern(object? x) { if (x is not string text) return 0; return text.Length; }
 static int Optional(int? value) { if (!value.HasValue) return 0; return value.Value; }
 static int UnsafeValue(int? value) { return value.Value; }
 static int Catch() { try { throw new System.Exception(); } catch (System.Exception ex) { return ex.GetHashCode(); } }
}
''','cs')
  for name in ['Bad','Wrong','UnsafeValue']:self.assertTrue(self.has(data,name),self.methods(data))
  for name in ['Guarded','Pattern','Optional','Catch']:self.assertFalse(self.has(data,name),self.methods(data))
 def test_go_nil_maps_pointers_guards_and_nil_safe_operations(self):
  data=analyze('go','''package fixture
func bad(p *int) int { return *p }
func guarded(p *int) int { if p == nil { return 0 }; return *p }
func wrong(p *int) int { if p == nil { return *p }; return 0 }
func nilmap() { var m map[string]int; m["x"] = 1 }
func initialized() { m := make(map[string]int); m["x"] = 1 }
func nilsafe() int { var xs []int; return len(xs) }
func returnsnil() (*int,error) { return nil,nil }
func unchecked() int { p,_ := returnsnil(); return *p }
''','go',{'go.mod':'module example.com/fixture\n\ngo 1.26.0\n'})
  for name in ['bad','wrong','nilmap','unchecked']:self.assertTrue(self.has(data,name),self.methods(data))
  for name in ['guarded','initialized','nilsafe']:self.assertFalse(self.has(data,name),self.methods(data))
 def test_rust_option_result_guards_and_indexing(self):
  data=analyze('rust','''fn bad(x: Option<i32>) -> i32 { x.unwrap() }
fn guarded(x: Option<i32>) -> i32 { if x.is_none() { return 0; } x.unwrap() }
fn wrong(x: Option<i32>) -> i32 { if x.is_none() { return x.unwrap(); } 0 }
fn known() -> i32 { Some(1).unwrap() }
fn matched(x: Option<i32>) -> i32 { match x { Some(_) => x.unwrap(), None => 0 } }
fn result(x: Result<i32, ()>) -> i32 { x.expect("required") }
fn checked(x: Result<i32, ()>) -> Result<i32, ()> { Ok(x?) }
fn bounded() -> i32 { let values = [1, 2]; values[1] }
fn indexing(values: Vec<i32>) -> i32 { values[5] }
''','rs')
  for name in ['bad','wrong','result','indexing']:self.assertTrue(self.has(data,'#'+name),self.methods(data))
  for name in ['guarded','known','matched','checked','bounded']:self.assertFalse(self.has(data,'#'+name),self.methods(data))
 def test_rust_custom_unwrap_is_not_option(self):
  data=analyze('rust','''struct Custom;
impl Custom { fn unwrap(&self) -> i32 { 1 } }
fn custom(value: Custom) -> i32 { value.unwrap() }
''','rs');self.assertFalse(self.has(data,'#custom'),self.methods(data))

 def test_python_keyword_contracts_and_exception_parameter(self):
  data=analyze('python','def requires(*, value: str):\n    return value.strip()\ndef bad():\n    return requires(value=None)\ndef caught():\n    try: raise ValueError()\n    except ValueError as error: return error.args\n','py')
  self.assertTrue(self.has(data,'#bad','003'));self.assertFalse(self.has(data,'#caught'),self.methods(data))
 def test_go_typed_nil_and_guarded_error_pair(self):
  data=analyze('go','package fixture\nimport "os"\nfunc typed() error { var e *os.PathError; return e }\nfunc guarded() { f,err:=os.Open("example"); if err!=nil{return}; _=f.Name() }\n','go',{'go.mod':'module example.com/fixture\ngo 1.26.0\n'})
  self.assertTrue(self.has(data,'typed','003'));self.assertFalse(self.has(data,'guarded'),self.methods(data))
 def test_csharp_resolved_nullable_return_consumption(self):
  data=analyze('csharp','class C { static string? Maybe() => null; static int Bad() => Maybe().Length; static int Safe() { var x=Maybe(); if(x==null)return 0; return x.Length; } }','cs')
  self.assertTrue(self.has(data,'Bad'));self.assertFalse(self.has(data,'Safe'),self.methods(data))
 def test_assignment_after_guards(self):
  data=analyze('python',"def f(x: str | None):\n    if x is None: return ''\n    x = None\n    return x.strip()\n",'py');self.assertTrue(self.has(data,'#f'))
  data=analyze('ecmascript','function f(x: string | null) { if(x === null) return 0; x=null; return x.length; }','ts');self.assertTrue(self.has(data,'#f'))
 def test_typescript_block_shadowing_and_nullable_callable_property(self):
  data=analyze('ecmascript','function bad() { let x = null; { let x = "ok"; } return x.length; } function call(x: {fn?: () => void}) { x.fn(); }','ts',{'tsconfig.json':'{"compilerOptions":{"strict":true}}'})
  self.assertTrue(self.has(data,'#bad'));self.assertTrue(self.has(data,'#call','004'))
 def test_typescript_configuration_exclusions(self):
  data=analyze('ecmascript','const x=null; x.name;','ts',{'tsconfig.json':'{"files":["included.ts"]}','included.ts':'export {}'})
  self.assertEqual(1,data['coverage']['typescript']['unsupportedFiles']);self.assertEqual('partial',data['status']);self.assertEqual([],data['findings'])
 def test_rust_bounds_and_shadowing(self):
  data=analyze('rust','fn safe(xs: Vec<i32>, i: usize) -> i32 { if i >= xs.len() { return 0; } xs[i] } fn shadow(x: Option<i32>) -> i32 { { let x = Some(1); } x.unwrap() } fn changed(xs: Vec<i32>, mut i: usize) -> i32 { if i >= xs.len() {return 0;} i=100; xs[i] }','rs')
  self.assertFalse(self.has(data,'#safe'),self.methods(data));self.assertTrue(self.has(data,'#shadow'));self.assertTrue(self.has(data,'#changed'))
 def test_rust_option_mutation(self):
  data=analyze('rust','fn f() { let mut x = Some(1); x.take(); x.unwrap(); }','rs');self.assertTrue(self.has(data,'#f'))
 def test_csharp_suppression_after_guard_and_expression_body(self):
  data=analyze('csharp','class C { static int Bad(string? x) => x.Length; static int Safe(string? x) { if(x is null) return 0; return x!.Length; } }','cs')
  self.assertTrue(self.has(data,'Bad'));self.assertFalse(self.has(data,'Safe'),self.methods(data))

if __name__=='__main__':unittest.main()
