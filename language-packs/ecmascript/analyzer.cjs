'use strict';
const ts = require('typescript'), fs = require('node:fs'), path = require('node:path');
const N=1,U=2,V=4,ANY=7, VERSION='2.0.0-preview.1';
const value=(mask=ANY,origin='No resolved nullability contract',line=1)=>({mask,origin,line});
const merge=(a,b)=>value(a.mask|b.mask,a.origin===b.origin?a.origin:('Joined paths: '+a.origin+'; '+b.origin).slice(0,500),Math.min(a.line,b.line));
function sameState(a,b){return a.size===b.size&&[...a].every(([key,v])=>b.has(key)&&b.get(key).mask===v.mask);}
function join(a,b){if(!a)return b&&new Map(b);if(!b)return new Map(a);const out=new Map();for(const key of new Set([...a.keys(),...b.keys()]))out.set(key,merge(a.get(key)||value(),b.get(key)||value()));return out;}
class Analyzer {
 constructor(request){this.r=request;this.findings=new Map();this.diagnostics=[];this.missing=new Set();this.unresolved=new Set();this.failed=new Set();this.unsupported=new Set();this.budgetExhausted=false;this.functions=new Map();this.summaries=new Map();this.reporting=true;this.returns=[];this.budget=Math.min(64,Math.max(1,request.maxIterations||64));}
 binding(node){return this.checker.getSymbolAtLocation(node)||node.text;}
 language(file){return /\.(ts|tsx|mts|cts)$/.test(file)?'typescript':'javascript';}
 line(node){const source=node.getSourceFile();return source.getLineAndCharacterOfPosition(node.getStart(source)).line+1;}
 report(node,v,rule='001',message){if(!this.reporting||!(v.mask&3)||!v.mask)return;const pos=this.source.getLineAndCharacterOfPosition(node.getStart(this.source));const line=this.source.text.split(/\r?\n/)[pos.line]||'';const col=Array.from(line.slice(0,pos.character)).length+1;const f={ruleId:'NG-ECMA-'+rule,language:this.language(this.file),severity:'warning',confidence:'experimental',methodId:this.file+'#'+this.current,message:message||((v.mask&V)?'Possible':'Definite')+' null/undefined dereference',location:{path:this.file,startLine:pos.line+1,startColumn:col},evidence:[v.origin,'Nullability mask: '+v.mask,'Unsafe operation at line '+(pos.line+1)],relatedLocations:v.origin==='No resolved nullability contract'||v.line===1&&v.origin.startsWith('Joined paths:')?[]:[{path:this.file,startLine:v.line,startColumn:1}]};this.findings.set(JSON.stringify([f.location,f.ruleId,f.message]),f);}
 typeValue(node){try{let type=this.checker.getTypeAtLocation(node);const parts=type.isUnion()?type.types:[type];let mask=0;for(const t of parts){if(t.flags&ts.TypeFlags.Null)mask|=N;else if(t.flags&(ts.TypeFlags.Undefined|ts.TypeFlags.Void))mask|=U;else if(t.flags&(ts.TypeFlags.Any|ts.TypeFlags.Unknown|ts.TypeFlags.TypeParameter))mask|=ANY;else mask|=V;}return value(mask||ANY,'Compiler type: '+this.checker.typeToString(type),this.line(node));}catch{return value();}}
 key(decl){return path.resolve(decl.getSourceFile().fileName)+':'+decl.pos;}
 declared(node){let mask=this.typeValue(node).mask;if(node.questionToken)mask|=U;const t=node.type;if(t){const text=t.getText(this.source);if(/\bnull\b/.test(text))mask|=N;if(/\bundefined\b/.test(text))mask|=U;}return value(mask,'Declared parameter '+node.name.getText(this.source),this.line(node));}
 evaluate(node,state){if(!node)return value(U,'Implicit undefined',1);const line=this.line(node);
  if(ts.isParenthesizedExpression(node)||ts.isAsExpression(node)||ts.isTypeAssertionExpression(node)||ts.isSatisfiesExpression(node))return this.evaluate(node.expression,state);
  if(ts.isIdentifier(node)){if(node.text==='undefined'&&!this.checker.getSymbolAtLocation(node)?.declarations?.some(d=>!d.getSourceFile().isDeclarationFile))return value(U,'undefined literal',line);return state.get(this.binding(node))||this.typeValue(node);}
  if(node.kind===ts.SyntaxKind.NullKeyword)return value(N,'null literal',line);
  if(node.kind===ts.SyntaxKind.ThisKeyword||ts.isLiteralExpression(node)||[ts.SyntaxKind.TrueKeyword,ts.SyntaxKind.FalseKeyword].includes(node.kind))return value(V,'Literal or this',line);
  if(ts.isNonNullExpression(node)){const v=this.evaluate(node.expression,state);this.report(node,v,'002','Non-null assertion hides a possibly null/undefined value');return v;}
  if(ts.isPropertyAccessExpression(node)||ts.isElementAccessExpression(node)){
   const receiver=this.evaluate(node.expression,state), continuation=!!(node.flags&ts.NodeFlags.OptionalChain)&&receiver.short;
   if(!node.questionDotToken)this.report(node,continuation?{...receiver,mask:receiver.innerMask}:receiver);
   if(ts.isElementAccessExpression(node))this.evaluate(node.argumentExpression,state);
   let result=this.typeValue(node);
   if(ts.isPropertyAccessExpression(node)){const symbol=this.checker.getSymbolAtLocation(node.name);if(symbol){const type=this.checker.getTypeOfSymbolAtLocation(symbol,node.name),parts=type.isUnion()?type.types:[type];let mask=0;for(const t of parts)mask|=t.flags&ts.TypeFlags.Null?N:t.flags&(ts.TypeFlags.Undefined|ts.TypeFlags.Void)?U:t.flags&(ts.TypeFlags.Any|ts.TypeFlags.Unknown|ts.TypeFlags.TypeParameter)?ANY:V;result={...result,mask:mask||ANY};}}
   const short=continuation||!!node.questionDotToken&&!!(receiver.mask&3);return {...result,innerMask:result.mask,mask:result.mask|(short?U:0),short};
  }
  if(ts.isCallExpression(node)){
   if(ts.isPropertyAccessExpression(node.expression)||ts.isElementAccessExpression(node.expression)){const callee=this.evaluate(node.expression,state);if(!node.questionDotToken)this.report(node.expression,callee.short?{...callee,mask:callee.innerMask}:callee,'004','Possibly null/undefined callable');}
   else {const callee=this.evaluate(node.expression,state);if(!node.questionDotToken)this.report(node.expression,callee,'004','Possibly null/undefined callable');}
   const args=node.arguments.map(a=>this.evaluate(a,state));const signature=this.checker.getResolvedSignature(node),decl=signature?.getDeclaration();
   if(decl&&this.functions.has(this.key(decl))&&(ts.isFunctionDeclaration(decl)||ts.isArrowFunction(decl)||ts.isFunctionExpression(decl))){
    const target=this.functions.get(this.key(decl));for(let i=0;i<Math.min(args.length,target.parameters.length);i++){const parameter=target.parameters[i];let required=this.typeValue(parameter).mask;if(parameter.questionToken||parameter.initializer)required|=U;const annotation=parameter.type?.getText(parameter.getSourceFile())||'';if(/\bnull\b/.test(annotation))required|=N;if(/\bundefined\b/.test(annotation))required|=U;if(args[i].mask&3&~required)this.report(node,args[i],'003','Nullable argument violates source parameter contract');}
    const summary=this.summaries.get(this.key(decl))||value();return {...summary,line,origin:'Source return consumed at call: '+summary.origin};
   }
   if(decl&&this.functions.has(this.key(decl))&&!(ts.isFunctionDeclaration(decl)||ts.isArrowFunction(decl)||ts.isFunctionExpression(decl)))this.unresolved.add(this.file+':'+line+':unresolved method dispatch');
   if(!decl)this.unresolved.add(this.file+':'+line+':'+node.expression.getText(this.source));
   let result=this.typeValue(node);if(node.questionDotToken)result=merge(result,value(U,'Optional call may not execute',line));return result;
  }
  if(ts.isNewExpression(node)){(node.arguments||[]).forEach(a=>this.evaluate(a,state));return value(V,'Constructed object',line);}
  if(ts.isConditionalExpression(node)){this.evaluate(node.condition,state);return merge(this.evaluate(node.whenTrue,this.refine(node.condition,state,true)),this.evaluate(node.whenFalse,this.refine(node.condition,state,false)));}
  if(ts.isBinaryExpression(node)){
   const op=node.operatorToken.kind;
   if(op===ts.SyntaxKind.EqualsToken){const v=this.evaluate(node.right,state);this.assign(node.left,v,state);return v;}
   const left=this.evaluate(node.left,state);
   if(op===ts.SyntaxKind.QuestionQuestionToken){const right=this.evaluate(node.right,state);return left.mask&3?merge(value(left.mask&V,left.origin,left.line),right):left;}
   if(op===ts.SyntaxKind.AmpersandAmpersandToken||op===ts.SyntaxKind.BarBarToken){const right=this.evaluate(node.right,this.refine(node.left,state,op===ts.SyntaxKind.AmpersandAmpersandToken));return op===ts.SyntaxKind.BarBarToken?merge(value(left.mask&V,left.origin,left.line),right):merge(left,right);}
   this.evaluate(node.right,state);return value(V,'Scalar expression completed',line);
  }
  if(ts.isArrowFunction(node)||ts.isFunctionExpression(node))return value(V,'Function object',line);
  if(ts.isObjectLiteralExpression(node)||ts.isArrayLiteralExpression(node)){ts.forEachChild(node,c=>{if(ts.isExpression(c))this.evaluate(c,state);else if(ts.isPropertyAssignment(c))this.evaluate(c.initializer,state);});return value(V,'Constructed container',line);}
  if(ts.isJsxElement(node)||ts.isJsxSelfClosingElement(node)||ts.isJsxFragment(node)){
   const visit=child=>{if(ts.isJsxExpression(child)){if(child.expression)this.evaluate(child.expression,state);}else ts.forEachChild(child,visit);};ts.forEachChild(node,visit);return value(V,'JSX element constructed',line);
  }
  if(ts.isAwaitExpression(node))return this.evaluate(node.expression,state);
  if(ts.isPrefixUnaryExpression(node)||ts.isPostfixUnaryExpression(node)){this.evaluate(node.operand,state);return value(V,'Unary result',line);}
  ts.forEachChild(node,c=>{if(ts.isExpression(c))this.evaluate(c,state);});return this.typeValue(node);
 }
 assign(target,v,state){if(ts.isIdentifier(target))state.set(this.binding(target),v);else if(ts.isObjectBindingPattern(target)||ts.isArrayBindingPattern(target)){target.elements.forEach(e=>{if(ts.isBindingElement(e))this.assign(e.name,value(ANY,'Destructured value',this.line(e)),state);});}else if(ts.isPropertyAccessExpression(target)||ts.isElementAccessExpression(target))this.evaluate(target.expression,state);}
 refine(test,state,truth){const out=new Map(state);
  if(ts.isCallExpression(test)&&truth&&ts.isPropertyAccessExpression(test.expression)&&test.expression.name.text==='isArray'&&ts.isIdentifier(test.expression.expression)&&test.expression.expression.text==='Array'&&test.arguments.length===1&&ts.isIdentifier(test.arguments[0])){
   const symbol=this.checker.getSymbolAtLocation(test.expression.expression);
   if(symbol?.declarations?.every(d=>d.getSourceFile().isDeclarationFile&&/lib\..*\.d\.ts$/.test(d.getSourceFile().fileName))){const target=test.arguments[0],old=state.get(this.binding(target))||this.typeValue(target);out.set(this.binding(target),value(old.mask&V,'Standard Array.isArray guard',this.line(test)));return out;}
  }
if(ts.isParenthesizedExpression(test))return this.refine(test.expression,state,truth);
  if(ts.isPrefixUnaryExpression(test)&&test.operator===ts.SyntaxKind.ExclamationToken)return this.refine(test.operand,state,!truth);
  if(ts.isIdentifier(test)&&truth){const v=out.get(this.binding(test))||this.typeValue(test);out.set(this.binding(test),value(v.mask&V,'Truthy guard',this.line(test)));return out;}
  if(ts.isBinaryExpression(test)){
   const op=test.operatorToken.kind;
   if(op===ts.SyntaxKind.AmpersandAmpersandToken||op===ts.SyntaxKind.BarBarToken){const guaranteed=(op===ts.SyntaxKind.AmpersandAmpersandToken)===truth;return guaranteed?this.refine(test.right,this.refine(test.left,state,truth),truth):join(this.refine(test.left,state,truth),this.refine(test.right,state,truth));}
   const equality=[ts.SyntaxKind.EqualsEqualsToken,ts.SyntaxKind.EqualsEqualsEqualsToken].includes(op),inequality=[ts.SyntaxKind.ExclamationEqualsToken,ts.SyntaxKind.ExclamationEqualsEqualsToken].includes(op);
   if(equality||inequality){let expression=test.left,literal=test.right;if(ts.isStringLiteral(expression))[expression,literal]=[literal,expression];
    if(ts.isTypeOfExpression(expression)&&ts.isIdentifier(expression.expression)&&ts.isStringLiteral(literal)){
     const old=state.get(this.binding(expression.expression))||this.typeValue(expression.expression),positive=truth===equality;
     if(literal.text==='undefined')out.set(this.binding(expression.expression),value(positive?old.mask&U:old.mask&~U,'typeof guard',this.line(test)));
     else if(['string','number','bigint','boolean','symbol','function'].includes(literal.text)&&positive)out.set(this.binding(expression.expression),value(old.mask&V,'typeof guard',this.line(test)));
     return out;
    }
   }
   const equal=[ts.SyntaxKind.EqualsEqualsToken,ts.SyntaxKind.EqualsEqualsEqualsToken].includes(op),different=[ts.SyntaxKind.ExclamationEqualsToken,ts.SyntaxKind.ExclamationEqualsEqualsToken].includes(op);
   let left=test.left,right=test.right;if(right.kind!==ts.SyntaxKind.NullKeyword&&!ts.isIdentifier(right)) [left,right]=[right,left];
   if((equal||different)&&ts.isIdentifier(left)){
    const isNull=right.kind===ts.SyntaxKind.NullKeyword,isUndefined=ts.isIdentifier(right)&&right.text==='undefined';
    if(isNull||isUndefined){const loose=[ts.SyntaxKind.EqualsEqualsToken,ts.SyntaxKind.ExclamationEqualsToken].includes(op);const bits=loose?3:isNull?N:U;const original=state.get(this.binding(left))||this.typeValue(left);const mask=truth===equal?original.mask&bits:original.mask&~bits;out.set(this.binding(left),value(mask,'Branch '+test.getText(this.source)+' is '+truth,this.line(test)));}
   }
  }return out;
 }
 block(statements,incoming){let state=incoming&&new Map(incoming);for(const node of statements){if(!state||[...state.values()].some(v=>v.mask===0))return null;
  if(ts.isVariableStatement(node)){for(const declaration of node.declarationList.declarations){const v=declaration.initializer?this.evaluate(declaration.initializer,state):value(U,'Uninitialized local',this.line(declaration));this.assign(declaration.name,v,state);}}
  else if(ts.isExpressionStatement(node))this.evaluate(node.expression,state);
  else if(ts.isReturnStatement(node)){this.returns.push(this.evaluate(node.expression,state));return null;}
  else if(ts.isThrowStatement(node)){this.evaluate(node.expression,state);return null;}
  else if(ts.isIfStatement(node)){this.evaluate(node.expression,state);state=join(this.statement(node.thenStatement,this.refine(node.expression,state,true)),node.elseStatement?this.statement(node.elseStatement,this.refine(node.expression,state,false)):this.refine(node.expression,state,false));}
  else if(ts.isBlock(node))state=this.block(node.statements,state);
  else if(ts.isWhileStatement(node)||ts.isDoStatement(node)||ts.isForStatement(node)||ts.isForOfStatement(node)||ts.isForInStatement(node)){
   let head=new Map(state);if(node.initializer){if(ts.isVariableDeclarationList(node.initializer)){for(const d of node.initializer.declarations)this.assign(d.name,d.initializer?this.evaluate(d.initializer,head):value(),head);}else this.evaluate(node.initializer,head);}
   const condition=node.condition||(ts.isWhileStatement(node)||ts.isDoStatement(node)?node.expression:null);if(ts.isForOfStatement(node)||ts.isForInStatement(node))this.evaluate(node.expression,head);
   const reporting=this.reporting;this.reporting=false;let previous=head,converged=false;for(let i=0;i<this.budget;i++){if(condition)this.evaluate(condition,previous);const entry=condition?this.refine(condition,previous,true):previous;let body=this.statement(node.statement,entry);if(body&&node.incrementor)this.evaluate(node.incrementor,body);const next=join(head,body);if(sameState(next,previous)){converged=true;break;}previous=next;}if(!converged)this.budgetExhausted=true;this.reporting=reporting;if(reporting){if(condition)this.evaluate(condition,previous);const finalEntry=condition?this.refine(condition,previous,true):previous;const finalBody=this.statement(node.statement,finalEntry);if(finalBody&&node.incrementor)this.evaluate(node.incrementor,finalBody);}state=join(state,previous);
  }
  else if(ts.isTryStatement(node)){let combined=this.block(node.tryBlock.statements,state);if(node.catchClause){const entry=new Map(state);if(node.catchClause.variableDeclaration)this.assign(node.catchClause.variableDeclaration.name,value(ANY,'JavaScript may throw null/undefined',this.line(node.catchClause)),entry);combined=join(combined,this.block(node.catchClause.block.statements,entry));}state=node.finallyBlock?this.block(node.finallyBlock.statements,combined):combined;}
  else if(ts.isBreakStatement(node)||ts.isContinueStatement(node))return state;
  else if(ts.isSwitchStatement(node)){this.evaluate(node.expression,state);let combined=new Map(state);for(const clause of node.caseBlock.clauses)combined=join(combined,this.block(clause.statements,state));state=combined;}
  else if(ts.isFunctionDeclaration(node)||ts.isClassDeclaration(node)||ts.isImportDeclaration(node)||ts.isExportDeclaration(node)||ts.isInterfaceDeclaration(node)||ts.isTypeAliasDeclaration(node)||ts.isEmptyStatement(node)){}
  else this.unresolved.add(this.file+':'+this.line(node)+':unsupported '+ts.SyntaxKind[node.kind]);
 }return state;}
 statement(node,state){return ts.isBlock(node)?this.block(node.statements,state):this.block([node],state);}
 function(fn,reporting){this.source=fn.getSourceFile();this.file=path.relative(this.r.root,this.source.fileName).split(path.sep).join('/');this.current=fn.name?.getText(this.source)||'<anonymous>';this.reporting=reporting;this.returns=[];const state=new Map();for(const p of fn.parameters)this.assign(p.name,this.declared(p),state);
  if(ts.isBlock(fn.body)){if(this.block(fn.body.statements,state)!==null)this.returns.push(value(U,'Function falls through',this.line(fn)));}else this.returns.push(this.evaluate(fn.body,state));
  return this.returns.reduce((a,b)=>merge(a,b),value(0,'No returning path',this.line(fn)));
 }
 run(){
  const configPath=ts.findConfigFile(this.r.root,ts.sys.fileExists,'tsconfig.json')||ts.findConfigFile(this.r.root,ts.sys.fileExists,'jsconfig.json');let options={allowJs:true,noEmit:true};
  if(configPath&&path.resolve(configPath).startsWith(path.resolve(this.r.root)+path.sep)){const read=ts.readConfigFile(configPath,ts.sys.readFile);if(read.error)this.diagnostics.push(ts.flattenDiagnosticMessageText(read.error.messageText,' '));else{const parsed=ts.parseJsonConfigFileContent(read.config,ts.sys,path.dirname(configPath));options={...parsed.options,noEmit:true};if(path.basename(configPath)==='jsconfig.json'&&parsed.options.allowJs===undefined)options.allowJs=true;this.configFiles=new Set(parsed.fileNames.map(f=>path.resolve(f)));if(parsed.errors.length)this.diagnostics.push(...parsed.errors.map(d=>ts.flattenDiagnosticMessageText(d.messageText,' ')));}}
  options.noEmit=true;const names=this.r.files.map(f=>path.resolve(this.r.root,f)).filter(f=>{if(this.configFiles&&!this.configFiles.has(f)||!options.allowJs&&this.language(f)==='javascript'){this.unsupported.add(f);return false;}return true;});this.program=ts.createProgram(names,options);this.checker=this.program.getTypeChecker();const selected=new Set(names);
  for(const diag of this.program.getSyntacticDiagnostics()){if(diag.file&&selected.has(path.resolve(diag.file.fileName))){this.failed.add(path.resolve(diag.file.fileName));this.diagnostics.push(path.relative(this.r.root,diag.file.fileName)+': '+ts.flattenDiagnosticMessageText(diag.messageText,' '));}}
  for(const diag of this.program.getSemanticDiagnostics()){if([2307,2688,6053,2792].includes(diag.code))this.missing.add(ts.flattenDiagnosticMessageText(diag.messageText,' '));}
  for(const source of this.program.getSourceFiles()){if(!selected.has(path.resolve(source.fileName))||this.failed.has(path.resolve(source.fileName)))continue;const visit=node=>{if(ts.isFunctionLike(node)&&node.body){this.functions.set(this.key(node),node);}ts.forEachChild(node,visit);};visit(source);}
  let converged=false;for(let pass=0;pass<this.budget;pass++){let changed=false;for(const [key,fn] of this.functions){const v=this.function(fn,false);if((this.summaries.get(key)||value()).mask!==v.mask)changed=true;this.summaries.set(key,v);}if(!changed){converged=true;break;}}if(!converged)this.budgetExhausted=true;
  for(const source of this.program.getSourceFiles()){if(!selected.has(path.resolve(source.fileName))||this.failed.has(path.resolve(source.fileName)))continue;this.source=source;this.file=path.relative(this.r.root,source.fileName).split(path.sep).join('/');this.current='<module>';this.reporting=true;this.returns=[];this.block(source.statements,new Map());}
  for(const fn of this.functions.values())this.function(fn,true);
  const coverage={};for(const language of this.r.languages){const files=this.r.files.filter(f=>this.language(f)===language);coverage[language]={discoveredFiles:files.length,analyzedFiles:files.filter(f=>!this.failed.has(path.resolve(this.r.root,f))&&!this.unsupported.has(path.resolve(this.r.root,f))).length,failedFiles:files.filter(f=>this.failed.has(path.resolve(this.r.root,f))).length,unsupportedFiles:files.filter(f=>this.unsupported.has(path.resolve(this.r.root,f))).length,unresolvedSymbols:this.unresolved.size,budgetExhausted:this.budgetExhausted,missingDependencies:[...this.missing].sort()};}
  const configurationErrors=this.diagnostics.length;
  if(this.unsupported.size)this.diagnostics.push('Files outside project configuration or disabled by allowJs are unsupported: '+[...this.unsupported].map(f=>path.relative(this.r.root,f)).join(', '));
  this.diagnostics.push('Angular templates, generated source, browser lifecycle and dynamic dispatch are not analyzed');
  return {protocolVersion:'1.0',analyzerVersion:VERSION,analyzer:'ecmascript',project:this.r.project,languages:this.r.languages,status:this.failed.size||this.unsupported.size||configurationErrors||this.missing.size||this.budgetExhausted?'partial':'complete',findings:[...this.findings.values()].sort((a,b)=>JSON.stringify(a.location).localeCompare(JSON.stringify(b.location))||a.ruleId.localeCompare(b.ruleId)),dependencies:[],coverage,diagnostics:this.diagnostics,capabilities:{safetyAnalysis:true,callGraph:false,stabilityScore:false,openApi:false,vulnerabilityReachability:false}};
 }
}
module.exports={Analyzer};
if(require.main===module){if(Number(process.versions.node.split('.')[0])<22)throw Error('Node.js >=22 is required');const request=JSON.parse(fs.readFileSync(0,'utf8'));if(request.protocolVersion!=='1.0')throw Error('Unsupported protocol');process.stdout.write(JSON.stringify(new Analyzer(request).run()));}
