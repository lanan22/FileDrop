import socket
import struct
import time
import threading


print("=" * 40)
print("FileDrop Desktop")
print("=" * 40)

# 获取ip
hostname = socket.gethostname()
ip = socket.gethostbyname(socket.gethostname())

print(f"本机端口IP: {ip}")
print(f"")
print(f"")

print("=" * 40)

# 广播
def broadcast_server():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    message = f"FILEDROP_SERVER:{ip}:12345"
    print("广播已启动（每秒一次，按 Ctrl+C 停止）")
    while True:
        try:
            sock.sendto(message.encode(), ('255.255.255.255', 12346))
            time.sleep(1)
        except:
            break

# 接收
def tcp_server():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind(('0.0.0.0', 12345))
    server.listen(1)
    print("等待手机连接...")

    conn, addr = server.accept()
    print(f"手机已连接: {addr}")

    # 1. 读取文件名长度
    len_data = conn.recv(4)
    if not len_data:
        print("E: 未收到数据")
        return
    file_name_len = struct.unpack('>I', len_data)[0]
    print(f"文件名长度: {file_name_len}")

    # 2. 读取文件名
    file_name_bytes = b''
    while len(file_name_bytes) < file_name_len:
        chunk = conn.recv(file_name_len - len(file_name_bytes))
        if not chunk:
            break
        file_name_bytes += chunk
    file_name = file_name_bytes.decode('utf-8')
    print(f"接收文件: {file_name}")

    # 3. 接收文件内容
    with open(file_name, 'wb') as f:
        while True:
            data = conn.recv(8192)
            if not data:
                break
            f.write(data)

    print("OK.文件保存成功！")
    conn.close()
    server.close()

# 启动
try:
    t1 = threading.Thread(target=broadcast_server, daemon=True)
    t2 = threading.Thread(target=tcp_server)

    t1.start()
    t2.start()

    t2.join()  # 等待接收完成

except KeyboardInterrupt:
    print("\n已停止")
except Exception as e:
    print(f"E.错误: {e}")
finally:
    print("程序退出")


