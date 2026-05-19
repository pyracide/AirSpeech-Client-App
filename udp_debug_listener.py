import socket

# Target Port is 8282 as configured in the Android App
PORT = 8282

# Create a UDP socket
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

# Bind the socket to all interfaces on port 8282
sock.bind(("0.0.0.0", PORT))

print(f"Listening for UDP haptic packets on port {PORT}...")
print("Ensure your Android device is on the same Wi-Fi network and 'Target PC' is checked in settings.")
print("Press Ctrl+C to exit.\n")

try:
    while True:
        data, addr = sock.recvfrom(1024)
        message = data.decode("utf-8")
        print(f"[{addr[0]}]: {message}")
except KeyboardInterrupt:
    print("\nShutting down UDP listener.")
    sock.close()
