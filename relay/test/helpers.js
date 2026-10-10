import { once } from 'node:events';
import { WebSocket } from 'ws';
import { createRelay } from '../src/relay.js';

export const TEST_TOKEN = 'test-device-token-0123456789';

/** Heartbeat is slow by default so pings never interleave with test traffic; heartbeat tests override it. */
export const TEST_CONFIG = Object.freeze({
  port: 0,
  deviceToken: TEST_TOKEN,
  requestTimeoutMs: 300,
  maxBodyBytes: 1024,
  maxResponseBytes: 2048,
  maxPending: 2,
  heartbeatIntervalMs: 1_000,
  heartbeatTimeoutMs: 5_000,
  trustProxy: true,
});

const silentLogger = { info() {}, warn() {}, error() {} };

/** Starts a relay on an ephemeral loopback port. */
export async function startRelay(overrides = {}) {
  const relay = createRelay({ ...TEST_CONFIG, ...overrides }, silentLogger);
  relay.server.listen(0, '127.0.0.1');
  await once(relay.server, 'listening');
  const { port } = relay.server.address();
  return {
    relay,
    port,
    baseUrl: `http://127.0.0.1:${port}`,
    wsUrl: `ws://127.0.0.1:${port}/device`,
    stop: () => relay.close(),
  };
}

/**
 * Opens a device socket with the given bearer; resolves once the handshake completes.
 * By default it answers relay pings with pongs (like the app does); `autoPong: false` plays a dead device.
 */
export async function connectDevice(wsUrl, token = TEST_TOKEN, { autoPong = true } = {}) {
  const ws = new WebSocket(wsUrl, { headers: { authorization: `Bearer ${token}` } });
  await once(ws, 'open');
  if (autoPong) {
    ws.on('message', (data) => {
      if (JSON.parse(data.toString('utf8')).type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    });
  }
  return ws;
}

/** Resolves with the next frame of the given type (default `request`); `null` accepts any type. */
export async function nextFrame(ws, type = 'request') {
  for (;;) {
    const [data] = await once(ws, 'message');
    const frame = JSON.parse(data.toString('utf8'));
    if (type === null || frame.type === type) return frame;
  }
}

/** Sends a response frame for the given request id (body is a string or Buffer). */
export function respond(ws, id, { status = 200, headers = {}, body = '' } = {}) {
  ws.send(JSON.stringify({
    type: 'response',
    id,
    status,
    headers,
    body: Buffer.from(body).toString('base64'),
  }));
}

/** Resolves with the HTTP status the server used to refuse a WebSocket upgrade. */
export function rejectedUpgradeStatus(wsUrl, headers) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl, { headers });
    ws.on('unexpected-response', (_req, res) => {
      res.resume();
      resolve(res.statusCode);
    });
    ws.on('open', () => reject(new Error('upgrade unexpectedly accepted')));
    ws.on('error', reject);
  });
}
