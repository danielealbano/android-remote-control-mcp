import { loadConfig } from './config.js';
import { createRelay } from './relay.js';

const SHUTDOWN_GRACE_MS = 5_000;

const config = loadConfig();
const relay = createRelay(config, console);

relay.server.listen(config.port, () => {
  console.info(`relay: listening on :${config.port}`);
});

function shutdown(signal) {
  console.info(`relay: ${signal} received, shutting down`);
  relay.close().then(() => process.exit(0));
  setTimeout(() => process.exit(1), SHUTDOWN_GRACE_MS).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
