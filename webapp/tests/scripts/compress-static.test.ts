import { execFile } from 'node:child_process';
import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';

// The build script is an ESM `.mjs` that runs on import; jest transforms modules to CJS (breaking
// `import.meta`), so exercise it as a real subprocess against a temporary output directory instead.
const execFileAsync = promisify(execFile);
const SCRIPT = path.resolve(__dirname, '../../scripts/compress-static.mjs');

// Repetitive JS text that is comfortably above MIN_BYTES (1024) and compresses to a fraction of its size.
const COMPRESSIBLE = 'console.log("compress me");\n'.repeat(200);

const run = (distDir: string) =>
  execFileAsync('node', [SCRIPT], { env: { ...process.env, NEXT_DIST_DIR: distDir } });

const exists = async (p: string): Promise<boolean> => {
  try {
    await fs.access(p);
    return true;
  } catch {
    return false;
  }
};

describe('compress-static', () => {
  let dir: string;

  beforeEach(async () => {
    dir = await fs.mkdtemp(path.join(os.tmpdir(), 'compress-static-'));
  });

  afterEach(async () => {
    await fs.rm(dir, { recursive: true, force: true });
  });

  it('writes .br and .gz siblings for compressible files above the size threshold', async () => {
    const file = path.join(dir, 'app.js');
    await fs.writeFile(file, COMPRESSIBLE);

    await run(dir);

    expect(await exists(`${file}.br`)).toBe(true);
    expect(await exists(`${file}.gz`)).toBe(true);
    // The emitted brotli variant must actually be smaller than the original.
    const [original, brotli] = await Promise.all([fs.stat(file), fs.stat(`${file}.br`)]);
    expect(brotli.size).toBeLessThan(original.size);
  });

  it('skips files below the minimum size', async () => {
    const file = path.join(dir, 'tiny.js');
    await fs.writeFile(file, 'x'.repeat(100));

    await run(dir);

    expect(await exists(`${file}.br`)).toBe(false);
    expect(await exists(`${file}.gz`)).toBe(false);
  });

  it('skips non-compressible extensions', async () => {
    const file = path.join(dir, 'image.png');
    await fs.writeFile(file, COMPRESSIBLE); // large enough, but not a text asset

    await run(dir);

    expect(await exists(`${file}.br`)).toBe(false);
    expect(await exists(`${file}.gz`)).toBe(false);
  });

  it('skips the webpack cache directory', async () => {
    const cacheDir = path.join(dir, 'cache');
    await fs.mkdir(cacheDir);
    const file = path.join(cacheDir, 'chunk.js');
    await fs.writeFile(file, COMPRESSIBLE);

    await run(dir);

    expect(await exists(`${file}.br`)).toBe(false);
    expect(await exists(`${file}.gz`)).toBe(false);
  });

  it('follows symlinked files instead of skipping them', async () => {
    const target = path.join(dir, 'real.js');
    await fs.writeFile(target, COMPRESSIBLE);
    const link = path.join(dir, 'linked.js');
    await fs.symlink(target, link);

    await run(dir);

    // Both the real file and the symlink are compressed into their own siblings.
    expect(await exists(`${target}.br`)).toBe(true);
    expect(await exists(`${link}.br`)).toBe(true);
  });

  it('does not recurse into a symlinked directory cycle', async () => {
    const file = path.join(dir, 'app.js');
    await fs.writeFile(file, COMPRESSIBLE);
    // A directory symlink pointing back to an ancestor forms a cycle. Recursing into it re-traverses the tree
    // over and over (until the filesystem hits its symlink limit), re-compressing every file many times.
    await fs.symlink(dir, path.join(dir, 'self'));

    const { stdout } = await run(dir);

    // The real file is compressed exactly once and the symlinked directory is never descended into.
    expect(await exists(`${file}.br`)).toBe(true);
    expect(stdout).toContain('Precompressed 1 files');
  });
});
