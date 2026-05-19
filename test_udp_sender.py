import socket
import time

PORT = 8282
# Send to localhost to test if the listener works locally
DEST_IP = "127.0.0.1"

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

print(f"Sending test UDP packets to {DEST_IP}:{PORT}...")
print("If the listener is working, you should see these messages in the listener window.")

try:
    for i in range(5):
        msg = f"TEST_PACKET_{i}"
        sock.sendto(msg.encode("utf-8"), (DEST_IP, PORT))
        print(f"Sent: {msg}")
        time.sleep(1)
except KeyboardInterrupt:
    pass
finally:
    sock.close()
