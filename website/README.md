# NullGuard documentation website

Docusaurus 3.10.2 builds this public documentation site for GitHub Pages at
`https://techbhaskar.github.io/null-guard/`.

## Develop

Use Node 20+ and Python 3.10+ (Python packages the downloadable skill).

```sh
python3 scripts/package_nullguard_skill.py
cd website
npm ci
npm start
```

The development server prints the local URL. For a production check:

```sh
npm run build
npm run serve
```

The site reuses repository Markdown. `scripts/sync-docs.mjs` copies docs, the
root README, the Gradle guide and agent skill references into ignored generated
content, adds website routing metadata, rewrites repository links, and builds
the browser-local search index. Edit original Markdown, not `.generated-docs`.
Website-specific introductions live in `website/docs`. The downloadable skill
comes from `target/skills/nullguard-audit.zip`; build it before publishing.

## Publish on GitHub Pages

1. In repository **Settings → Pages → Build and deployment**, select **GitHub Actions**.
2. Push this website and `.github/workflows/deploy-docs.yml` to `master`.
3. The workflow builds and deploys the site. Pull requests build and validate
   content but do not deploy. You can also run the workflow manually on `master`.

No PAT, separate hosting account or custom domain is required. Pages permissions
are limited to the deployment job. Build-time broken links fail the workflow.
The public URL is configured for this repository; changing owner, repository
name or a custom domain requires updating `url`, `baseUrl` and robots.txt.

## Discoverability

Pages are statically rendered with titles, descriptions, canonical URLs,
Open Graph metadata, a sitemap and robots.txt. Search runs in the browser without
an external search account. Publication makes the content crawlable; it does not
guarantee indexing or search ranking. GitHub Pages is a static host: analytics
and search-console verification are not configured or invented.

## Blog and sitemap

The sitemap is generated at `/null-guard/sitemap.xml` (not `sites.xml`). It includes
documentation, public pages and blog routes. Search is excluded. The footer links
to the sitemap and blog RSS feed.

Create a Markdown post in `website/blog/YYYY-MM-DD-short-title.md`, with frontmatter:

```yaml
---
title: A descriptive post title
description: A short summary for search and social metadata.
slug: short-title
authors: [nullguard]
tags: [remediation]
---
```

Write a standalone introduction, then `<!-- truncate -->`, then the full post.
Define new tags in `blog/tags.yml`. Build before publishing to catch broken links
and invalid post metadata. Posts deploy with the existing Pages workflow.
The blog is at `/null-guard/blog/`; feeds are `blog/rss.xml` and `blog/atom.xml`.
