/*
 * Pre-compresses the static web build output into `.br` (Brotli) and `.gz` (gzip) siblings so the Central
 * Dogma server can serve them directly via `FileService.serveCompressedFiles(true)` instead of shipping the
 * multi-megabyte assets (e.g. the ~3.2MB Monaco chunk, the ~5.7MB TypeScript worker) uncompressed.
 *
 * Runs after `next build` against the final export output. The uncompressed originals are kept for clients
 * that do not advertise `Accept-Encoding: br`/`gzip`; `FileService.autoDecompress(true)` covers the rest.
 * Compression happens at build time, so we use the maximum Brotli quality regardless of CPU cost.
 */
import { promises as fs } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import zlib from 'node:zlib';

// Mirror next.config.js's `distDir` (`process.env.NEXT_DIST_DIR || 'build/web/'`). The trailing slash there
// is irrelevant here because paths are always joined with `path.join`.
const OUTPUT_DIR = process.env.NEXT_DIST_DIR || 'build/web';

// Only text-based assets are worth compressing; binary media (png, ico, woff2, …) barely shrink.
const COMPRESSIBLE_EXTENSIONS = new Set([
  '.js',
  '.mjs',
  '.css',
  '.html',
  '.json',
  '.svg',
  '.txt',
  '.map',
  '.xml',
  '.wasm',
  '.webmanifest',
]);

// Next.js keeps its webpack build cache under `cache/`; it is never served, so skip it entirely.
const SKIP_DIRECTORIES = new Set(['cache']);

// Files below this size don't benefit once compression framing overhead is accounted for.
const MIN_BYTES = 1024;

// Async (libuv threadpool) variants so multiple files — and the gzip/brotli of a single file — can be
// compressed concurrently instead of blocking the main thread one at a time.
const gzipAsync = promisify(zlib.gzip);
const brotliCompressAsync = promisify(zlib.brotliCompress);

async function* walk(dir) {
  const entries = await fs.readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (SKIP_DIRECTORIES.has(entry.name)) {
        continue;
      }
      yield* walk(fullPath);
    } else if (entry.isFile()) {
      yield fullPath;
    } else if (entry.isSymbolicLink()) {
      // Follow a symlink only when it resolves to a regular file, so linked assets (possible in monorepo/CI
      // layouts) aren't silently skipped. Never recurse into a symlinked directory: a link pointing back to
      // an ancestor forms a cycle that would re-traverse the tree until the filesystem's symlink limit is
      // hit. Next.js export output doesn't nest linked directories, so skipping them is safe.
      try {
        const stats = await fs.stat(fullPath);
        if (stats.isFile()) {
          yield fullPath;
        }
      } catch {
        // Dangling symlink: nothing to compress.
      }
    }
  }
}

// Compresses one file into `.gz`/`.br` siblings, returning its byte totals for reporting, or null when the
// file is too small to be worth compressing. gzip and brotli run concurrently.
async function compressFile(file) {
  const original = await fs.readFile(file);
  if (original.length < MIN_BYTES) {
    return null;
  }

  const [gzip, brotli] = await Promise.all([
    gzipAsync(original, { level: zlib.constants.Z_BEST_COMPRESSION }),
    brotliCompressAsync(original, {
      params: {
        [zlib.constants.BROTLI_PARAM_QUALITY]: zlib.constants.BROTLI_MAX_QUALITY,
        [zlib.constants.BROTLI_PARAM_SIZE_HINT]: original.length,
      },
    }),
  ]);

  // Only emit a variant that actually beats the original, so we never serve a larger "compressed" file.
  const writes = [];
  if (gzip.length < original.length) {
    writes.push(fs.writeFile(`${file}.gz`, gzip));
  }
  if (brotli.length < original.length) {
    writes.push(fs.writeFile(`${file}.br`, brotli));
  }
  await Promise.all(writes);

  return {
    originalLength: original.length,
    // The size actually served to a brotli-capable client: the `.br` when it won, else the original.
    compressedLength: Math.min(brotli.length, original.length),
  };
}

async function main() {
  const files = [];
  for await (const file of walk(OUTPUT_DIR)) {
    if (COMPRESSIBLE_EXTENSIONS.has(path.extname(file).toLowerCase())) {
      files.push(file);
    }
  }

  let fileCount = 0;
  let originalTotal = 0;
  let compressedTotal = 0;

  // Compress files across a bounded worker pool so idle cores are used without spawning an unbounded number
  // of concurrent compressions on large builds. JS is single-threaded, so grabbing `cursor` between awaits
  // hands each worker a distinct file with no locking.
  const concurrency = Math.max(1, os.cpus().length - 1);
  let cursor = 0;
  const worker = async () => {
    while (cursor < files.length) {
      const file = files[cursor];
      cursor += 1;
      const result = await compressFile(file);
      if (result) {
        fileCount += 1;
        originalTotal += result.originalLength;
        compressedTotal += result.compressedLength;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, files.length) }, worker));

  const savedPct =
    originalTotal === 0 ? '0.0' : ((1 - compressedTotal / originalTotal) * 100).toFixed(1);
  const toMb = (bytes) => (bytes / 1e6).toFixed(2);
  console.log(
    `[compress-static] Precompressed ${fileCount} files in ${OUTPUT_DIR}: ` +
      `${toMb(originalTotal)}MB -> ${toMb(compressedTotal)}MB brotli (${savedPct}% smaller).`,
  );
}

main().catch((err) => {
  console.error('[compress-static] Failed to precompress static assets:', err);
  process.exitCode = 1;
});
