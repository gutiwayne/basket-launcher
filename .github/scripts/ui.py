#!/usr/bin/env python3
"""Cesta: print the centre "x y" of the first UI node matching a resource-id suffix or text (uiautomator dump)."""
import re
import sys
import xml.etree.ElementTree as ET

dump, kind, value = sys.argv[1], sys.argv[2], sys.argv[3]
for node in ET.parse(dump).iter("node"):
    attr = node.get("resource-id", "") if kind == "id" else node.get("text", "")
    if (kind == "id" and attr.endswith(value)) or (kind == "text" and attr == value):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(f"not found: {kind}={value}")
