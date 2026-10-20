"""Generate a deterministic vector from the permitted upstream Python implementation."""
import argparse
import importlib.util
import json
from unittest.mock import patch
from urllib.parse import urlencode

parser = argparse.ArgumentParser()
parser.add_argument('--source', required=True)
parser.add_argument('--config', default='bundle_src/platforms/douyin.json')
parser.add_argument('--websign', required=True)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('upstream_abogus', args.source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
with open(args.config, encoding='utf-8') as stream:
    config = json.load(stream)
pairs = config['common_query'] + [['aweme_id', '7697489379418420475'], ['cursor', '0'], ['count', '50'], ['item_type', '0']]
query = urlencode([tuple(pair) for pair in pairs])
fingerprint = '1134|806|1159|882|0|0|0|0|1134|806|1359|834|1134|806|24|24|Win32'
with patch.object(module.time, 'time', return_value=1700000000.123), patch.object(module.random, 'random', return_value=0.123456):
    value = module.ABogus(fp=fingerprint, user_agent=config['ua'], options=[0, 1, 8]).generate_abogus(query, '')[1]
web_spec = importlib.util.spec_from_file_location('reference_websign', args.websign)
web = importlib.util.module_from_spec(web_spec)
web_spec.loader.exec_module(web)
protected_pairs = config['common_query'] + [['aweme_id', '1'], ['extra', '中文 +/~*'], ['uifid', 'offline_test_id']]
protected_query = urlencode([tuple(pair) for pair in protected_pairs])
with patch.object(module.time, 'time', return_value=1700000000.123), patch.object(module.random, 'random', return_value=0.123456):
    protected_bogus = module.ABogus(fp=fingerprint, user_agent=config['ua'], options=[0, 1, 8]).generate_abogus(protected_query, '')[1]
protected_pairs.append(['a_bogus', protected_bogus])
signed = web.sign(protected_pairs, 'offline_test_id', timestamp=1700000000)[0]
print(json.dumps({'source': 'jiji262/douyin-downloader + Evil0ctal/dtk websign', 'abogus': value,
                 'protectedQuery': signed}, separators=(',', ':')))
