import React, {useEffect, useMemo, useState} from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';

export default function Search() {
  const [query, setQuery] = useState('');
  const [documents, setDocuments] = useState([]);
  const [state, setState] = useState('loading');
  const indexUrl = useBaseUrl('/search-index.json');
  const baseUrl = useBaseUrl('/');
  useEffect(() => {
    const controller = new AbortController();
    fetch(indexUrl, {signal: controller.signal}).then(response => {
      if (!response.ok) throw new Error('Search index unavailable');
      return response.json();
    }).then(data => {setDocuments(data); setState('ready');}).catch(error => {
      if (error.name !== 'AbortError') setState('error');
    });
    return () => controller.abort();
  }, [indexUrl]);
  const results = useMemo(() => {
    const words = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
    if (!words.length) return documents.filter(doc => ['docs/getting-started/', 'docs/languages/', 'docs/agent-skill/'].includes(doc.url));
    return documents.map(doc => ({...doc, score:
      (['docs/getting-started/', 'docs/languages/', 'docs/agent-skill/', 'docs/reference/multilang/'].includes(doc.url) ? 8 : 0) +
      words.reduce((sum, word) => sum + (doc.title.toLowerCase().includes(word) ? 20 : 0) +
        (doc.url.toLowerCase().includes(word) ? 5 : 0) + Math.min(5, doc.text.toLowerCase().split(word).length - 1), 0)}))
      .filter(doc => words.every(word => (doc.title + ' ' + doc.text).toLowerCase().includes(word)))
      .sort((a, b) => b.score - a.score).slice(0, 20);
  }, [query, documents]);
  return <Layout title="Search documentation" description="Search NullGuard setup guides, language support, reports, releases and agent skill documentation."><main className="search-page home-shell">
    <span className="eyebrow">FIND YOUR NEXT STEP</span><h1>Search the docs.</h1>
    <label htmlFor="docs-search">What are you looking for?</label>
    <input id="docs-search" className="docs-search-input" type="search" placeholder="Try Python, Gradle, coverage, or agent skill…" value={query} onChange={event => setQuery(event.target.value)} />
    <p role="status" aria-live="polite">{state === 'loading' ? 'Loading documentation…' : state === 'error' ? 'Search could not load. Open Documentation in the navigation to browse the guides.' : query.trim() ? `${results.length}${results.length === 20 ? '+' : ''} matching pages` : 'Popular starting points'}</p>
    <div className="search-results">{state === 'ready' && !results.length ? <p>No matching pages. Try a language name or a shorter phrase.</p> : results.map(doc => <article key={doc.url}><Link to={baseUrl + doc.url}><h2>{doc.title}</h2></Link><p>{doc.text.slice(0, 230)}…</p></article>)}</div>
  </main></Layout>;
}
