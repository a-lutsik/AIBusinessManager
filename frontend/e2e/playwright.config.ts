import { e2eConfig } from './support/config';

// Main run (npm run e2e): dev-headers bundle, tests next to this file; OIDC specs have their own config.
export default e2eConfig({ testDir: '.', testIgnore: ['oidc/**', 'support/**'] });
