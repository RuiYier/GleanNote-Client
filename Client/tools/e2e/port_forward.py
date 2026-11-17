"""
联调备用通道：当模拟器的 QEMU NAT 无法直连局域网（slirp 出网故障）时，
在宿主机上把本地端口转发到真实服务端，模拟器改连 10.0.2.2 即可。

用法：
    python port_forward.py --target 192.168.10.4 --api-port 8080 --s3-port 9000

默认在宿主机监听：
    0.0.0.0:8081  ->  {target}:8080   （API）
    0.0.0.0:9001  ->  {target}:9000   （对象存储，预签名直传）

模拟器侧需把 BASE_URL 指向 http://10.0.2.2:8081/v1，
并用 iptables 把预签名地址 192.168.10.4:9000 重定向到 10.0.2.2:9001。
"""

import argparse
import socket
import sys
import threading
import time

BUFSIZE = 64 * 1024


def _pump(src: socket.socket, dst: socket.socket) -> None:
    try:
        while True:
            data = src.recv(BUFSIZE)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for sock in (src, dst):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass


def _connect(target: str, target_port: int, attempts: int = 3) -> socket.socket | None:
    """显式建连并重试：docker-proxy 偶尔会瞬时 RST，重试可消除偶发失败。"""
    last: Exception | None = None
    for i in range(attempts):
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(15)
        try:
            sock.connect((target, target_port))
            return sock
        except OSError as err:
            last = err
            sock.close()
            print(f"  [~] 第 {i + 1} 次连接 {target}:{target_port} 失败: {err}", flush=True)
            time.sleep(0.4)
    print(f"  [x] 连接 {target}:{target_port} 最终失败: {last}", flush=True)
    return None


def _handle(client: socket.socket, target: str, target_port: int) -> None:
    upstream = _connect(target, target_port)
    if upstream is None:
        client.close()
        return
    threading.Thread(target=_pump, args=(client, upstream), daemon=True).start()
    threading.Thread(target=_pump, args=(upstream, client), daemon=True).start()


def serve(listen_port: int, target: str, target_port: int) -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    # 只绑回环：adb reverse 连的是宿主机 127.0.0.1，无需对外暴露。
    server.bind(("127.0.0.1", listen_port))
    server.listen(64)
    print(f"  {listen_port} -> {target}:{target_port} 已就绪", flush=True)
    while True:
        client, addr = server.accept()
        print(f"  [+] {addr[0]}:{addr[1]} -> {target}:{target_port}", flush=True)
        threading.Thread(target=_handle, args=(client, target, target_port), daemon=True).start()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True, help="真实服务端地址")
    parser.add_argument("--api-port", type=int, default=8080)
    parser.add_argument("--s3-port", type=int, default=9000)
    parser.add_argument("--local-api", type=int, default=8081)
    parser.add_argument("--local-s3", type=int, default=9001)
    args = parser.parse_args()

    print(f"转发目标 {args.target}；Ctrl+C 结束", flush=True)
    threading.Thread(
        target=serve, args=(args.local_s3, args.target, args.s3_port), daemon=True
    ).start()
    try:
        serve(args.local_api, args.target, args.api_port)
    except KeyboardInterrupt:
        print("\n已停止", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
