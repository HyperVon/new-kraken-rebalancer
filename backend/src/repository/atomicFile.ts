import fs from 'fs';
import * as path from 'path';
import { Decimal } from 'decimal.js';

export function decimalReplacer(key: string, value: unknown): unknown {
  if (value instanceof Decimal) {
    return value.toNumber();
  }
  return value;
}

export class AtomicJsonFile {
  static writeSync<T>(targetPath: string, value: T): void {
    const absoluteTargetPath = path.resolve(targetPath);
    const parentDir = path.dirname(absoluteTargetPath);

    if (!fs.existsSync(parentDir)) {
      fs.mkdirSync(parentDir, { recursive: true });
    }

    const tempPath = path.join(parentDir, `${path.basename(targetPath)}.${Date.now()}.tmp`);
    try {
      const data = JSON.stringify(value, decimalReplacer, 2);
      fs.writeFileSync(tempPath, data, 'utf8');
      fs.renameSync(tempPath, absoluteTargetPath);
    } catch (err) {
      if (fs.existsSync(tempPath)) {
        try {
          fs.unlinkSync(tempPath);
        } catch (_) {}
      }
      throw err;
    }
  }
}
