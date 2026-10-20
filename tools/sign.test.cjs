const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const path = require('node:path');

const config = JSON.parse(fs.readFileSync(path.join(__dirname, '../bundle_src/platforms/douyin.json')));
const source = fs.readFileSync(path.join(__dirname, '../bundle_src/sign/douyin.js'), 'utf8');
function engine() {
  const math = Object.create(Math); math.random = () => 0.123456;
  const sandbox = { __platform: config, Math: math, Date: class extends Date { static now() { return 1700000000123; } } };
  vm.runInNewContext(source, sandbox);
  return sandbox.buildUrl;
}
const context = {ua: config.ua, uifid: 'offline_test_id', now: 1700000000};
const parameters = [['aweme_id','7697489379418420475'],['cursor','0'],['count','50'],['item_type','0']];

test('Bilibili WBI reference key signs the exact canonical query', () => {
  const config = JSON.parse(fs.readFileSync(path.join(__dirname, '../bundle_src/platforms/bilibili.json')));
  const sandbox = { __platform: config };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../bundle_src/sign/bilibili.js'), 'utf8'), sandbox);
  const result = sandbox.buildUrl(config.api.play.path, [['foo', "a!'()* b"], ['bvid', 'BV1nx411u79K']], {
    now: 1700000000, img: '7cd084941338484aae1ad9425b84077c', sub: '4932caff0ff746eab6f01bf08b70ac45'
  });
  const query = 'bvid=BV1nx411u79K&foo=a%20b&wts=1700000000';
  const expected = crypto.createHash('md5').update(query + 'ea1db124af3c7062474693fa704f4ff8').digest('hex');
  assert.equal(result, config.origin + config.api.play.path + '?' + query + '&w_rid=' + expected);
  assert.throws(() => sandbox.buildUrl('/unknown', [], {}));
  assert.throws(() => sandbox.buildUrl(config.api.play.path, [], {}));
});

test('web request retains its a_bogus reference vector and has no protected signature', () => {
  const url = new URL(engine()(config.api.favorites.path, parameters, context));
  assert.equal(url.searchParams.getAll('a_bogus').length, 1);
  assert.equal(url.searchParams.has('timestamp'), false);
  assert.equal(url.searchParams.has('x-secsdk-web-signature'), false);
  const reference = JSON.parse(fs.readFileSync(path.join(__dirname, 'sign/reference-vector.json')));
  assert.equal(url.searchParams.get('a_bogus'), reference.abogus);
});
test('protected signature covers exact sent bytes including timestamp', () => {
  const result = engine()(config.api.detail.path, [['aweme_id','1'],['extra','中文 +/~*']], context);
  const raw = result.split('?')[1];
  const reference = JSON.parse(fs.readFileSync(path.join(__dirname, 'sign/reference-vector.json')));
  assert.equal(raw, reference.protectedQuery);
  const at = raw.lastIndexOf('&x-secsdk-web-signature=');
  const covered = raw.slice(0, at);
  const expected = crypto.createHash('md5').update(`${context.uifid}_${context.now}_A96D855A08C0A9707F8BEF0D9A527E4E_${covered}`).digest('hex');
  assert.equal(raw.slice(at).split('=')[1], expected);
  assert.ok(covered.endsWith(`&timestamp=${context.now}`));
  assert.equal(new URL(result).searchParams.getAll('a_bogus').length, 1);
  assert.ok(covered.indexOf('aweme_id=') < covered.indexOf('uifid='));
});
test('protected request without login visitor id is rejected', () => {
  assert.throws(() => engine()(config.api.detail.path, [], {...context,uifid:''}), /NEED_LOGIN/);
});
test('pre-signed params and unknown endpoints are rejected', () => {
  assert.throws(() => engine()(config.api.comment.path, [['a_bogus','injected']], context), /PRE_SIGNED_PARAM/);
  assert.throws(() => engine()('/unconfigured/', [], context), /UNKNOWN_ENDPOINT/);
});
test('business parameter insertion order is retained', () => {
  const result = engine()(config.api.comment.path, [['z','a b'],['a','+~']], context);
  assert.ok(result.includes('&z=a+b&a=%2B~&a_bogus='));
});

test('comment profile overrides are signed once without changing other operations', () => {
  for (const operation of ['comment', 'reply']) {
    const count = String(config[operation === 'comment' ? 'count_top' : 'count_reply']);
    const variables = {postId: '123', parentId: '456', cursor: '0', count, deviceId: '1234567890123456'};
    const spec = config.api[operation];
    const profile = config.request_profiles[spec.profile];
    const params = spec.params.map(([key, value]) => [key, value.startsWith('$') ? variables[value.slice(1)] : value]);
    const url = new URL(engine()(spec.path, params, {...context, ua: profile.ua, uifid: ''}));
    assert.equal(url.origin, profile.origin);
    assert.equal(url.searchParams.get('device_id'), variables.deviceId);
    assert.equal(url.searchParams.get('device_platform'), 'android');
    assert.equal(url.searchParams.has('uifid'), false);
    assert.equal(url.searchParams.has('x-secsdk-web-signature'), false);
    assert.deepEqual(url.searchParams.getAll('aid'), ['1128']);
    assert.equal(url.searchParams.getAll('a_bogus').length, 1);
    assert.equal(url.searchParams.get('count'), count);
  }
  assert.equal(new URL(engine()(config.api.detail.path, [['aweme_id', '123']], context)).searchParams.get('aid'), '6383');
  assert.equal(new URL(engine()(config.api.self.path, [], context)).searchParams.get('aid'), '6383');
});

test('anonymous comments use one JSON response and a separate form without web metadata', () => {
  const spec = config.api.comment;
  const variables = {postId: '123', cursor: '50', count: '50', deviceId: '1234567890123456'};
  const profile = config.request_profiles[spec.profile];
  const params = spec.params.map(([key, value]) => [key, value.startsWith('$') ? variables[value.slice(1)] : value]);
  const url = new URL(engine()(spec.path, params, {ua: profile.ua, now: context.now}));
  assert.equal(spec.method, 'POST');
  assert.deepEqual(spec.form, [['language', 'zh']]);
  assert.equal(url.searchParams.get('need_chunk'), '0');
  assert.equal(url.searchParams.has('browser_version'), false);
  assert.equal(url.searchParams.get('cursor'), '50');
});

test('saved-work POST signs only configured query fields and keeps form fields separate', () => {
  const spec = config.api.savedWorks;
  assert.equal(spec.method, 'POST');
  const url = new URL(engine()(spec.path, spec.params, context));
  assert.equal(url.searchParams.getAll('a_bogus').length, 1);
  assert.equal(url.searchParams.getAll('x-secsdk-web-signature').length, 1);
  assert.equal(url.searchParams.get('publish_video_strategy_type'), '2');
  assert.equal(url.searchParams.has('cursor'), false);
  assert.equal(url.searchParams.has('count'), false);
  assert.deepEqual(spec.form, [['count', '$count'], ['cursor', '$cursor']]);
});

test('liked-work requests sign the own-account identifier and opaque response cursor', () => {
  const spec = config.api.likedWorks;
  const variables = {secUid: 'offline-own-account', cursor: 'opaque+cursor', count: '20'};
  const params = spec.params.map(([key, value]) => [key, value.startsWith('$') ? variables[value.slice(1)] : value]);
  const url = new URL(engine()(spec.path, params, context));
  assert.equal(url.searchParams.get('sec_user_id'), variables.secUid);
  assert.equal(url.searchParams.get('max_cursor'), variables.cursor);
  assert.equal(url.searchParams.getAll('a_bogus').length, 1);
  assert.equal(url.searchParams.getAll('x-secsdk-web-signature').length, 1);
});
