// Static resource/reference inventory. Candidates require review: Android also resolves names at runtime.
// Run from the repository root: node Tools/hgram-audit.mjs
import fs from 'node:fs';
import path from 'node:path';

function* files(root) {
    if (!fs.existsSync(root)) return;
    for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
        const file = path.join(root, entry.name);
        if (entry.isDirectory()) {
            if (!['build', 'jni', '.git', '.codegraph', 'node_modules'].includes(entry.name)) yield* files(file);
        } else if (/\.(java|kt|xml)$/.test(file)) yield file;
    }
}

const references = new Set();
const declarations = new Map();
const duplicates = [];
let sourceFiles = 0;
let hgramFiles = 0;
let runtimeResourceLookups = 0;
for (const file of files('TMessagesProj/src')) {
    const text = fs.readFileSync(file, 'utf8');
    sourceFiles++;
    if (file.includes('/hgram/') && !file.includes('/test/')) hgramFiles++;
    if (/getIdentifier\(/.test(text)) runtimeResourceLookups++;
    for (const match of text.matchAll(/R\.(\w+)\.(\w+)|@(?:\+)?(\w+)\/(\w+)/g)) {
        references.add(`${match[1] || match[3]}/${match[2] || match[4]}`);
    }
    if (/\/res\/values[^/]*\//.test(file)) {
        const seen = new Set();
        for (const match of text.matchAll(/<(string|color|dimen|style|bool|integer)\s+name="([^"]+)"/g)) {
            const key = `${match[1]}/${match[2]}`;
            if (seen.has(key)) duplicates.push(`${file}: ${key}`);
            seen.add(key);
            if (!declarations.has(key)) declarations.set(key, []);
            declarations.get(key).push(file);
        }
    }
}

const hgramCandidates = [...declarations.keys()].filter(key => /\/Hgram/.test(key) && !references.has(key));
const testDir = 'TMessagesProj/build/test-results/testDebugUnitTest';
const tests = [];
if (fs.existsSync(testDir)) {
    for (const file of fs.readdirSync(testDir).filter(name => name.endsWith('.xml'))) {
        const text = fs.readFileSync(path.join(testDir, file), 'utf8');
        const attrs = Object.fromEntries([...text.slice(0, text.indexOf('>', text.indexOf('<testsuite')) + 1)
            .matchAll(/(\w+)="([^"]*)"/g)].map(match => [match[1], match[2]]));
        tests.push({ name: attrs.name, tests: Number(attrs.tests), failures: Number(attrs.failures), skipped: Number(attrs.skipped) });
    }
}
const manifest = fs.readFileSync('TMessagesProj/src/main/AndroidManifest.xml', 'utf8');
const permissions = [...manifest.matchAll(/<uses-permission\s+android:name="([^"]+)"(?:\s+android:maxSdkVersion="(\d+)")?/g)]
    .map(match => ({ name: match[1], maxSdk: match[2] || null }));
console.log(JSON.stringify({ sourceFiles, hgramFiles, runtimeResourceLookups, duplicates, hgramCandidates, permissions, tests }, null, 2));
if (duplicates.length) process.exitCode = 1;
