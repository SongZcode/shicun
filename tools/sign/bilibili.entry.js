// WBI signing follows yt-dlp's BilibiliBaseIE (Unlicense); pinned source is recorded in notices.
import md5 from 'js-md5';
function encode(value) { return encodeURIComponent(String(value)).replace(/[!'()*]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase()); }
globalThis.buildUrl = function(path, pairs, ctx) {
  if (!Object.values(__platform.api).some(rule => rule.path === path)) throw new Error('Unknown operation');
  const params = Object.fromEntries(pairs);
  if (path === __platform.api.play.path) {
    if (!ctx.img || !ctx.sub) throw new Error('Missing WBI keys');
    const key = __platform.mixin_order.map(i => (ctx.img + ctx.sub)[i]).join('').slice(0, 32);
    params.wts = String(Math.round(ctx.now));
    const query = Object.keys(params).sort().map(k => encode(k) + '=' + encode(String(params[k]).replace(/[!'()*]/g, ''))).join('&');
    return __platform.origin + path + '?' + query + '&w_rid=' + md5(query + key);
  }
  const query = pairs.map(([k,v]) => encode(k) + '=' + encode(v)).join('&');
  return __platform.origin + path + (query ? '?' + query : '');
};
