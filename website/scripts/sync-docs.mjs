import {readFile, writeFile, mkdir, readdir, rm, copyFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const site = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = path.resolve(site, '..');
const output = path.join(site, '.generated-docs');
const repo = 'https://github.com/techbhaskar/null-guard/blob/master/';
const sources = new Map();
const slash = value => value.split(path.sep).join('/');

async function collect(directory, prefix) {
  for (const entry of await readdir(directory, {withFileTypes: true})) {
    const source = path.join(directory, entry.name);
    const target = path.posix.join(prefix, entry.name === 'README.md' ? 'index.md' : entry.name);
    if (entry.isDirectory()) await collect(source, target);
    else if (entry.name.endsWith('.md')) sources.set(source, target);
  }
}

await collect(path.join(root, 'docs'), 'reference');
await collect(path.join(site, 'docs'), '');
sources.set(path.join(root, 'README.md'), 'reference/java-guide.md');
sources.set(path.join(root, 'nullguard-gradle-plugin/README.md'), 'integrations/gradle.md');
sources.set(path.join(root, 'skills/nullguard-audit/SKILL.md'), 'agent-skill/workflow.md');
await collect(path.join(root, 'skills/nullguard-audit/references'), 'agent-skill/references');

await rm(output, {recursive: true, force: true});
await mkdir(output, {recursive: true});
const index = [];
for (const [source, target] of sources) {
  const raw = await readFile(source, 'utf8');
  const title = raw.match(/^#\s+(.+)$/m)?.[1]?.replace(/`/g, '') ?? path.basename(target, '.md');
  const slug = target.replace(/\.md$/, '').replace(/\/index$/, '');
  // Keep source Markdown untouched. Only the generated website copy gets routing metadata.
  let body = raw.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n/, '');
  body = body.split(/(^\s*`{3,}[^\n]*\n[\s\S]*?^\s*`{3,}\s*$|^\s*~{3,}[^\n]*\n[\s\S]*?^\s*~{3,}\s*$)/m).map(part => {
    if (/^\s*(?:`{3,}|~{3,})/.test(part)) return part;
    return part.replace(/(!?\[[^\]]*\]\()([^\s)]+)(\))/g, (whole, start, destination, end) => {
      if (/^(?:[a-z]+:|\/|#)/i.test(destination)) return whole;
      const [file, fragment] = destination.split('#', 2);
      const resolved = path.resolve(path.dirname(source), decodeURI(file));
      const mapped = sources.get(resolved);
      if (mapped) {
        const relative = slash(path.relative(path.dirname(target), mapped));
        return start + relative + (fragment ? '#' + fragment : '') + end;
      }
      const relative = slash(path.relative(root, resolved));
      const url = start.startsWith('!') ? 'https://raw.githubusercontent.com/techbhaskar/null-guard/master/' : repo;
      return start + url + relative + (fragment ? '#' + fragment : '') + end;
    });
  }).join('');
  const destination = path.join(output, target);
  await mkdir(path.dirname(destination), {recursive: true});
  const original = slash(path.relative(root, source));
  const text = body.replace(/```mermaid[\s\S]*?```/g, ' ')
    .replace(/^\s*```[^\n]*$/gm, ' ').replace(/^#{1,6}\s+/gm, '')
    .replace(/!\[[^\]]*\]\([^)]+\)/g, ' ').replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/[*`|>]/g, ' ').replace(/\s+/g, ' ').trim();
  const description = text.replace(title, '').replace(/\\/g, ' ').trim().slice(0, 170);
  await writeFile(destination, `---\ntitle: ${JSON.stringify(title)}\ndescription: ${JSON.stringify(description)}\nslug: /${slug}\ncustom_edit_url: ${JSON.stringify('https://github.com/techbhaskar/null-guard/edit/master/' + original)}\n---\n\n${body}`);
  index.push({title, url: 'docs/' + slug + '/', text: text.slice(0, 24000)});
}
await mkdir(path.join(site, 'static/downloads'), {recursive: true});
await writeFile(path.join(site, 'static/search-index.json'), JSON.stringify(index));
await copyFile(path.join(root, 'target/skills/nullguard-audit.zip'), path.join(site, 'static/downloads/nullguard-audit.zip')).catch(error => {
  if (error.code !== 'ENOENT') throw error;
  console.log('Skill ZIP not present; build it with python3 scripts/package_nullguard_skill.py before publishing.');
});
console.log(`Prepared ${sources.size} Markdown pages from repository sources.`);
