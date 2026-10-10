export const FRAME_TYPES = Object.freeze({
  REQUEST: 'request',
  RESPONSE: 'response',
  PING: 'ping',
  PONG: 'pong',
});

const MIN_STATUS = 200;
const MAX_STATUS = 599;

/** Builds the text frame the device receives for one HTTP request (body is a Buffer). */
export function encodeRequestFrame({ id, method, path, headers, body }) {
  return JSON.stringify({
    type: FRAME_TYPES.REQUEST,
    id,
    method,
    path,
    headers,
    body: body.toString('base64'),
  });
}

export function encodePing() {
  return JSON.stringify({ type: FRAME_TYPES.PING });
}

/**
 * Parses a frame sent by the device. Returns `null` when the text is not a well-formed frame,
 * `{ type }` for pong/unknown types, and a normalized object for `response` frames
 * (`headers` is `{ name: [values] }`, `body` a Buffer, `status` 200–599).
 */
export function decodeDeviceFrame(text) {
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  if (parsed === null || typeof parsed !== 'object' || typeof parsed.type !== 'string') return null;
  if (parsed.type !== FRAME_TYPES.RESPONSE) return { type: parsed.type };
  return normalizeResponse(parsed);
}

function normalizeResponse(parsed) {
  if (typeof parsed.id !== 'string' || !Number.isInteger(parsed.status)) return null;
  if (parsed.status < MIN_STATUS || parsed.status > MAX_STATUS) return null;
  const rawHeaders = parsed.headers ?? {};
  if (typeof rawHeaders !== 'object' || Array.isArray(rawHeaders)) return null;
  const headers = {};
  for (const [name, values] of Object.entries(rawHeaders)) {
    if (!Array.isArray(values) || !values.every((value) => typeof value === 'string')) return null;
    headers[name] = values;
  }
  const body = typeof parsed.body === 'string' ? Buffer.from(parsed.body, 'base64') : Buffer.alloc(0);
  return { type: FRAME_TYPES.RESPONSE, id: parsed.id, status: parsed.status, headers, body };
}
