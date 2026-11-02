import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import Head from '@docusaurus/Head';
import useBaseUrl from '@docusaurus/useBaseUrl';

const languages = [
  ['Java', 'Core engine', 'Control flow, call graphs, API exposure and stability scoring.'],
  ['Python', 'Experimental', 'Nullable values and source-backed evidence.'],
  ['JavaScript / TypeScript', 'Experimental', 'JS, TS, JSX and TSX, using project configuration.'],
  ['C# / .NET', 'Experimental', 'SDK-style C# projects with coverage diagnostics.'],
  ['Go / Rust', 'Experimental', 'Optional packs with explicit capability boundaries.'],
];

function RiskPath() {
  return <div className="risk-panel" aria-label="Illustrative Java call path from controller through service to repository">
    <div className="panel-top"><span className="panel-dot" /> <span>FOLLOW THE RISK</span><span className="panel-caption">Java · example</span></div>
    <div className="path-row"><span className="node-icon">01</span><div><span className="node-layer">API ENTRY POINT</span><strong>OrdersController</strong></div><span className="node-kind">GET /orders</span></div>
    <div className="path-connector"><span /> caller exposure</div>
    <div className="path-row"><span className="node-icon">02</span><div><span className="node-layer">BUSINESS LOGIC</span><strong>OrderService</strong></div><span className="node-kind">lookup()</span></div>
    <div className="path-connector"><span /> downstream risk</div>
    <div className="path-row path-row-risk"><span className="node-icon">03</span><div><span className="node-layer">DATA BOUNDARY</span><strong>OrderRepository</strong></div><span className="risk-tag">nullable return</span></div>
    <div className="panel-note"><span>↳</span> One local finding. A wider path to investigate.</div>
  </div>;
}

export default function Home() {
  const image = useBaseUrl('/img/social-card.svg', {absolute: true});
  return <Layout title="Static analysis with context" description="Find null-handling risks with NullGuard. Java stability analysis, experimental Python, JavaScript, TypeScript, C#, Go and Rust packs, and a free coding-agent skill.">
    <Head><meta property="og:image" content={image} /><script type="application/ld+json">{JSON.stringify({
      '@context': 'https://schema.org', '@type': 'SoftwareSourceCode', name: 'NullGuard',
      description: 'Static stability analysis for Java with experimental multi-language safety analyzers.',
      codeRepository: 'https://github.com/techbhaskar/null-guard',
      license: 'https://opensource.org/license/mit',
      programmingLanguage: ['Java', 'Python', 'JavaScript', 'TypeScript', 'C#', 'Go', 'Rust'],
    })}</script></Head>
    <main>
      <section className="home-hero home-shell">
        <div className="hero-copy">
          <div className="eyebrow"><span /> OPEN SOURCE. SOURCE AWARE.</div>
          <h1>Follow the risk.<br /><span>Find the fix.</span></h1>
          <p className="hero-lead">A null warning is a starting point.<br />Understand the path, inspect the evidence,<br className="desktop-break" /> and fix what matters.</p>
          <div className="hero-actions"><Link className="button button--primary button--lg" to="/docs/getting-started">Get started <span aria-hidden="true">↗</span></Link><Link className="hero-secondary" to="https://github.com/techbhaskar/null-guard">Explore the source <span aria-hidden="true">→</span></Link></div>
          <div className="hero-meta"><span>MIT licensed</span><span>Runs statically</span><span>Your code stays local</span></div>
        </div>
        <RiskPath />
      </section>
      <div className="language-strip"><div className="home-shell"><span className="strip-label">ONE TOOLKIT. DIFFERENT CAPABILITIES.</span><p>Java <span>·</span> Python <span>·</span> JavaScript <span>·</span> TypeScript <span>·</span> C# <span>·</span> Go <span>·</span> Rust</p></div></div>
      <section className="home-section home-shell">
        <div className="section-intro"><div><span className="eyebrow">BEYOND THE WARNING</span><h2>More context.<br />Better decisions.</h2></div><p>Java analysis follows risk across method boundaries. Optional language packs bring source evidence and coverage diagnostics to more of your repository.</p></div>
        <div className="principles">
          <article><span className="principle-number">01 / TRACE</span><h3>See the bigger path.</h3><p>Inspect Java call chains, supported API entry points and shared hotspots. Prioritize with caller exposure, alongside source evidence.</p><Link to="/docs/reference/java-guide">Explore Java analysis →</Link></article>
          <article><span className="principle-number">02 / VERIFY</span><h3>Keep the gaps visible.</h3><p>Failed files, unresolved symbols and missing dependencies stay in the report. Incomplete analysis is never evidence of safety.</p><Link to="/docs/reference/multilang">Read the report guide →</Link></article>
          <article><span className="principle-number">03 / IMPROVE</span><h3>Turn evidence into action.</h3><p>Review the source, make a focused fix, add a regression test, and rerun. Bring the same workflow into your agent and CI.</p><Link to="/docs/agent-skill">Meet the agent skill →</Link></article>
        </div>
      </section>
      <section className="language-section"><div className="home-shell language-layout"><div><span className="eyebrow">KNOW YOUR SCOPE</span><h2>Your repository.<br />Clearly defined coverage.</h2><p>Use the Java engine on its own, or install the packs your repository needs. Mixed projects are analyzed independently.</p><Link className="text-link" to="/docs/languages">Language support & limitations →</Link></div><div className="language-table">{languages.map(([name, status, description]) => <div className="language-row" key={name}><div><h3>{name}</h3><p>{description}</p></div><span className={status === 'Core engine' ? 'status-core' : 'status-preview'}>{status}</span></div>)}</div></div></section>
      <section className="home-section home-shell quickstart-section"><div><span className="eyebrow">A SMALL FIRST STEP</span><h2>Start with one report.</h2><p>Run Java analysis without changing your Maven configuration. Review findings before introducing enforcement.</p><Link className="text-link" to="/docs/getting-started">Find your setup →</Link></div><div className="terminal"><div className="terminal-title"><span>Terminal</span><span>Java 21+ · Maven</span></div><pre><code><span className="code-comment"># Report-only. No POM changes needed.</span>{'\n'}mvn com.arinits.nullguard:nullguard-maven-plugin:2.0.0:analyze -Dnullguard.failBuild=false</code></pre><p>Open <code>target/nullguard/nullguard-dashboard-latest.html</code></p></div></section>
      <section className="agent-banner home-shell"><div><span className="eyebrow">A WORKFLOW YOUR AGENT CAN USE</span><h2>From “scan this” to a verified fix.</h2><p>A free skill for Java, Python, JavaScript, TypeScript and C#. Source review, requested fixes, tests and report-only CI.</p></div><Link className="button button--primary button--lg" to="/docs/agent-skill">Install the skill ↗</Link></section>
    </main>
  </Layout>;
}
