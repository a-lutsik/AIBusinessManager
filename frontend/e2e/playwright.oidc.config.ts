import { e2eConfig } from './support/config';

// OIDC run (npm run e2e:oidc): bundle and backend with real Keycloak login, specs in e2e/oidc.
export default e2eConfig({ testDir: './oidc' });
