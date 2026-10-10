import { createServer } from 'node:http';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { WebSocketServer } from 'ws';
import { FRAME_TYPES, decodeDeviceFrame, encodePing, encodeRequestFrame } from './frames.js';

export const DEVICE_PATH = '/device';
export const HEALTH_PATH = '/relay/health';
export const CLOSE_REPLACED = 4000;

const HOP_BY_HOP_HEADERS = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'expect',
  'host',
  'content-length',
]);
const NO_BODY_STATUSES = new Set([204, 304]);
const FRAME_OVERHEAD_BYTES = 64 * 1024;
const CLOSE_GOING_AWAY = 1001;

export class RelayError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'RelayError';
    this.status = status;
  }
}

/**
 * Builds the relay: an HTTP server that proxies every request (except HEALTH_PATH) to the single connected
 * device over its WebSocket, plus the bearer-gated DEVICE_PATH upgrade endpoint.
 * Returns `{ server, close, isDeviceConnected }`; the caller decides where `server` listens.
 */
export function createRelay(config, logger = console) {
  const pending = new Map();
  let device = null;

  const largestBody = Math.max(config.maxBodyBytes, config.maxResponseBytes);
  const wss = new WebSocketServer({
    noServer: true,
    maxPayload: Math.ceil((largestBody * 4) / 3) + FRAME_OVERHEAD_BYTES,
  });
  const server = createServer((req, res) => {
    handleHttp(req, res).catch((err) => {
      logger.error(`relay: unhandled error: ${err?.stack ?? err}`);
      if (!res.headersSent) sendJson(res, 500, { error: 'internal relay error' });
    });
  });
  server.on('upgrade', handleUpgrade);

  function isAuthorized(req) {
    const given = Buffer.from(req.headers.authorization ?? '', 'utf8');
    const expected = Buffer.from(`Bearer ${config.deviceToken}`, 'utf8');
    return given.length === expected.length && timingSafeEqual(given, expected);
  }

  function handleUpgrade(req, socket, head) {
    const { pathname } = new URL(req.url, 'http://relay.invalid');
    if (pathname !== DEVICE_PATH) {
      rejectUpgrade(socket, 404, 'Not Found');
      return;
    }
    if (!isAuthorized(req)) {
      logger.warn(`relay: rejected device handshake from ${req.socket.remoteAddress}`);
      rejectUpgrade(socket, 401, 'Unauthorized');
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => attachDevice(ws, req));
  }

  function rejectUpgrade(socket, status, text) {
    socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
    socket.destroy();
  }

  function attachDevice(ws, req) {
    if (device !== null) {
      logger.info('relay: new device connection replaces the previous one');
      const previous = device;
      detachDevice(previous, 'device connection replaced');
      previous.socket.close(CLOSE_REPLACED, 'replaced by a new device connection');
    }
    const entry = { socket: ws, lastSeen: Date.now(), heartbeat: null };
    entry.heartbeat = setInterval(() => heartbeat(entry), config.heartbeatIntervalMs);
    device = entry;
    logger.info(`relay: device connected from ${req.socket.remoteAddress}`);
    ws.on('message', (data) => {
      entry.lastSeen = Date.now();
      handleDeviceMessage(data);
    });
    ws.on('pong', () => {
      entry.lastSeen = Date.now();
    });
    ws.on('close', (code) => {
      logger.info(`relay: device disconnected (code ${code})`);
      detachDevice(entry, 'device disconnected');
    });
    ws.on('error', (err) => {
      logger.warn(`relay: device socket error: ${err.message}`);
    });
  }

  function detachDevice(entry, reason) {
    clearInterval(entry.heartbeat);
    if (device === entry) {
      device = null;
      failAllPending(reason);
    }
  }

  function heartbeat(entry) {
    if (Date.now() - entry.lastSeen > config.heartbeatTimeoutMs) {
      logger.warn('relay: device unresponsive, terminating its socket');
      entry.socket.terminate();
      return;
    }
    if (entry.socket.readyState === entry.socket.OPEN) {
      entry.socket.send(encodePing());
    }
  }

  function handleDeviceMessage(data) {
    const frame = decodeDeviceFrame(data.toString('utf8'));
    if (frame === null) {
      logger.warn('relay: ignoring malformed device frame');
      return;
    }
    if (frame.type === FRAME_TYPES.PONG) return;
    if (frame.type !== FRAME_TYPES.RESPONSE) {
      logger.warn(`relay: ignoring device frame of type "${frame.type}"`);
      return;
    }
    const waiter = pending.get(frame.id);
    if (waiter === undefined) {
      logger.warn(`relay: response for unknown or expired request ${frame.id}`);
      return;
    }
    pending.delete(frame.id);
    clearTimeout(waiter.timer);
    waiter.resolve(frame);
  }

  function failAllPending(reason) {
    for (const waiter of pending.values()) {
      clearTimeout(waiter.timer);
      waiter.reject(new RelayError(502, reason));
    }
    pending.clear();
  }

  async function handleHttp(req, res) {
    const url = new URL(req.url, 'http://relay.invalid');
    if (url.pathname === HEALTH_PATH) {
      sendJson(res, 200, { status: 'ok', deviceConnected: device !== null });
      return;
    }
    try {
      if (device === null) throw new RelayError(503, 'device not connected');
      if (pending.size >= config.maxPending) throw new RelayError(503, 'too many pending requests');
      const body = await readBody(req, config.maxBodyBytes);
      const response = await forward(req, url, body);
      if (response.body.length > config.maxResponseBytes) throw new RelayError(502, 'device response too large');
      writeResponse(req, res, response);
    } catch (err) {
      if (err instanceof RelayError) {
        sendJson(res, err.status, { error: err.message }, err.status === 413);
        return;
      }
      throw err;
    }
  }

  function forward(req, url, body) {
    if (device === null) {
      return Promise.reject(new RelayError(503, 'device not connected'));
    }
    const id = randomUUID();
    const frame = encodeRequestFrame({
      id,
      method: req.method,
      path: `${url.pathname}${url.search}`,
      headers: forwardedHeaders(req),
      body,
    });
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new RelayError(504, 'device did not answer in time'));
      }, config.requestTimeoutMs);
      pending.set(id, { resolve, reject, timer });
      device.socket.send(frame, (err) => {
        if (err && pending.delete(id)) {
          clearTimeout(timer);
          reject(new RelayError(502, `failed to send to device: ${err.message}`));
        }
      });
    });
  }

  function forwardedHeaders(req) {
    const headers = {};
    for (const [name, values] of Object.entries(req.headersDistinct)) {
      if (!HOP_BY_HOP_HEADERS.has(name)) headers[name] = values;
    }
    const observedHost = [req.headers.host ?? ''];
    const observedProto = [req.socket.encrypted ? 'https' : 'http'];
    if (config.trustProxy) {
      headers['x-forwarded-host'] ??= observedHost;
      headers['x-forwarded-proto'] ??= observedProto;
    } else {
      headers['x-forwarded-host'] = observedHost;
      headers['x-forwarded-proto'] = observedProto;
      delete headers['x-forwarded-for'];
    }
    headers['x-forwarded-for'] = [...(headers['x-forwarded-for'] ?? []), req.socket.remoteAddress ?? ''];
    return headers;
  }

  function writeResponse(req, res, frame) {
    for (const [name, values] of Object.entries(frame.headers)) {
      if (!HOP_BY_HOP_HEADERS.has(name.toLowerCase())) res.setHeader(name, values);
    }
    res.statusCode = frame.status;
    if (req.method === 'HEAD' || NO_BODY_STATUSES.has(frame.status)) {
      res.end();
      return;
    }
    res.setHeader('content-length', frame.body.length);
    res.end(frame.body);
  }

  function close() {
    return new Promise((resolve) => {
      if (device !== null) {
        const current = device;
        detachDevice(current, 'relay shutting down');
        current.socket.close(CLOSE_GOING_AWAY, 'relay shutting down');
      }
      wss.close();
      server.close(() => resolve());
      server.closeAllConnections();
    });
  }

  return {
    server,
    close,
    isDeviceConnected: () => device !== null,
  };
}

function readBody(req, maxBytes) {
  const declared = Number.parseInt(req.headers['content-length'] ?? '', 10);
  if (Number.isFinite(declared) && declared > maxBytes) {
    req.resume();
    return Promise.reject(new RelayError(413, `request body exceeds ${maxBytes} bytes`));
  }
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let rejected = false;
    req.on('data', (chunk) => {
      if (rejected) return;
      total += chunk.length;
      if (total > maxBytes) {
        rejected = true;
        reject(new RelayError(413, `request body exceeds ${maxBytes} bytes`));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (!rejected) resolve(Buffer.concat(chunks));
    });
    req.on('error', (err) => {
      if (!rejected) reject(new RelayError(400, `failed to read request body: ${err.message}`));
    });
  });
}

function sendJson(res, status, payload, closeConnection = false) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  const headers = { 'content-type': 'application/json', 'content-length': body.length };
  if (closeConnection) headers.connection = 'close';
  res.writeHead(status, headers);
  res.end(body);
}
