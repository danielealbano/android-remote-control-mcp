import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { decodeDeviceFrame, encodeRequestFrame } from '../src/frames.js';

describe('encodeRequestFrame', () => {
  it('base64-encodes the body', () => {
    const body = Buffer.from([0, 1, 2, 253, 254, 255]);

    const frame = JSON.parse(
      encodeRequestFrame({ id: 'r1', method: 'POST', path: '/mcp?x=1', headers: { 'x-test': ['1'] }, body }),
    );

    assert.equal(frame.type, 'request');
    assert.equal(frame.id, 'r1');
    assert.equal(frame.method, 'POST');
    assert.equal(frame.path, '/mcp?x=1');
    assert.deepEqual(frame.headers, { 'x-test': ['1'] });
    assert.deepEqual(Buffer.from(frame.body, 'base64'), body);
  });
});

describe('decodeDeviceFrame', () => {
  it('returns null for invalid json', () => {
    assert.equal(decodeDeviceFrame('not json'), null);
  });

  it('returns null for non-object or missing type', () => {
    assert.equal(decodeDeviceFrame('[1]'), null);
    assert.equal(decodeDeviceFrame('{"id":"x"}'), null);
  });

  it('returns type for pong and unknown', () => {
    assert.deepEqual(decodeDeviceFrame('{"type":"pong"}'), { type: 'pong' });
    assert.deepEqual(decodeDeviceFrame('{"type":"hello"}'), { type: 'hello' });
  });

  it('normalizes a response', () => {
    const text = JSON.stringify({
      type: 'response',
      id: 'r1',
      status: 201,
      headers: { 'Content-Type': ['application/json'], 'Mcp-Session-Id': ['abc'] },
      body: Buffer.from('{"ok":true}').toString('base64'),
    });

    const frame = decodeDeviceFrame(text);

    assert.equal(frame.type, 'response');
    assert.equal(frame.id, 'r1');
    assert.equal(frame.status, 201);
    assert.deepEqual(frame.headers, { 'Content-Type': ['application/json'], 'Mcp-Session-Id': ['abc'] });
    assert.equal(frame.body.toString('utf8'), '{"ok":true}');
  });

  it('rejects malformed responses', () => {
    const base = { type: 'response', id: 'r1', status: 200 };
    const broken = [
      { ...base, id: undefined },
      { ...base, status: '200' },
      { ...base, status: 101 },
      { ...base, status: 600 },
      { ...base, headers: { a: 'x' } },
      { ...base, headers: { a: [1] } },
      { ...base, headers: [] },
    ];

    for (const frame of broken) {
      assert.equal(decodeDeviceFrame(JSON.stringify(frame)), null, JSON.stringify(frame));
    }
  });

  it('defaults missing body and headers', () => {
    const frame = decodeDeviceFrame('{"type":"response","id":"r1","status":200}');

    assert.equal(frame.body.length, 0);
    assert.deepEqual(frame.headers, {});
  });
});
