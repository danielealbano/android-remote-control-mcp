const DEFAULTS = Object.freeze({
  PORT: 8080,
  REQUEST_TIMEOUT_MS: 120_000,
  MAX_BODY_BYTES: 16 * 1024 * 1024,
  MAX_RESPONSE_BYTES: 8 * 1024 * 1024,
  MAX_PENDING: 32,
  HEARTBEAT_INTERVAL_MS: 25_000,
  HEARTBEAT_TIMEOUT_MS: 60_000,
  TRUST_PROXY: true,
});

const MIN_TOKEN_LENGTH = 16;
const MAX_PORT = 65535;
const TRUE_WORDS = new Set(['true', '1', 'yes']);
const FALSE_WORDS = new Set(['false', '0', 'no']);

function readPositiveInt(env, name, fallback) {
  const raw = env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number.parseInt(raw, 10);
  if (!Number.isSafeInteger(value) || value <= 0 || String(value) !== raw.trim()) {
    throw new Error(`${name} must be a positive integer, got "${raw}"`);
  }
  return value;
}

function readPort(env) {
  const port = readPositiveInt(env, 'PORT', DEFAULTS.PORT);
  if (port > MAX_PORT) throw new Error(`PORT must be between 1 and ${MAX_PORT}, got "${port}"`);
  return port;
}

function readBoolean(env, name, fallback) {
  const raw = env[name];
  if (raw === undefined || raw === '') return fallback;
  const word = raw.trim().toLowerCase();
  if (TRUE_WORDS.has(word)) return true;
  if (FALSE_WORDS.has(word)) return false;
  throw new Error(`${name} must be true or false, got "${raw}"`);
}

/**
 * Reads the relay configuration from environment variables.
 * RELAY_DEVICE_TOKEN is mandatory (>= 16 characters); everything else has a default.
 */
export function loadConfig(env = process.env) {
  const deviceToken = env.RELAY_DEVICE_TOKEN ?? '';
  if (deviceToken.length < MIN_TOKEN_LENGTH) {
    throw new Error(`RELAY_DEVICE_TOKEN must be set and at least ${MIN_TOKEN_LENGTH} characters long`);
  }
  return Object.freeze({
    port: readPort(env),
    deviceToken,
    requestTimeoutMs: readPositiveInt(env, 'REQUEST_TIMEOUT_MS', DEFAULTS.REQUEST_TIMEOUT_MS),
    maxBodyBytes: readPositiveInt(env, 'MAX_BODY_BYTES', DEFAULTS.MAX_BODY_BYTES),
    maxResponseBytes: readPositiveInt(env, 'MAX_RESPONSE_BYTES', DEFAULTS.MAX_RESPONSE_BYTES),
    maxPending: readPositiveInt(env, 'MAX_PENDING', DEFAULTS.MAX_PENDING),
    heartbeatIntervalMs: readPositiveInt(env, 'HEARTBEAT_INTERVAL_MS', DEFAULTS.HEARTBEAT_INTERVAL_MS),
    heartbeatTimeoutMs: readPositiveInt(env, 'HEARTBEAT_TIMEOUT_MS', DEFAULTS.HEARTBEAT_TIMEOUT_MS),
    trustProxy: readBoolean(env, 'TRUST_PROXY', DEFAULTS.TRUST_PROXY),
  });
}
