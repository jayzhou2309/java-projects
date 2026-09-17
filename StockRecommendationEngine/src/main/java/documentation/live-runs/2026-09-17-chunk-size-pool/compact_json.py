#!/usr/bin/env python3
"""Remove whitespace outside JSON strings, leaving every token (numbers included) as written.

usage: compact_json.py <in> <out>
Scans characters, tracking string state and escapes; then checks that both texts parse to equal values with numbers read as written
(Decimal) and prints parses_equal_numbers_as_written=<True|False>; exits 1 when not equal. Used on the evidence reports, which the
application returns pretty-printed (plan plans/2026-09-17-chunk-size-pool.md, Milestone 1).
"""
import json, sys
from decimal import Decimal
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding='utf-8').read()
out = []
in_string = False
escaped = False
for ch in text:
    if in_string:
        out.append(ch)
        if escaped:
            escaped = False
        elif ch == '\\':
            escaped = True
        elif ch == '"':
            in_string = False
    elif ch == '"':
        in_string = True
        out.append(ch)
    elif ch not in ' \t\r\n':
        out.append(ch)
compact = ''.join(out)
equal = json.loads(text, parse_float=Decimal) == json.loads(compact, parse_float=Decimal)
open(dst, 'w', encoding='utf-8').write(compact + '\n')
print('compact %s -> %s bytes %d -> %d parses_equal_numbers_as_written=%s' % (src.split('/')[-1], dst.split('/')[-1], len(text.encode()), len(compact.encode()) + 1, equal))
sys.exit(0 if equal else 1)
