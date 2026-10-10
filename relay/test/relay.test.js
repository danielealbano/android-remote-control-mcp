import assert from 'node:assert/strict';
import { once } from 'node:events';
import { connect } from 'node:net';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { setTimeout as sleep } from 'node:timers/promises';
import { CLOSE_REPLACED } from '../src/relay.js';
import { TEST_TOKEN, connectDevice, nextFrame, rejectedUpgradeStatus, respond, startRelay } from './helpers.js';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const NO_FRAME = 'no frame';

async function fetchText(url, init) {
  const res = await fetch(url, init);
  return { res, text: await res.text() };
}

/** Writes a raw HTTP/1.1 request (with `Connection: close`) and resolves with everything the server sent back. */
function rawRequest(port, text) {
  return new Promise((resolve, reject) => {
    const socket = connect(port, '127.0.0.1');
    let data = '';
    socket.on('connect', () => socket.write(text));
    socket.on('data', (chunk) => {
      data += chunk.toString('utf8');
    });
    socket.on('close', () => resolve(data));
    socket.on('error', reject);
  });
}

/** Resolves with the next frame of any type, or with NO_FRAME when none arrives within `ms`. */
function frameOrNothing(ws, ms) {
  return Promise.race([nextFrame(ws, null), sleep(ms, NO_FRAME)]);
}

describe('relay', () => {
  let ctx;
  const extras = [];
  const devices = [];

  beforeEach(async () => {
    ctx = await startRelay();
  });

  afterEach(async () => {
    for (const ws of devices) ws.terminate();
    devices.length = 0;
    for (const extra of extras) await extra.stop();
    extras.length = 0;
    if (ctx) await ctx.stop();
    ctx = null;
  });

  async function device(wsUrl = ctx.wsUrl, options = {}) {
    const ws = await connectDevice(wsUrl, TEST_TOKEN, options);
    devices.push(ws);
    return ws;
  }

  async function extraRelay(overrides) {
    const extra = await startRelay(overrides);
    extras.push(extra);
    return extra;
  }

  it('health reports device state', async () => {
    let { res, text } = await fetchText(`${ctx.baseUrl}/relay/health`);
    assert.equal(res.status, 200);
    assert.deepEqual(JSON.parse(text), { status: 'ok', deviceConnected: false });

    await device();

    ({ res, text } = await fetchText(`${ctx.baseUrl}/relay/health`));
    assert.equal(res.status, 200);
    assert.deepEqual(JSON.parse(text), { status: 'ok', deviceConnected: true });
  });

  it('upgrade without bearer is refused with 401', async () => {
    assert.equal(await rejectedUpgradeStatus(ctx.wsUrl, {}), 401);
  });

  it('upgrade with wrong bearer is refused with 401', async () => {
    assert.equal(await rejectedUpgradeStatus(ctx.wsUrl, { authorization: 'Bearer nope' }), 401);
  });

  it('upgrade on another path is refused with 404', async () => {
    const url = `${ctx.baseUrl.replace('http://', 'ws://')}/other`;
    assert.equal(await rejectedUpgradeStatus(url, { authorization: `Bearer ${TEST_TOKEN}` }), 404);
  });

  it('request without device returns 503', async () => {
    const { res, text } = await fetchText(`${ctx.baseUrl}/mcp`, { method: 'POST', body: '{}' });

    assert.equal(res.status, 503);
    assert.equal(JSON.parse(text).error, 'device not connected');
  });

  it('request is forwarded as a frame and the response frame is returned', async () => {
    const ws = await device();
    const sent = JSON.stringify({ hello: 'world' });
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp?x=1`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-test': '1' },
      body: sent,
    });

    const frame = await framePromise;
    assert.equal(frame.type, 'request');
    assert.match(frame.id, UUID_PATTERN);
    assert.equal(frame.method, 'POST');
    assert.equal(frame.path, '/mcp?x=1');
    assert.deepEqual(frame.headers['x-test'], ['1']);
    assert.deepEqual(frame.headers['content-type'], ['application/json']);
    assert.equal(frame.headers.host, undefined);
    assert.equal(frame.headers['content-length'], undefined);
    assert.equal(Buffer.from(frame.body, 'base64').toString('utf8'), sent);

    const body = '{"ok":true}';
    respond(ws, frame.id, {
      status: 201,
      headers: {
        'Content-Type': ['application/json'],
        'Mcp-Session-Id': ['abc'],
        'Transfer-Encoding': ['chunked'],
      },
      body,
    });

    const { res, text } = await fetchPromise;
    assert.equal(res.status, 201);
    assert.equal(res.headers.get('content-type'), 'application/json');
    assert.equal(res.headers.get('mcp-session-id'), 'abc');
    assert.equal(res.headers.get('transfer-encoding'), null);
    assert.equal(text, body);
  });

  it('absolute-form request target is forwarded as origin-form', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);
    const rawPromise = rawRequest(
      ctx.port,
      'GET http://relay.invalid/mcp?x=1 HTTP/1.1\r\nHost: relay.invalid\r\nConnection: close\r\n\r\n',
    );

    const frame = await framePromise;
    assert.equal(frame.path, '/mcp?x=1');
    respond(ws, frame.id, { body: 'ok' });

    const raw = await rawPromise;
    assert.match(raw, /^HTTP\/1\.1 200 /);
  });

  it('trusted proxy headers are preserved', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp`, {
      headers: {
        'x-forwarded-host': 'public.example',
        'x-forwarded-proto': 'https',
        'x-forwarded-for': '203.0.113.9',
      },
    });

    const frame = await framePromise;
    assert.deepEqual(frame.headers['x-forwarded-host'], ['public.example']);
    assert.deepEqual(frame.headers['x-forwarded-proto'], ['https']);
    assert.deepEqual(frame.headers['x-forwarded-for'], ['203.0.113.9', '127.0.0.1']);

    respond(ws, frame.id);
    await fetchPromise;
  });

  it('untrusted proxy headers are overwritten', async () => {
    const untrusted = await extraRelay({ trustProxy: false });
    const ws = await device(untrusted.wsUrl);
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${untrusted.baseUrl}/mcp`, {
      headers: {
        'x-forwarded-host': 'public.example',
        'x-forwarded-proto': 'https',
        'x-forwarded-for': '203.0.113.9',
      },
    });

    const frame = await framePromise;
    assert.deepEqual(frame.headers['x-forwarded-host'], [`127.0.0.1:${untrusted.port}`]);
    assert.deepEqual(frame.headers['x-forwarded-proto'], ['http']);
    assert.deepEqual(frame.headers['x-forwarded-for'], ['127.0.0.1']);

    respond(ws, frame.id);
    await fetchPromise;
  });

  it('missing proxy headers are filled in', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp`);

    const frame = await framePromise;
    assert.deepEqual(frame.headers['x-forwarded-host'], [`127.0.0.1:${ctx.port}`]);
    assert.deepEqual(frame.headers['x-forwarded-proto'], ['http']);
    assert.deepEqual(frame.headers['x-forwarded-for'], ['127.0.0.1']);

    respond(ws, frame.id);
    await fetchPromise;
  });

  it('head and 204 responses carry no body', async () => {
    const ws = await device();

    let framePromise = nextFrame(ws);
    let fetchPromise = fetchText(`${ctx.baseUrl}/x`, { method: 'HEAD' });
    let frame = await framePromise;
    assert.equal(frame.method, 'HEAD');
    respond(ws, frame.id, { body: 'ignored' });
    let { res, text } = await fetchPromise;
    assert.equal(res.status, 200);
    assert.equal(text, '');
    assert.equal(res.headers.get('content-length'), null);

    framePromise = nextFrame(ws);
    fetchPromise = fetchText(`${ctx.baseUrl}/x`, { method: 'POST', body: 'y' });
    frame = await framePromise;
    respond(ws, frame.id, { status: 204, body: 'ignored' });
    ({ res, text } = await fetchPromise);
    assert.equal(res.status, 204);
    assert.equal(text, '');
  });

  it('device not answering returns 504', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);

    const { res, text } = await fetchText(`${ctx.baseUrl}/mcp`, { method: 'POST', body: '{}' });

    await framePromise;
    assert.equal(res.status, 504);
    assert.equal(JSON.parse(text).error, 'device did not answer in time');
  });

  it('body over the limit returns 413 and closes the connection', async () => {
    const ws = await device();
    const chunk = 'a'.repeat(2048);

    const raw = await rawRequest(
      ctx.port,
      `POST /mcp HTTP/1.1\r\nHost: relay.invalid\r\nTransfer-Encoding: chunked\r\n\r\n${chunk.length.toString(16)}\r\n${chunk}\r\n0\r\n\r\n`,
    );

    assert.match(raw, /^HTTP\/1\.1 413 /);
    assert.match(raw, /\r\nconnection: close\r\n/i);
    assert.equal(await frameOrNothing(ws, 200), NO_FRAME);
  });

  it('declared content-length over the limit returns 413 before reading', async () => {
    const ws = await device();

    const raw = await rawRequest(
      ctx.port,
      'POST /mcp HTTP/1.1\r\nHost: relay.invalid\r\nContent-Length: 4096\r\nConnection: close\r\n\r\n',
    );

    assert.match(raw, /^HTTP\/1\.1 413 /);
    assert.equal(await frameOrNothing(ws, 200), NO_FRAME);
  });

  it('oversized device response returns 502', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp`);

    const frame = await framePromise;
    respond(ws, frame.id, { body: 'a'.repeat(2049) });

    const { res, text } = await fetchPromise;
    assert.equal(res.status, 502);
    assert.equal(JSON.parse(text).error, 'device response too large');
  });

  it('pending limit returns 503', async () => {
    const ws = await device();
    const firstFrame = nextFrame(ws);
    const first = fetchText(`${ctx.baseUrl}/one`);
    const frame1 = await firstFrame;
    const secondFrame = nextFrame(ws);
    const second = fetchText(`${ctx.baseUrl}/two`);
    const frame2 = await secondFrame;

    const { res, text } = await fetchText(`${ctx.baseUrl}/three`);
    assert.equal(res.status, 503);
    assert.equal(JSON.parse(text).error, 'too many pending requests');

    respond(ws, frame1.id, { status: 200 });
    respond(ws, frame2.id, { status: 201 });
    assert.equal((await first).res.status, 200);
    assert.equal((await second).res.status, 201);
  });

  it('malformed and unknown device frames are ignored', async () => {
    const ws = await device();
    ws.send('nope');
    ws.send('{"type":"hello"}');
    ws.send('{"type":"response","id":"unknown","status":200}');
    ws.send('{"type":"response","id":"x","status":150}');

    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp`);
    const frame = await framePromise;
    respond(ws, frame.id, { body: 'still alive' });

    const { res, text } = await fetchPromise;
    assert.equal(res.status, 200);
    assert.equal(text, 'still alive');
  });

  it('new device replaces the previous one', async () => {
    const first = await device();
    const firstClosed = once(first, 'close');
    const firstFrame = nextFrame(first);
    const pendingFetch = fetchText(`${ctx.baseUrl}/mcp`);
    await firstFrame;

    const second = await device();

    const [code] = await firstClosed;
    assert.equal(code, CLOSE_REPLACED);
    assert.equal((await pendingFetch).res.status, 502);

    const secondFrame = nextFrame(second);
    const nextFetch = fetchText(`${ctx.baseUrl}/mcp`);
    const frame = await secondFrame;
    respond(second, frame.id, { body: 'from second' });
    const { res, text } = await nextFetch;
    assert.equal(res.status, 200);
    assert.equal(text, 'from second');
  });

  it('device disconnect fails in-flight requests with 502', async () => {
    const ws = await device();
    const framePromise = nextFrame(ws);
    const fetchPromise = fetchText(`${ctx.baseUrl}/mcp`);
    await framePromise;

    ws.close();

    const { res, text } = await fetchPromise;
    assert.equal(res.status, 502);
    assert.equal(JSON.parse(text).error, 'device disconnected');
  });

  it('relay pings the device', async () => {
    const pinging = await extraRelay({ heartbeatIntervalMs: 40, heartbeatTimeoutMs: 5_000 });
    const ws = await device(pinging.wsUrl);

    const frame = await Promise.race([nextFrame(ws, 'ping'), sleep(500, NO_FRAME)]);

    assert.deepEqual(frame, { type: 'ping' });
  });

  it('unresponsive device is terminated', async () => {
    const strict = await extraRelay({ heartbeatIntervalMs: 40, heartbeatTimeoutMs: 120 });
    const ws = await device(strict.wsUrl, { autoPong: false });

    const outcome = await Promise.race([once(ws, 'close').then(() => 'closed'), sleep(1_000, 'still open')]);

    assert.equal(outcome, 'closed');
    const { text } = await fetchText(`${strict.baseUrl}/relay/health`);
    assert.equal(JSON.parse(text).deviceConnected, false);
  });

  it('shutdown closes device socket with 1001', async () => {
    const ws = await device();
    const closed = once(ws, 'close');

    await ctx.stop();
    ctx = null;

    const [code] = await closed;
    assert.equal(code, 1001);
  });
});
