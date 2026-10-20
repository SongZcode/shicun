// a_bogus port: jiji262/douyin-downloader (MIT), original JohnserfSeed (Apache-2.0).
// secsdk: Evil0ctal/Douyin_TikTok_Download_API (Apache-2.0).
import sm3 from 'sm-crypto/src/sm3/index.js';
import md5 from 'js-md5';

const ALPHABETS = [
  'Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe',
  'ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe'
];
const BOX = [121,243,55,234,103,36,47,228,30,231,106,6,115,95,78,101,250,207,198,50,139,227,220,105,97,143,34,28,194,215,18,100,159,160,43,8,169,217,180,120,247,45,90,11,27,197,46,3,84,72,5,68,62,56,221,75,144,79,73,161,178,81,64,187,134,117,186,118,16,241,130,71,89,147,122,129,65,40,88,150,110,219,199,255,181,254,48,4,195,248,208,32,116,167,69,201,17,124,125,104,96,83,80,127,236,108,154,126,204,15,20,135,112,158,13,1,188,164,210,237,222,98,212,77,253,42,170,202,26,22,29,182,251,10,173,152,58,138,54,141,185,33,157,31,252,132,233,235,102,196,191,223,240,148,39,123,92,82,128,109,57,24,38,113,209,245,2,119,153,229,189,214,230,174,232,63,52,205,86,140,66,175,111,171,246,133,238,193,99,60,74,91,225,51,76,37,145,211,166,151,213,206,0,200,244,176,218,44,184,172,49,216,93,168,53,21,183,41,67,85,224,155,226,242,87,177,146,70,190,12,162,19,137,114,25,165,163,192,23,59,9,94,179,107,35,7,142,131,239,203,149,136,61,249,14,156];
const ORDER = [18,20,52,26,30,34,58,38,40,53,42,21,27,54,55,31,35,57,39,41,43,22,28,32,60,36,23,29,33,37,44,45,59,46,47,48,49,50,24,25,65,66,70,71];
const XOR_ORDER = [18,20,26,30,34,38,40,42,21,27,31,35,39,41,43,22,28,32,36,23,29,33,37,44,45,46,47,48,49,50,24,25,52,53,54,55,57,58,59,60,65,66,70,71];
const SALT = 'A96D855A08C0A9707F8BEF0D9A527E4E';

function hash(input, salted = true) {
  if (typeof input === 'string' && salted) input += 'cus';
  return sm3(input).match(/../g).map(x => parseInt(x, 16));
}
function base64(bytes, alphabet) {
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const n = (bytes[i] << 16) | ((bytes[i + 1] || 0) << 8) | (bytes[i + 2] || 0);
    out += alphabet[(n >>> 18) & 63] + alphabet[(n >>> 12) & 63];
    if (i + 1 < bytes.length) out += alphabet[(n >>> 6) & 63];
    if (i + 2 < bytes.length) out += alphabet[n & 63];
  }
  return out + '='.repeat((4 - out.length % 4) % 4);
}
function rc4(bytes, key) {
  const s = Array.from({length: 256}, (_, i) => i);
  let j = 0;
  for (let i = 0; i < 256; i++) {
    j = (j + s[i] + key[i % key.length]) % 256;
    [s[i], s[j]] = [s[j], s[i]];
  }
  let i = 0; j = 0;
  return bytes.map(c => {
    i = (i + 1) % 256; j = (j + s[i]) % 256;
    [s[i], s[j]] = [s[j], s[i]];
    return c ^ s[(s[i] + s[j]) % 256];
  });
}
function transform(bytes) {
  const box = BOX.slice();
  let b = box[1], initial = 0, e = 0;
  return bytes.map((c, index) => {
    let sum;
    if (index === 0) {
      initial = box[b]; sum = b + initial; box[1] = initial; box[b] = b;
    } else sum = initial + e;
    const encrypted = c ^ box[sum % 256];
    e = box[(index + 2) % 256];
    sum = (b + e) % 256;
    initial = box[sum]; box[sum] = box[(index + 2) % 256];
    box[(index + 2) % 256] = initial; b = sum;
    return encrypted;
  });
}
function fingerprint() {
  const rand = (min, max) => min + Math.floor(Math.random() * (max - min + 1));
  const w = rand(1024, 1920), h = rand(768, 1080);
  return [w,h,w+rand(24,32),h+rand(75,90),0,Math.random()<0.5?0:30,0,0,
    rand(1024,1920),rand(768,1080),rand(1280,1920),rand(800,1080),w,h,24,24,'Win32'].join('|');
}
function abogus(query, ua) {
  const start = Date.now();
  const a = hash(hash(query)), b = hash(hash(''));
  const u = hash(base64(rc4(Array.from(ua, c => c.charCodeAt(0)), [0,1,14]), ALPHABETS[1]), false);
  const end = Date.now(), fp = fingerprint();
  const d = {18:44,26:0,27:0,28:0,29:0,30:0,31:1,32:0,33:0,34:0,35:0,36:0,37:8,
    38:a[21],39:a[22],40:b[21],41:b[22],42:u[23],43:u[24],48:3,
    52:0,53:0,54:0,55:0,57:6383&255,58:(6383>>>8)&255,59:0,60:0,
    65:fp.length,66:0,70:0,71:0};
  for (const [time, positions] of [[start,[20,21,22,23,24,25]], [end,[44,45,46,47,49,50]]]) {
    d[positions[0]] = Math.floor(time / 2**24) & 255;
    d[positions[1]] = Math.floor(time / 2**16) & 255;
    d[positions[2]] = Math.floor(time / 2**8) & 255;
    d[positions[3]] = time & 255;
    d[positions[4]] = Math.floor(time / 2**32);
    d[positions[5]] = Math.floor(time / 2**40);
  }
  const bytes = ORDER.map(i => d[i] || 0);
  let xor = 0;
  for (const i of XOR_ORDER) xor ^= d[i] || 0;
  bytes.push(...Array.from(fp, c => c.charCodeAt(0)), xor);
  const random = [];
  for (let i = 0; i < 3; i++) {
    const r = Math.floor(Math.random() * 10000);
    random.push((r & 255 & 170)|1, (r & 255 & 85)|2, ((r>>>8)&170)|5, ((r>>>8)&85)|40);
  }
  return base64(random.concat(transform(bytes)), ALPHABETS[0]);
}
// Python urlencode's form encoding; keep it identical before hashing and sending.
function encode(value) {
  return encodeURIComponent(String(value)).replace(/[!'()*]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase()).replace(/%20/g, '+');
}
function encodePairs(pairs) { return pairs.map(([k,v]) => encode(k) + '=' + encode(v)).join('&'); }

globalThis.buildUrl = function buildUrl(path, params, ctx) {
  const config = globalThis.__platform;
  if (!config || !ctx.ua || !Array.isArray(params)) throw new Error('INVALID_SIGN_CONTEXT');
  const operation = Object.values(config.api).find(api => api.path === path);
  if (!operation) throw new Error('UNKNOWN_ENDPOINT');
  const profile = operation.profile ? config.request_profiles[operation.profile] : config;
  if (!profile || !Array.isArray(profile.common_query)) throw new Error('INVALID_REQUEST_PROFILE');
  const pairs = profile.common_query.map(pair => pair.slice());
  const set = (key, value) => {
    if (pairs.some(pair => pair[0] === key)) pairs.find(pair => pair[0] === key)[1] = String(value);
    else pairs.push([key, String(value)]);
  };
  for (const pair of params) {
    if (['a_bogus','timestamp','x-secsdk-web-signature'].includes(pair[0])) throw new Error('PRE_SIGNED_PARAM');
    set(pair[0], pair[1]);
  }
  const protectedPath = config.protected.includes(path);
  if (protectedPath) {
    if (!ctx.uifid) throw new Error('NEED_LOGIN');
    set('uifid', ctx.uifid);
  }
  const raw = encodePairs(pairs);
  pairs.push(['a_bogus', abogus(raw, ctx.ua)]);
  if (protectedPath) {
    const timestamp = String(Math.floor(ctx.now));
    pairs.push(['timestamp', timestamp]);
    // Reference websign reserializes every pair using quote(safe='*-._').
    const secsdkEncode = value => encodeURIComponent(String(value)).replace(/[!'()]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase());
    const covered = pairs.map(([k,v]) => secsdkEncode(k) + '=' + secsdkEncode(v)).join('&');
    const signature = md5(ctx.uifid + '_' + timestamp + '_' + SALT + '_' + covered);
    return profile.origin + path + '?' + covered + '&x-secsdk-web-signature=' + signature;
  }
  return profile.origin + path + '?' + encodePairs(pairs);
};
