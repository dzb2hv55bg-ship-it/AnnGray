#!/usr/bin/env python3
"""Reject empty, local, or placeholder API endpoints in distributable APKs."""
import ipaddress
import sys
from urllib.parse import urlsplit


def valid_api_url(value):
    try:
        parsed = urlsplit(value.strip())
        hostname = parsed.hostname
        if parsed.scheme != 'https' or not hostname or parsed.username or parsed.password:
            return False
        if parsed.query or parsed.fragment or parsed.path not in ('', '/'):
            return False
        if parsed.port is not None and not (1 <= parsed.port <= 65535):
            return False
        host = hostname.lower().rstrip('.')
        if host in {'localhost', 'example.com', 'example.org', 'example.net'}:
            return False
        if any(host.endswith(x) for x in ('.local', '.localhost', '.test', '.invalid', '.example')):
            return False
        try:
            ip = ipaddress.ip_address(host)
            if not ip.is_global:
                return False
        except ValueError:
            pass
        return True
    except (ValueError, AttributeError):
        return False


if __name__ == '__main__':
    if len(sys.argv) != 2 or not valid_api_url(sys.argv[1]):
        sys.exit('ERROR: configure MOMENTO_API_URL with a public HTTPS hostname before building a distributable APK.')
    print('MOMENTO_API_URL format: OK (network reachability not verified)')
