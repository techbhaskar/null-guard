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

if os.environ.get('NULLGUARD_TEST_PACK_HOME'):
 home=Path(os.environ['NULLGUARD_TEST_PACK_HOME'])
 for pack in COMMANDS:
  manifest=json.loads((home/pack/'pack.json').read_text())
  COMMANDS[pack]=[argument.replace('${pack}',str(home/pack)) for argument in manifest['command']]


def analyze(pack,source,extension,extra=None,selected=None):
 with tempfile.TemporaryDirectory(prefix='nullguard-fixture-') as directory:
  root=Path(directory);file='example.'+extension;(root/file).write_text(source)
  for name,text in (extra or {}).items():
   p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
  request={'protocolVersion':'1.0','project':'fixture:'+pack,'root':str(root),'files':[file]+(selected or []),'languages':[{'ecmascript':'typescript' if extension in ('ts','tsx') else 'javascript','csharp':'csharp'}.get(pack,pack)],'maxIterations':64}
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

 def test_python_import_bindings_are_nonnull_without_execution(self):
  data=analyze('python','import sys\nimport not_a_real_module\ndef safe():\n    return sys.version\ndef shadowed(sys: object | None):\n    return sys.version\n','py')
  self.assertFalse(self.has(data,'#safe'),self.methods(data));self.assertTrue(self.has(data,'#shadowed'))
  data=analyze('python','import sys\nsys=None\ndef bad(): return sys.version\n','py');self.assertTrue(self.has(data,'#bad'))
 def test_python_keyword_contracts_and_exception_parameter(self):
  data=analyze('python','def requires(*, value: str):\n    return value.strip()\ndef bad():\n    return requires(value=None)\ndef caught():\n    try: raise ValueError()\n    except ValueError as error: return error.args\n','py')
  self.assertTrue(self.has(data,'#bad','003'));self.assertFalse(self.has(data,'#caught'),self.methods(data))
 def test_go_typed_nil_and_guarded_error_pair(self):
  data=analyze('go','package fixture\nimport "os"\nfunc typed() error { var e *os.PathError; return e }\nfunc guarded() { f,err:=os.Open("example"); if err!=nil{return}; _=f.Name() }\n','go',{'go.mod':'module example.com/fixture\ngo 1.26.0\n'})
  self.assertTrue(self.has(data,'typed','003'));self.assertFalse(self.has(data,'guarded'),self.methods(data))
 def test_csharp_loop_join_preserves_dominating_allocations(self):
  data=analyze('csharp','class C { int Safe(int n) {var b=new System.Text.StringBuilder(); while(n-->0){ b.Append("x"); } lock(this) {return b.Length;} } static int Bad(string? x,int n) {while(n-->0){x=null;} return x.Length;} }','cs')
  self.assertFalse(self.has(data,'Safe'),self.methods(data));self.assertTrue(self.has(data,'Bad'),self.methods(data))
 def test_csharp_resolved_nullable_return_consumption(self):
  data=analyze('csharp','class C { static string? Maybe() => null; static int Bad() => Maybe().Length; static int Safe() { var x=Maybe(); if(x==null)return 0; return x.Length; } }','cs')
  self.assertTrue(self.has(data,'Bad'));self.assertFalse(self.has(data,'Safe'),self.methods(data))
 def test_assignment_after_guards(self):
  data=analyze('python',"def f(x: str | None):\n    if x is None: return ''\n    x = None\n    return x.strip()\n",'py');self.assertTrue(self.has(data,'#f'))
  data=analyze('ecmascript','function f(x: string | null) { if(x === null) return 0; x=null; return x.length; }','ts');self.assertTrue(self.has(data,'#f'))
 def test_typescript_block_shadowing_and_nullable_callable_property(self):
  data=analyze('ecmascript','function bad() { let x = null; { let x = "ok"; } return x.length; } function call(x: {fn?: () => void}) { x.fn(); }','ts',{'tsconfig.json':'{"compilerOptions":{"strict":true}}'})
  self.assertTrue(self.has(data,'#bad'));self.assertTrue(self.has(data,'#call','004'))
 def test_typescript_cross_file_summary_locations_and_unknown_method_dispatch(self):
  data=analyze('ecmascript','import {nil} from "./helper"; function bad(){return nil().length;} class Base { value():any{return "ok";} } function dynamic(x:Base){return x.value().length;}','ts',{'helper.ts':'\n'*10+'export function nil(){return null;}','tsconfig.json':'{"compilerOptions":{"strict":true}}'},selected=['helper.ts'])
  self.assertTrue(self.has(data,'#dynamic'),self.methods(data))
  bad=next(f for f in data['findings'] if '#bad' in f['methodId'])
  self.assertEqual('example.ts',bad['relatedLocations'][0]['path']);self.assertEqual(1,bad['relatedLocations'][0]['startLine'])
 def test_go_and_csharp_summary_locations_use_call_site(self):
  data=analyze('go','package fixture\nfunc bad()int{return *nilValue()}\n','go',{'go.mod':'module example.com/fixture\ngo 1.26.0\n','helper.go':'package fixture\n\n\n\nfunc nilValue()*int{return nil}\n'},selected=['helper.go'])
  bad=next(f for f in data['findings'] if '.bad' in f['methodId']);self.assertEqual(2,bad['relatedLocations'][0]['startLine'])
  data=analyze('csharp','class C {static int Bad()=>Helper.Value().Length;}','cs',{'helper.cs':'\n'*8+'class Helper {public static string? Value()=>null;}'},selected=['helper.cs'])
  bad=next(f for f in data['findings'] if 'Bad' in f['methodId']);self.assertEqual(1,bad['relatedLocations'][0]['startLine'])
 def test_python_decorated_and_rebound_calls_remain_unknown(self):
  data=analyze('python','def f(): return "ok"\nf=lambda:None\ndef bad(): return f().strip()\n@unknown\ndef g(): return "ok"\ndef decorated(): return g().strip()\n','py')
  self.assertTrue(self.has(data,'#bad'));self.assertTrue(self.has(data,'#decorated'))
 def test_typescript_typeof_array_guards_and_default_contracts(self):
  data=analyze('ecmascript','function stringSafe(x:unknown){if(typeof x!=="string")return 0;return x.length;} function arraySafe(x:unknown){if(Array.isArray(x))return x.length;return 0;} function takes(x:string[]=[]){return x.length;} function defaultSafe(){return takes(undefined);} function nullable(x:string|null){return x?.length;} function wrong(){return nullable(undefined);} function shadow(Array:{isArray:(x:unknown)=>boolean},x:unknown){if(Array.isArray(x))return x.value;}','ts',{'tsconfig.json':'{"compilerOptions":{"strict":true}}'})
  for name in ['stringSafe','arraySafe','defaultSafe']:self.assertFalse(self.has(data,'#'+name),self.methods(data))
  for name in ['wrong','shadow']:self.assertTrue(self.has(data,'#'+name),self.methods(data))
 def test_typescript_loops_converge_with_symbol_bindings(self):
  data=analyze('ecmascript','function bad(x: string|null, n: number) { while(n-->0) { x=null; } return x.length; } function safe(x: string|null, n: number) { if(x===null)return 0; for(let i=0;i<n;i++) { x=x.trim(); } return x.length; }','ts')
  self.assertTrue(self.has(data,'#bad'));self.assertFalse(self.has(data,'#safe'),self.methods(data))
 def test_go_alias_mutation_invalidates_memory_facts(self):
  data=analyze('go','package fixture\nfunc mutate(p **int){*p=nil}\nfunc bad(){p:=new(int);mutate(&p);_=*p}\nfunc captured(){p:=new(int);f:=func(){_=*p};p=nil;f()}\n','go',{'go.mod':'module example.com/fixture\ngo 1.26.0\n'})
  self.assertTrue(self.has(data,'.bad'),self.methods(data));self.assertTrue(self.has(data,'.captured'),self.methods(data))
 def test_go_closure_storage_and_standard_allocations(self):
  data=analyze('go','package fixture\nimport "flag"\nfunc safe() { p:=flag.Bool("p",false,""); done:=make(chan bool); go func(){if *p {done<-true}}() }\nfunc bad(p *int) { go func(){_ = *p}() }\n','go',{'go.mod':'module example.com/fixture\ngo 1.26.0\n'})
  self.assertFalse(self.has(data,'.safe'),self.methods(data));self.assertTrue(self.has(data,'.bad'),self.methods(data))
  data=analyze('go','package fixture\nimport "flag"\nfunc vars(){var count int;var enabled bool;flag.IntVar(&count,"c",2,"");flag.BoolVar(&enabled,"e",false,"");for i:=0;i<count;i++{go func(){if enabled { _=count }}()}}\n','go',{'go.mod':'module example.com/fixture\ngo 1.26.0\n'})
  self.assertFalse(self.has(data,'.vars'),self.methods(data))
 def test_go_globals_loop_dominators_and_local_storage(self):
  data=analyze('go','package fixture\nvar global *int\nvar flag bool\nfunc unsafe() int {return *global}\nfunc safe(n int) { if flag {} ; m:=make(map[int]int); for i:=0;i<n;i++ {m[i]=i} }\nfunc local() int { p:=new(int); q:=&p; return **q }\n','go',{'go.mod':'module example.com/fixture\ngo 1.26.0\n'})
  self.assertTrue(self.has(data,'unsafe'));self.assertFalse(self.has(data,'.safe'),self.methods(data));self.assertFalse(self.has(data,'local'),self.methods(data))
 def test_typescript_configuration_exclusions(self):
  data=analyze('ecmascript','const x=null; x.name;','ts',{'tsconfig.json':'{"files":["included.ts"]}','included.ts':'export {}'})
  self.assertEqual(1,data['coverage']['typescript']['unsupportedFiles']);self.assertEqual('partial',data['status']);self.assertEqual([],data['findings'])
 def test_rust_wildcard_and_local_callable_do_not_establish_standard_identity(self):
  data=analyze('rust','use foreign::*; fn f(x:Option<i32>)->i32{x.unwrap()}','rs')
  self.assertFalse(self.has(data,'#f'),self.methods(data));self.assertEqual('partial',data['status'])
  data=analyze('rust','use {std::fmt, foreign::*}; fn f(x:Option<i32>)->i32{x.unwrap()}', 'rs');self.assertFalse(self.has(data,'#f'),self.methods(data))
  data=analyze('rust','type Option = Custom; struct Custom; impl Custom {fn unwrap(self)->i32{1}} fn f(x:Option)->i32{x.unwrap()}', 'rs');self.assertFalse(self.has(data,'#f'),self.methods(data))
  data=analyze('rust','struct Custom; impl Custom {fn unwrap(self)->i32{1}} fn f(Some:fn()->Custom)->i32{Some().unwrap()}','rs');self.assertFalse(self.has(data,'#f'),self.methods(data))
 def test_rust_augmented_assignment_and_loop_shadow_invalidate_bounds(self):
  data=analyze('rust','fn changed(xs:Vec<i32>,mut i:usize)->i32{if i>=xs.len(){return 0;} i+=100; xs[i]} fn shadow(xs:Vec<i32>,i:usize,indices:&[usize]){if i>=xs.len(){return;} for i in 0..100 {xs[i];}}','rs')
  self.assertTrue(self.has(data,'#changed'),self.methods(data));self.assertTrue(self.has(data,'#shadow'),self.methods(data))
 def test_rust_local_items_do_not_borrow_module_function_summaries(self):
  data=analyze('rust','fn value()->Option<i32>{None} fn f(){ fn value()->i32{1} value().unwrap(); }','rs')
  self.assertFalse(self.has(data,'#f'),self.methods(data));self.assertEqual('partial',data['status'])
 def test_loop_findings_use_joined_state_without_duplicate_certainty(self):
  data=analyze('rust','fn f(flags: &[bool]) {let mut x=None;for flag in flags {if *flag {x=Some(1)} else {x.unwrap();}}}','rs')
  locations=[(f['ruleId'],f['location']['startLine'],f['location']['startColumn']) for f in data['findings']];self.assertEqual(len(set(locations)),len(locations),self.methods(data))
  data=analyze('python','def f(flags):\n    x=None\n    for flag in flags:\n        if flag: x="a"\n        else: x.strip()\n','py')
  locations=[(f['ruleId'],f['location']['startLine'],f['location']['startColumn']) for f in data['findings']];self.assertEqual(len(set(locations)),len(locations),self.methods(data))
  data=analyze('ecmascript','function f(flags:boolean[]) {let x:string|null=null;for(const flag of flags){if(flag)x="a";else x.length;}}','ts')
  locations=[(f['ruleId'],f['location']['startLine'],f['location']['startColumn']) for f in data['findings']];self.assertEqual(len(set(locations)),len(locations),self.methods(data))
 def test_rust_full_and_constant_slices(self):
  data=analyze('rust','fn full(xs: &[i32]) -> &[i32] { &xs[..] } fn bounded() { let xs=[1,2]; let _a=&xs[0..2]; let _b=&xs[..=1]; } fn wrong() { let xs=[1,2]; let _a=&xs[..=2]; }','rs')
  self.assertFalse(self.has(data,'#full'),self.methods(data));self.assertFalse(self.has(data,'#bounded'),self.methods(data));self.assertTrue(self.has(data,'#wrong'))
 def test_rust_bounds_and_shadowing(self):
  data=analyze('rust','fn safe(xs: Vec<i32>, i: usize) -> i32 { if i >= xs.len() { return 0; } xs[i] } fn shadow(x: Option<i32>) -> i32 { { let x = Some(1); } x.unwrap() } fn changed(xs: Vec<i32>, mut i: usize) -> i32 { if i >= xs.len() {return 0;} i=100; xs[i] }','rs')
  self.assertFalse(self.has(data,'#safe'),self.methods(data));self.assertTrue(self.has(data,'#shadow'));self.assertTrue(self.has(data,'#changed'))
 def test_rust_option_mutation(self):
  data=analyze('rust','fn f() { let mut x = Some(1); x.take(); x.unwrap(); }','rs');self.assertTrue(self.has(data,'#f'))
 def test_csharp_suppression_after_guard_and_expression_body(self):
  data=analyze('csharp','class C { static int Bad(string? x) => x.Length; static int Safe(string? x) { if(x is null) return 0; return x!.Length; } }','cs')
  self.assertTrue(self.has(data,'Bad'));self.assertFalse(self.has(data,'Safe'),self.methods(data))

if __name__=='__main__':unittest.main()
