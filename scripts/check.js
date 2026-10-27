import { readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
for (const dir of ['server', 'public', 'scripts', 'test']) {
  for (const name of readdirSync(dir).filter(name => name.endsWith('.js'))) {
    const result = spawnSync(process.execPath, ['--check', `${dir}/${name}`], { stdio: 'inherit' });
    if (result.status) process.exit(result.status);
  }
}
console.log('JavaScript syntax checked.');
