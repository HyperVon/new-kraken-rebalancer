import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import fs from 'fs';
import * as path from 'path';
import { AtomicJsonFile } from '../../src/repository/atomicFile';

describe('AtomicJsonFileTest', () => {
  const testDir = path.resolve('build/test-atomic');

  beforeEach(() => {
    if (!fs.existsSync(testDir)) {
      fs.mkdirSync(testDir, { recursive: true });
    }
  });

  afterEach(() => {
    if (fs.existsSync(testDir)) {
      try {
        fs.rmSync(testDir, { recursive: true, force: true });
      } catch (_) {}
    }
    const localNoParent = path.resolve('success-no-parent.json');
    if (fs.existsSync(localNoParent)) {
      try {
        fs.unlinkSync(localNoParent);
      } catch (_) {}
    }
  });

  it('should write json file successfully', () => {
    const target = path.join(testDir, 'success.json');
    if (fs.existsSync(target)) fs.unlinkSync(target);

    AtomicJsonFile.writeSync(target, { hello: 'world' });

    expect(fs.existsSync(target)).toBe(true);
    const content = JSON.parse(fs.readFileSync(target, 'utf8'));
    expect(content.hello).toBe('world');
  });

  it('should write json file successfully with no parent directory', () => {
    const target = path.resolve('success-no-parent.json');
    if (fs.existsSync(target)) fs.unlinkSync(target);

    AtomicJsonFile.writeSync(target, { hello: 'world' });

    expect(fs.existsSync(target)).toBe(true);
    const content = JSON.parse(fs.readFileSync(target, 'utf8'));
    expect(content.hello).toBe('world');
  });

  it('should create parent directory if it does not exist', () => {
    const parent = path.join(testDir, 'nested-dir');
    if (fs.existsSync(parent)) {
      fs.rmSync(parent, { recursive: true, force: true });
    }

    const target = path.join(parent, 'nested.json');
    AtomicJsonFile.writeSync(target, { a: 1 });

    expect(fs.existsSync(target)).toBe(true);
    expect(fs.existsSync(parent)).toBe(true);
  });

  it('should throw Error if parent directory cannot be created', () => {
    const notADirFile = path.join(testDir, 'not-a-dir-file');
    if (fs.existsSync(notADirFile)) fs.unlinkSync(notADirFile);
    fs.writeFileSync(notADirFile, 'regular file', 'utf8');

    // Trying to create a file inside a path where the parent is a regular file must throw
    const target = path.join(notADirFile, 'nested-subdir', 'nested.json');
    expect(() => AtomicJsonFile.writeSync(target, { a: 1 })).toThrow();
  });

  it('should delete temp file if write fails', () => {
    const target = path.join(testDir, 'fail-cleanup.json');
    if (fs.existsSync(target)) fs.unlinkSync(target);

    const spy = vi.spyOn(fs, 'renameSync').mockImplementation(() => {
      throw new Error('Rename failed');
    });

    expect(() => AtomicJsonFile.writeSync(target, { should: 'fail' })).toThrow();

    const files = fs.readdirSync(testDir);
    const hasTemp = files.some(f => f.startsWith('fail-cleanup.json') && f.endsWith('.tmp'));
    expect(hasTemp).toBe(false);

    spy.mockRestore();
  });
});
