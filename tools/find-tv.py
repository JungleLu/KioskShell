#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在局域网里找开着 ADB 的电视。

用法：
    python find-tv.py                 # 扫本机所在 /24 网段的 5555
    python find-tv.py --ports 5555,8009,9999
    python find-tv.py --net 192.168.1.0/24

为什么会找不到：电视上的「ADB 调试 / 网络调试」没打开，或者电视和电脑不在同一个网段。
端口没开时这里一定扫不到，先回去开开关。
"""
import argparse
import asyncio
import ipaddress
import socket


def local_nets():
    """取本机所有非回环 IPv4 的 /24，手机热点、双网卡都能覆盖。"""
    nets = set()
    try:
        host = socket.gethostname()
        for info in socket.getaddrinfo(host, None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith("127.") or ip.startswith("169.254."):
                continue
            nets.add(str(ipaddress.ip_network(ip + "/24", strict=False)))
    except Exception:
        pass

    # getaddrinfo 有时只返回回环，用一个外部地址探测真实出口 IP
    for probe in ("8.8.8.8", "223.5.5.5"):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect((probe, 53))
            ip = s.getsockname()[0]
            s.close()
            if not ip.startswith("127."):
                nets.add(str(ipaddress.ip_network(ip + "/24", strict=False)))
        except Exception:
            pass
    return sorted(nets)


async def probe(ip, port, timeout):
    try:
        _, w = await asyncio.wait_for(asyncio.open_connection(str(ip), port), timeout=timeout)
        w.close()
        return str(ip)
    except Exception:
        return None


async def scan(net, ports, timeout):
    hosts = list(ipaddress.ip_network(net).hosts())
    print("扫描 %s，端口 %s，共 %d 台 ..." % (net, ",".join(map(str, ports)), len(hosts)))
    hits = {}
    for port in ports:
        res = await asyncio.gather(*[probe(ip, port, timeout) for ip in hosts])
        for ip in res:
            if ip:
                hits.setdefault(ip, []).append(port)
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--net", help="网段，如 192.168.1.0/24；不填则自动取本机 /24")
    ap.add_argument("--ports", default="5555", help="要探测的端口，逗号分隔")
    ap.add_argument("--timeout", type=float, default=0.45, help="单台超时秒数")
    args = ap.parse_args()

    ports = [int(p) for p in args.ports.split(",") if p.strip()]
    nets = [args.net] if args.net else local_nets()
    if not nets:
        print("没能判断本机网段，请手动传 --net")
        return 1

    all_hits = {}
    for net in nets:
        hits = asyncio.run(scan(net, ports, args.timeout))
        for ip, ps in hits.items():
            all_hits.setdefault(ip, []).extend(ps)

    if not all_hits:
        print("\n没找到开着 5555 的设备。")
        print("排查顺序：")
        print("  1. 电视是否开机、是否和本机连同一个 WiFi（不要连访客网络）")
        print("  2. 电视上是否打开了「ADB 调试 / 网络调试」")
        print("  3. 电视是不是 5G 频段但走了不同网段")
        return 1

    print("\n找到这些：")
    for ip in sorted(all_hits, key=lambda x: tuple(int(n) for n in x.split("."))):
        ports_text = ",".join(str(p) for p in sorted(set(all_hits[ip])))
        mark = "  <-- 大概率是电视" if 5555 in all_hits[ip] else ""
        print("  %-15s 开放 %s%s" % (ip, ports_text, mark))
    print("\n下一步：")
    print("  powershell -ExecutionPolicy Bypass -File tools\\tv-install.ps1 -TvIp <上面的IP>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
