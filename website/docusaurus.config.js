const {themes} = require('prism-react-renderer');

module.exports = {
  title: 'NullGuard',
  tagline: 'Follow the risk. Find the fix.',
  url: 'https://techbhaskar.github.io',
  baseUrl: '/null-guard/',
  organizationName: 'techbhaskar',
  projectName: 'null-guard',
  trailingSlash: true,
  favicon: 'img/mark.svg',
  onBrokenLinks: 'throw',
  markdown: {format: 'md', mermaid: true, hooks: {onBrokenMarkdownLinks: 'throw'}},
  themes: ['@docusaurus/theme-mermaid'],
  presets: [['classic', {
    docs: {path: '.generated-docs', sidebarPath: './sidebars.js', showLastUpdateTime: false},
    blog: false,
    theme: {customCss: './src/css/custom.css'},
    sitemap: {changefreq: 'weekly', priority: 0.5, ignorePatterns: ['**/search/**']},
  }]],
  themeConfig: {
    image: 'img/social-card.svg',
    metadata: [
      {name: 'keywords', content: 'NullGuard, static analysis, null safety, Java, Python, TypeScript, JavaScript, C#, agent skill'},
      {name: 'theme-color', content: '#103c32'},
    ],
    colorMode: {defaultMode: 'light', respectPrefersColorScheme: false},
    navbar: {
      title: 'NullGuard',
      logo: {alt: 'NullGuard', src: 'img/mark.svg'},
      items: [
        {to: '/docs/getting-started', label: 'Documentation', position: 'left'},
        {to: '/docs/languages', label: 'Languages', position: 'left'},
        {to: '/docs/agent-skill', label: 'Agent skill', position: 'left'},
        {to: '/search', label: 'Search', position: 'right'},
        {href: 'https://github.com/techbhaskar/null-guard', label: 'GitHub ↗', position: 'right'},
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {title: 'Start building', items: [
          {label: 'Quick start', to: '/docs/getting-started'},
          {label: 'Language support', to: '/docs/languages'},
          {label: 'Agent skill', to: '/docs/agent-skill'},
        ]},
        {title: 'Understand the analysis', items: [
          {label: 'Java developer guide', to: '/docs/reference/java-guide'},
          {label: 'Multi-language guide', to: '/docs/reference/multilang'},
          {label: 'Accuracy and limitations', to: '/docs/reference/evaluation/accuracy-protocol'},
        ]},
        {title: 'Open source', items: [
          {label: 'Source code', href: 'https://github.com/techbhaskar/null-guard'},
          {label: 'Releases', href: 'https://github.com/techbhaskar/null-guard/releases'},
          {label: 'Report an issue', href: 'https://github.com/techbhaskar/null-guard/issues'},
        ]},
      ],
      copyright: `© ${new Date().getFullYear()} Bhaskararao. Built in the open. MIT licensed.`,
    },
    prism: {theme: themes.github, darkTheme: themes.dracula, additionalLanguages: ['java', 'bash', 'powershell', 'python', 'csharp', 'typescript', 'groovy', 'kotlin', 'rust', 'go', 'json']},
  },
};
