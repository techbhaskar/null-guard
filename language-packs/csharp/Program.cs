using System.Text.Json;
using System.Text.Json.Nodes;
using System.Xml.Linq;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.FlowAnalysis;
using Microsoft.CodeAnalysis.Operations;

record Request(string protocolVersion,string project,string root,string[] files,string[] languages,int maxIterations);
record Fact(int Mask=3,string Origin="Unresolved nullable contract",int Line=1);
sealed class Analyzer(Request request) {
 const int Null=1, Non=2;
 readonly SortedDictionary<string,object> findings=new();
 readonly SortedSet<string> diagnostics=new(), missing=new(), unresolved=new();
 readonly Dictionary<IMethodSymbol,Fact> summaries=new(SymbolEqualityComparer.Default);
 readonly Dictionary<IMethodSymbol,(SyntaxNode,SemanticModel)> methods=new(SymbolEqualityComparer.Default);
 bool budget,reporting; string file="",method=""; SemanticModel model=null!;
 Dictionary<string,Fact> state=new();
 Dictionary<string,Fact> captureDefaults=new();
 static string Key(ISymbol s)=>s.Kind+":"+s.ToDisplayString()+":"+s.Locations.FirstOrDefault()?.SourceSpan.Start;
 static Fact Merge(Fact a,Fact b)=>new(a.Mask|b.Mask,a.Origin==b.Origin?a.Origin:"Joined control-flow paths",Math.Min(a.Line,b.Line));
 static Dictionary<string,Fact> Join(Dictionary<string,Fact>? a,Dictionary<string,Fact> b){if(a is null)return new(b);var result=new Dictionary<string,Fact>();foreach(var key in a.Keys.Union(b.Keys))result[key]=Merge(a.GetValueOrDefault(key,new()),b.GetValueOrDefault(key,new()));return result;}
 static bool Same(Dictionary<string,Fact> a,Dictionary<string,Fact> b)=>a.Count==b.Count&&a.All(p=>b.TryGetValue(p.Key,out var f)&&p.Value.Mask==f.Mask);
 static bool NullableValue(ITypeSymbol? t)=>t is INamedTypeSymbol n&&n.OriginalDefinition.SpecialType==SpecialType.System_Nullable_T;
 static Fact Contract(ITypeSymbol? type,NullableAnnotation annotation){if(type is null)return new();if(NullableValue(type))return new();if(type.IsValueType)return new(Non,"Value type");return new(annotation==NullableAnnotation.NotAnnotated?Non:3,"Declared nullable annotation: "+annotation);}
 int Line(SyntaxNode n)=>n.GetLocation().GetLineSpan().StartLinePosition.Line+1;
 void Emit(IOperation node,Fact value,string rule="001",string? message=null){if(!reporting||(value.Mask&Null)==0)return;var start=node.Syntax.GetLocation().GetLineSpan().StartLinePosition;var text=node.Syntax.SyntaxTree.GetText().Lines[start.Line].ToString();var column=text[..Math.Min(start.Character,text.Length)].EnumerateRunes().Count()+1;var item=new{ruleId="NG-CS-"+rule,language="csharp",severity="warning",confidence="experimental",methodId=method,message=message??(value.Mask==Null?"Definite null dereference":"Possible null dereference"),location=new{path=file,startLine=start.Line+1,startColumn=column},evidence=new[]{value.Origin,"Nullable mask: "+value.Mask},relatedLocations=new[]{new{path=file,startLine=Math.Max(1,value.Line),startColumn=1}}};findings[$"{file}:{start.Line:D8}:{column:D6}:{rule}:{item.message}"]=item;}
 Fact ReadSymbol(ISymbol symbol,ITypeSymbol? type,NullableAnnotation annotation)=>state.GetValueOrDefault(Key(symbol),Contract(type,annotation));
 void Assign(IOperation target,Fact value){if(target is ILocalReferenceOperation l)state[Key(l.Local)]=value;else if(target is IParameterReferenceOperation p)state[Key(p.Parameter)]=value;else if(target is IFlowCaptureReferenceOperation capture)state["capture:"+capture.Id.GetHashCode()]=value;}
 Fact Checked(IOperation op,Fact v){if(op.Syntax.Parent is PostfixUnaryExpressionSyntax p&&p.IsKind(SyntaxKind.SuppressNullableWarningExpression))Emit(op,v,"002","Null-forgiving assertion hides a nullable value");return v;}
 Fact Eval(IOperation? op){if(op is null)return new(Non,"No value");if(op.ConstantValue.HasValue)return new(op.ConstantValue.Value is null?Null:Non,"Constant value",Line(op.Syntax));switch(op){
 case ILocalReferenceOperation l:return Checked(l,ReadSymbol(l.Local,l.Type,l.Local.NullableAnnotation));
 case IParameterReferenceOperation p:return Checked(p,ReadSymbol(p.Parameter,p.Type,p.Parameter.NullableAnnotation));
 case IInstanceReferenceOperation:return new(Non,"Instance reference",Line(op.Syntax));
 case IFlowCaptureReferenceOperation c:return state.GetValueOrDefault("capture:"+c.Id.GetHashCode(),captureDefaults.GetValueOrDefault("capture:"+c.Id.GetHashCode(),new()));
 case IFlowCaptureOperation c:var captured=Eval(c.Value);var captureKey="capture:"+c.Id.GetHashCode();state[captureKey]=captured;captureDefaults[captureKey]=captureDefaults.TryGetValue(captureKey,out var priorCapture)?Merge(priorCapture,captured):captured;return captured;
 case IVariableDeclaratorOperation d:var initial=d.Initializer is null?new Fact(d.Symbol.Type.IsValueType&&!NullableValue(d.Symbol.Type)?Non:Null,"Default local value",Line(d.Syntax)):Eval(d.Initializer.Value);state[Key(d.Symbol)]=initial;return initial;
 case ISimpleAssignmentOperation a:var assigned=Eval(a.Value);Assign(a.Target,assigned);return assigned;
 case IConversionOperation c:var converted=Eval(c.Operand);return c.IsTryCast?new(3,"as conversion may return null",Line(c.Syntax)):converted;
 case IObjectCreationOperation o:foreach(var arg in o.Arguments)Eval(arg.Value);return new(Non,"Constructed object",Line(op.Syntax));
 case IArrayCreationOperation a:foreach(var d in a.DimensionSizes)Eval(d);if(a.Initializer is not null)Eval(a.Initializer);return new(Non,"Constructed array",Line(op.Syntax));
 case IAnonymousFunctionOperation:return new(Non,"Delegate instance",Line(op.Syntax));
 case IDelegateCreationOperation d:Eval(d.Target);return new(Non,"Delegate instance",Line(op.Syntax));
 case IDefaultValueOperation:return new(op.Type?.IsReferenceType==true||NullableValue(op.Type)?Null:Non,"Default value",Line(op.Syntax));
 case ICaughtExceptionOperation:return new(Non,"Caught exception",Line(op.Syntax));
 case IFieldReferenceOperation f:if(f.Instance is not null)Emit(f,Eval(f.Instance));return new Fact(f.Field.Type.IsValueType&&!NullableValue(f.Field.Type)?Non:3,"Field lifecycle is not proven",Line(f.Syntax));
 case IPropertyReferenceOperation p:var receiver=p.Instance is null?new Fact(Non):Eval(p.Instance);bool nullable=NullableValue(p.Instance?.Type);if(nullable&&p.Property.Name=="HasValue")return new(Non,"Nullable.HasValue",Line(p.Syntax));if(p.Instance is not null)Emit(p,receiver,nullable&&p.Property.Name=="Value"?"003":"001",nullable&&p.Property.Name=="Value"?"Nullable.Value access without a proven HasValue guard":null);foreach(var arg in p.Arguments)Eval(arg.Value);return Contract(p.Type,p.Property.NullableAnnotation);
 case IArrayElementReferenceOperation a:Emit(a,Eval(a.ArrayReference));foreach(var index in a.Indices)Eval(index);return Contract(a.Type,a.Type?.NullableAnnotation??NullableAnnotation.None);
 case IInvocationOperation call:
  if(call.Instance is not null)Emit(call,Eval(call.Instance));foreach(var arg in call.Arguments){var v=Eval(arg.Value);if(arg.Parameter?.NullableAnnotation==NullableAnnotation.NotAnnotated&&arg.Parameter.Type.IsReferenceType)Emit(arg,v,"004","Nullable argument violates a non-null parameter contract");}
  if(call.TargetMethod.ContainingType.ToDisplayString()=="System.ArgumentNullException"&&call.TargetMethod.Name=="ThrowIfNull"&&call.Arguments.Length>0)Assign(call.Arguments[0].Value,new(Non,"ArgumentNullException.ThrowIfNull completed",Line(call.Syntax)));
  if(!call.IsVirtual&&summaries.TryGetValue(call.TargetMethod.OriginalDefinition,out var summary))return summary with {Line=Line(call.Syntax),Origin="Source return consumed at call: "+summary.Origin};
  if(call.IsVirtual)unresolved.Add(file+":"+Line(call.Syntax)+":virtual dispatch remains unresolved");
  return Contract(call.Type,call.TargetMethod.ReturnNullableAnnotation);
 case IConditionalOperation c:Eval(c.Condition);var original=new Dictionary<string,Fact>(state);state=Refine(c.Condition,original,true);var yes=Eval(c.WhenTrue);var ys=state;state=Refine(c.Condition,original,false);var no=Eval(c.WhenFalse);state=Join(ys,state);return Merge(yes,no);
 case ICoalesceOperation c:var left=Eval(c.Value);var right=Eval(c.WhenNull);return(left.Mask&Null)!=0?Merge(new(left.Mask&Non,left.Origin,left.Line),right):left;
 case IUnaryOperation u:Eval(u.Operand);return new(Non,"Unary result",Line(u.Syntax));
 case IBinaryOperation b:Eval(b.LeftOperand);Eval(b.RightOperand);return new(Non,"Scalar expression",Line(b.Syntax));
 case IIsPatternOperation p:Eval(p.Value);return new(Non,"Pattern test",Line(p.Syntax));
 case IIsNullOperation n:Eval(n.Operand);return new(Non,"Null test",Line(n.Syntax));
 case IReturnOperation r:return Eval(r.ReturnedValue);
 case IThrowOperation t:Eval(t.Exception);return new();
 case IInvalidOperation:unresolved.Add(file+":"+Line(op.Syntax)+":invalid operation");break;
 }
 foreach(var child in op.ChildOperations)Eval(child);return Contract(op.Type,op.Type?.NullableAnnotation??NullableAnnotation.None);
 }
 Dictionary<string,Fact> Refine(IOperation? condition,Dictionary<string,Fact> incoming,bool truth){var result=new Dictionary<string,Fact>(incoming);if(condition is null)return result;
  void Set(IOperation value,int allowed){var previous=state;state=result;var old=EvalWithoutReport(value);state=previous;var refined=new Fact(old.Mask&allowed,"Selected null/pattern guard",Line(condition.Syntax));if(value is ILocalReferenceOperation l)result[Key(l.Local)]=refined;else if(value is IParameterReferenceOperation p)result[Key(p.Parameter)]=refined;else if(value is IFlowCaptureReferenceOperation c)result["capture:"+c.Id.GetHashCode()]=refined;}
  if(condition is IUnaryOperation unary&&unary.OperatorKind==UnaryOperatorKind.Not)return Refine(unary.Operand,incoming,!truth);
  if(condition is IIsNullOperation check)Set(check.Operand,truth?Null:Non);
  else if(condition is IBinaryOperation binary&&binary.OperatorKind is BinaryOperatorKind.Equals or BinaryOperatorKind.NotEquals){var target=binary.RightOperand.ConstantValue is { HasValue:true,Value:null }?binary.LeftOperand:binary.LeftOperand.ConstantValue is {HasValue:true,Value:null}?binary.RightOperand:null;if(target is not null)Set(target,truth==(binary.OperatorKind==BinaryOperatorKind.Equals)?Null:Non);}
  else if(condition is IPropertyReferenceOperation property&&NullableValue(property.Instance?.Type)&&property.Property.Name=="HasValue"&&property.Instance is not null)Set(property.Instance,truth?Non:Null);
  else if(condition is IIsPatternOperation pattern){bool negated=false;IPatternOperation p=pattern.Pattern;if(p is INegatedPatternOperation not){p=not.Pattern;negated=true;}bool matched=truth!=negated;if(p is IConstantPatternOperation constant&&constant.Value.ConstantValue is{HasValue:true,Value:null})Set(pattern.Value,matched?Null:Non);else if(p is IDeclarationPatternOperation declaration&&matched){Set(pattern.Value,Non);if(declaration.DeclaredSymbol is not null)result[Key(declaration.DeclaredSymbol)]=new(Non,"Pattern variable",Line(pattern.Syntax));}else if(p is IRecursivePatternOperation&&matched)Set(pattern.Value,Non);}
  return result;
 }
 Fact EvalWithoutReport(IOperation value){var old=reporting;reporting=false;var result=Eval(value);reporting=old;return result;}
 Dictionary<string,Fact> Edge(BasicBlock from,BasicBlock to,Dictionary<string,Fact> outgoing){if(from.BranchValue is null||from.ConditionKind==ControlFlowConditionKind.None)return new(outgoing);bool conditional=from.ConditionalSuccessor?.Destination==to;bool truth=from.ConditionKind==ControlFlowConditionKind.WhenTrue?conditional:!conditional;return Refine(from.BranchValue,outgoing,truth);}
 Fact Analyze(IMethodSymbol symbol,SyntaxNode node,SemanticModel semantic,bool report){captureDefaults=new();model=semantic;file=Path.GetRelativePath(request.root,node.SyntaxTree.FilePath).Replace('\\','/');method=symbol.ToDisplayString();reporting=false;ControlFlowGraph? cfg;
  try{cfg=ControlFlowGraph.Create(node,semantic);}catch(Exception error){diagnostics.Add(file+": CFG unavailable: "+error.Message);unresolved.Add(file+":"+Line(node)+":CFG unavailable");return new();}if(cfg is null)return new();
  var ins=cfg.Blocks.ToDictionary(b=>b.Ordinal,b=>new Dictionary<string,Fact>());var outs=new Dictionary<int,Dictionary<string,Fact>>();bool converged=false;
  Dictionary<string,Fact> Transfer(BasicBlock block,Dictionary<string,Fact> entry){state=new(entry);foreach(var operation in block.Operations)Eval(operation);if(block.BranchValue is not null)Eval(block.BranchValue);return new(state);}
  for(int pass=0;pass<Math.Clamp(request.maxIterations,1,64);pass++){bool changed=false;foreach(var block in cfg.Blocks){if(!block.IsReachable)continue;Dictionary<string,Fact>? incoming=null;foreach(var predecessor in block.Predecessors)if(outs.TryGetValue(predecessor.Source.Ordinal,out var prior))incoming=Join(incoming,Edge(predecessor.Source,block,prior));incoming??=new();ins[block.Ordinal]=incoming;var outgoing=Transfer(block,incoming);if(!outs.TryGetValue(block.Ordinal,out var previous)||!Same(outgoing,previous)){changed=true;outs[block.Ordinal]=outgoing;}}if(!changed){converged=true;break;}}if(!converged)budget=true;
  reporting=report;Fact? returned=null;foreach(var block in cfg.Blocks){if(!block.IsReachable)continue;Transfer(block,ins[block.Ordinal]);if(block.FallThroughSuccessor?.Semantics==ControlFlowBranchSemantics.Return&&block.BranchValue is not null){var value=Eval(block.BranchValue);returned=returned is null?value:Merge(returned,value);}}
  return returned??Contract(symbol.ReturnType,symbol.ReturnNullableAnnotation);
 }
 public object Run(){var paths=request.files.Select(f=>Path.GetFullPath(Path.Combine(request.root,f))).ToHashSet();var options=new CSharpParseOptions(LanguageVersion.Latest);NullableContextOptions nullable=NullableContextOptions.Disable;
  var projects=Directory.GetFiles(request.root,"*.csproj");if(projects.Length>1)missing.Add("Multiple projects in one directory need explicit project disambiguation");
  foreach(var p in projects){try{var xml=XDocument.Load(p);if(xml.Descendants().Any(e=>e.Attribute("Condition") is not null))missing.Add("Conditional MSBuild configuration is not evaluated");if(xml.Descendants().Any(e=>e.Name.LocalName is "Compile" or "ProjectReference"))missing.Add("Explicit Compile/ProjectReference metadata requires resolution; no MSBuild targets are executed");var text=xml.Descendants().FirstOrDefault(e=>e.Name.LocalName=="Nullable")?.Value;if(text=="enable")nullable=NullableContextOptions.Enable;var lang=xml.Descendants().FirstOrDefault(e=>e.Name.LocalName=="LangVersion")?.Value;if(lang is not null&&LanguageVersionFacts.TryParse(lang,out var parsed))options=options.WithLanguageVersion(parsed);var framework=xml.Descendants().FirstOrDefault(e=>e.Name.LocalName=="TargetFramework")?.Value;if(framework is not null&&framework!="net10.0")missing.Add("Target reference assemblies not resolved for "+framework+"; current runtime references used as partial fallback");}catch(Exception e){missing.Add("Project metadata: "+e.Message);}}
  var trees=new List<SyntaxTree>();var failed=new HashSet<string>();foreach(var path in paths){try{var tree=CSharpSyntaxTree.ParseText(File.ReadAllText(path),options,path);if(tree.GetDiagnostics().Any(d=>d.Severity==DiagnosticSeverity.Error)){failed.Add(path);diagnostics.Add(Path.GetRelativePath(request.root,path)+": syntax errors");}else trees.Add(tree);}catch(Exception e){failed.Add(path);diagnostics.Add(e.Message);}}
  var references=((string?)AppContext.GetData("TRUSTED_PLATFORM_ASSEMBLIES")??"").Split(Path.PathSeparator,StringSplitOptions.RemoveEmptyEntries).Select(p=>MetadataReference.CreateFromFile(p)).ToList();var assets=Path.Combine(request.root,"obj/project.assets.json");if(File.Exists(assets)){try{using var json=JsonDocument.Parse(File.ReadAllText(assets));var root=json.RootElement;foreach(var target in root.GetProperty("targets").EnumerateObject())foreach(var library in target.Value.EnumerateObject()){if(!library.Value.TryGetProperty("compile",out var compile))continue;foreach(var item in compile.EnumerateObject()){if(!item.Name.EndsWith(".dll",StringComparison.OrdinalIgnoreCase))continue;var reference=root.GetProperty("packageFolders").EnumerateObject().Select(folder=>Path.Combine(folder.Name,library.Name.ToLowerInvariant(),item.Name)).FirstOrDefault(File.Exists);if(reference is not null)references.Add(MetadataReference.CreateFromFile(reference));else missing.Add("Missing restored reference "+library.Name+"/"+item.Name);}}}catch(Exception e){missing.Add("Restore assets: "+e.Message);}}
  int unmodeled=trees.Sum(t=>t.GetRoot().DescendantNodes().Count(n=>n is LocalFunctionStatementSyntax or AnonymousFunctionExpressionSyntax));if(unmodeled>0){diagnostics.Add("Local function/lambda CFGs not yet lowered: "+unmodeled);unresolved.Add("Unmodeled local/lambda executable bodies");}
  var compilation=CSharpCompilation.Create("NullGuardAnalysis",trees,references,new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary,nullableContextOptions:nullable));foreach(var error in compilation.GetDiagnostics().Where(d=>d.Severity==DiagnosticSeverity.Error))missing.Add(error.ToString());
  foreach(var tree in trees){var semantic=compilation.GetSemanticModel(tree);foreach(var node in tree.GetRoot().DescendantNodes().Where(n=>n is BaseMethodDeclarationSyntax||n is AccessorDeclarationSyntax)){if((node is BaseMethodDeclarationSyntax bm && (bm.Body!=null||bm.ExpressionBody!=null) || node is AccessorDeclarationSyntax ac && (ac.Body!=null||ac.ExpressionBody!=null)) && semantic.GetDeclaredSymbol(node) is IMethodSymbol symbol)methods[symbol.OriginalDefinition]=(node,semantic);}}
  bool fixedPoint=false;for(int pass=0;pass<Math.Clamp(request.maxIterations,1,64);pass++){bool changed=false;foreach(var entry in methods){var value=Analyze(entry.Key,entry.Value.Item1,entry.Value.Item2,false);if(!summaries.TryGetValue(entry.Key,out var old)||old.Mask!=value.Mask)changed=true;summaries[entry.Key]=value;}if(!changed){fixedPoint=true;break;}}if(!fixedPoint)budget=true;foreach(var entry in methods)Analyze(entry.Key,entry.Value.Item1,entry.Value.Item2,true);
  diagnostics.Add("No MSBuild execution, source generators, dynamic dispatch or generated source analysis");bool partial=failed.Count>0||missing.Count>0||budget||unresolved.Count>0;return new{protocolVersion="1.0",analyzerVersion="2.0.0-preview.1",analyzer="csharp",project=request.project,languages=new[]{"csharp"},status=partial?"partial":"complete",findings=findings.Values.ToArray(),dependencies=Array.Empty<object>(),diagnostics=diagnostics.ToArray(),coverage=new Dictionary<string,object>{{"csharp",new{discoveredFiles=request.files.Length,analyzedFiles=trees.Count,failedFiles=failed.Count,unsupportedFiles=0,unresolvedSymbols=unresolved.Count,budgetExhausted=budget,missingDependencies=missing.ToArray()}}},capabilities=new{safetyAnalysis=true,callGraph=false,stabilityScore=false,openApi=false,vulnerabilityReachability=false}};
 }
}
static class Program { static void Main(){var request=JsonSerializer.Deserialize<Request>(Console.In.ReadToEnd())??throw new ArgumentException("Missing request");if(request.protocolVersion!="1.0")throw new ArgumentException("Unsupported protocol");Console.Write(JsonSerializer.Serialize(new Analyzer(request).Run()));} }
