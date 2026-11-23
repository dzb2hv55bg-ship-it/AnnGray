import test from 'node:test';
import assert from 'node:assert/strict';
import { validateDisplayName } from '../src/profile.js';
test('accepts trimmed unicode names', () => assert.equal(validateDisplayName({displayName:'  Маша  '}).value, 'Маша'));
test('rejects blank names', () => assert.equal(validateDisplayName({displayName:'  '}).error, 'INVALID_DISPLAY_NAME'));
test('rejects long names', () => assert.equal(validateDisplayName({displayName:'a'.repeat(61)}).error, 'INVALID_DISPLAY_NAME'));
test('rejects control characters', () => assert.equal(validateDisplayName({displayName:'Hi\nthere'}).error, 'INVALID_DISPLAY_NAME'));
test('rejects malformed payloads', () => assert.equal(validateDisplayName([]).error, 'INVALID_DISPLAY_NAME'));
